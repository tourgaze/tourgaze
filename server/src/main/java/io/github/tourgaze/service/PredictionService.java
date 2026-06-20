/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.tourgaze.dto.PredictionDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.SettingRepository;
import io.github.tourgaze.store.StorageService;
import io.github.tourgaze.util.Geo;

/**
 * Enriches a soon-to-be-imported ride with internet-sourced metadata so the
 * AddTour form opens with sensible defaults instead of an empty shell.
 *
 * Sources today:
 * 1. Nominatim reverse geocoding — start / end place names + country / region
 * in the user's configured display language (so a Mallorca ride reads
 * "Mallorca, Spanien" in German mode instead of "Balearic Islands").
 * 2. Local activity DB — tags lifted from rides whose start is within ~200m
 * of the new ride's start (Hausrunde-style match). Frequency-weighted.
 *
 * Nominatim usage policy compliance:
 * - One request at a time (no parallel fetches).
 * - User-Agent header set to identify the app per OSM policy.
 * - Results cached on disk (cache/geocode.json) keyed on a ~200m grid +
 * language, reloaded on startup — so a place is fetched once, ever.
 */
@Service
public class PredictionService {

	private static final Logger log = LoggerFactory.getLogger(PredictionService.class);
	private static final String NOMINATIM_BASE = "https://nominatim.openstreetmap.org/reverse";
	private static final double SIMILAR_START_RADIUS_DEG = 0.002; // ~200m at mid-latitudes
	private static final int SUGGESTED_TAG_LIMIT = 5;

	private final SettingRepository settingRepo;
	private final ActivityRepository activityRepo;
	private final ObjectMapper objectMapper;
	private final StorageService storage;
	private final HttpClient httpClient;

	/**
	 * Reverse-geocode results, cached on disk (cache/geocode.json) just like map
	 * tiles — so a place is looked up once, ever. Most rides start at the same
	 * home/trailhead, and at ~200m grid resolution they all share one entry, so
	 * after the first run Nominatim is barely touched (and never on restart).
	 */
	private final ConcurrentHashMap<String, ReverseGeoResult> geoCache = new ConcurrentHashMap<>();
	private final AtomicBoolean geoDirty = new AtomicBoolean(false);

	/**
	 * Min spacing between actual Nominatim calls (cache misses only) — OSM policy
	 * is
	 * ~1 req/s, one at a time. Cache hits never reach the network, so re-warming an
	 * all-cached inbox is instant; only genuinely-new places are throttled here.
	 */
	private static final long NOMINATIM_GAP_MS = 1100;
	private final Object nominatimGate = new Object();
	private long lastNominatimCallMs = 0;

	public PredictionService(SettingRepository settingRepo,
			ActivityRepository activityRepo,
			ObjectMapper objectMapper,
			StorageService storage) {
		this.settingRepo = settingRepo;
		this.activityRepo = activityRepo;
		this.objectMapper = objectMapper;
		this.storage = storage;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(8))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	private Path geoCacheFile() {
		return storage.cacheDir().resolve("geocode.json");
	}

	/** Load the on-disk geocode cache once at startup (best-effort). */
	@PostConstruct
	void loadGeoCache() {
		Path f = geoCacheFile();
		if (!Files.isRegularFile(f))
			return;
		try {
			Map<String, ReverseGeoResult> saved = objectMapper.readValue(Files.readAllBytes(f),
					new TypeReference<Map<String, ReverseGeoResult>>() {
					});
			geoCache.putAll(saved);
			log.info("[Geocode] loaded {} cached places from {}", geoCache.size(), f);
		} catch (Exception e) {
			log.debug("[Geocode] cache load skipped: {}", e.getMessage());
		}
	}

	/** Persist new geocode entries to disk periodically (and on shutdown). */
	@Scheduled(fixedDelay = 30_000)
	public void flushGeoCache() {
		if (!geoDirty.getAndSet(false))
			return;
		Path f = geoCacheFile();
		try {
			Files.createDirectories(f.getParent());
			Files.write(f, objectMapper.writeValueAsBytes(geoCache));
		} catch (IOException e) {
			geoDirty.set(true); // retry next tick
			log.debug("[Geocode] cache flush failed: {}", e.getMessage());
		}
	}

	@PreDestroy
	void flushGeoCacheOnShutdown() {
		geoDirty.set(true);
		flushGeoCache();
	}

	/**
	 * Block until ≥{@link #NOMINATIM_GAP_MS} since the last real Nominatim call.
	 */
	private void throttleNominatim() {
		synchronized (nominatimGate) {
			long wait = NOMINATIM_GAP_MS - (System.currentTimeMillis() - lastNominatimCallMs);
			if (wait > 0) {
				try {
					Thread.sleep(wait);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			lastNominatimCallMs = System.currentTimeMillis();
		}
	}

	/** Resolved language for outbound geocoding requests. Default DE. */
	public String currentLanguage() {
		return settingRepo.findById("app.language")
				.map(s -> s.getValue() == null || s.getValue().isBlank() ? "de" : s.getValue())
				.orElse("de");
	}

	/**
	 * Top-level entry point. Always returns a non-null DTO — fields stay null
	 * for anything we couldn't fetch / derive, and the frontend treats nulls
	 * as "no suggestion."
	 */
	public PredictionDto predict(double startLat, double startLon,
			Double endLat, Double endLon,
			Double distanceKm) {
		String lang = currentLanguage();
		ReverseGeoResult start = reverseGeocode(startLat, startLon, lang);

		// Only reverse the end point if it's far enough from start to be
		// meaningfully different. Loop rides and Hausrunden return to within
		// a few hundred metres of where they started.
		boolean loop = distanceKm == null || distanceKm < 2.0
				|| endLat == null || endLon == null
				|| Geo.distanceKm(startLat, startLon, endLat, endLon) < 0.5;
		ReverseGeoResult end = loop ? null : reverseGeocode(endLat, endLon, lang);

		String startLabel = start != null ? start.displayLabel : null;
		String endLabel = end != null ? end.displayLabel : null;

		String suggestedName;
		if (startLabel != null && endLabel != null && !endLabel.equals(startLabel)) {
			suggestedName = startLabel + " → " + endLabel;
		} else if (startLabel != null) {
			// "Tegernsee Tour" / "Mallorca Tour" — works as a placeholder the
			// user can refine without staring at an empty field.
			suggestedName = startLabel + " Tour";
		} else {
			suggestedName = null;
		}

		List<String> tagIds = suggestSimilarTagIds(startLat, startLon);

		return new PredictionDto(
				startLabel,
				endLabel,
				suggestedName,
				tagIds,
				start != null ? start.country : null,
				start != null ? start.region : null,
				lang);
	}

	// ── Nominatim reverse geocode ───────────────────────────────────────────

	/**
	 * Reverse-geocode, served from the on-disk cache ({@link #geoCache}) snapped to
	 * a ~200m grid + lang. Reverse-geocoding doesn't need GPS precision — every
	 * ride
	 * from the same home / trailhead wants the same place name — so a coarse grid
	 * collapses a whole cluster of starts into ONE Nominatim call, looked up once
	 * ever (the cache persists across restarts, like map tiles). Failures (null —
	 * timeout / rate-limit) are NOT cached so each lookup retries independently.
	 */
	public ReverseGeoResult reverseGeocode(double lat, double lon, String lang) {
		// ~200m grid key → every ride from the same home/trailhead shares one entry.
		String key = Math.round(lat / 0.002) + "|" + Math.round(lon / 0.002) + "|" + lang;
		ReverseGeoResult hit = geoCache.get(key);
		if (hit != null)
			return hit;
		ReverseGeoResult result = fetchReverseGeocode(lat, lon, lang);
		if (result != null) { // never cache failures — they retry independently
			geoCache.put(key, result);
			geoDirty.set(true);
		}
		return result;
	}

	/**
	 * One Nominatim round-trip. Returns null on any failure (kept out of cache).
	 */
	private ReverseGeoResult fetchReverseGeocode(double lat, double lon, String lang) {
		try {
			String url = NOMINATIM_BASE
					+ "?lat=" + lat
					+ "&lon=" + lon
					+ "&format=jsonv2"
					+ "&zoom=14" // ~suburb level — enough to get village / town / island
					+ "&addressdetails=1"
					+ "&accept-language=" + lang;
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("User-Agent", "TourGaze/1.0 (https://github.com/tourgaze)")
					.timeout(Duration.ofSeconds(8))
					.build();
			throttleNominatim(); // serialize + space real calls; cache hits never get here
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				log.debug("Nominatim {} for ({}, {}): {}", resp.statusCode(), lat, lon, resp.body());
				return null;
			}
			JsonNode root = objectMapper.readTree(resp.body());
			JsonNode addr = root.path("address");

			// Most-specific → least-specific. The user wants "Mallorca" if the
			// ride is on Mallorca, not "Balearen", so island ranks above state.
			String place = firstNonBlank(
					addr.path("village").asText(null),
					addr.path("town").asText(null),
					addr.path("city").asText(null),
					addr.path("hamlet").asText(null),
					addr.path("suburb").asText(null),
					addr.path("island").asText(null),
					addr.path("county").asText(null),
					addr.path("state").asText(null),
					root.path("display_name").asText(null));
			String region = firstNonBlank(
					addr.path("state").asText(null),
					addr.path("county").asText(null),
					addr.path("region").asText(null));
			String country = addr.path("country_code").asText(null);
			String countryName = addr.path("country").asText(null);

			// Display label: "place, region" is informative without being long.
			// Fall back to country if region is missing.
			String label;
			if (place != null && region != null && !place.equalsIgnoreCase(region)) {
				label = place + ", " + region;
			} else if (place != null && countryName != null && !place.equalsIgnoreCase(countryName)) {
				label = place + ", " + countryName;
			} else {
				label = place != null ? place : countryName;
			}

			return new ReverseGeoResult(label, country == null ? null : country.toUpperCase(Locale.ROOT), region);
		} catch (Exception e) {
			log.warn("Nominatim lookup failed for ({}, {}): {}", lat, lon, e.getMessage());
			return null;
		}
	}

	public record ReverseGeoResult(String displayLabel, String country, String region) {
	}

	/** One forward-geocode hit — surfaced to the AddTour autocomplete. */
	public record PlaceProposal(String name, String country, String region, String displayLabel, Double lat,
			Double lon, Double south, Double north, Double west, Double east) {
	}

	// ── Forward geocode (autocomplete) ──────────────────────────────────────

	/**
	 * Free-text place lookup via Nominatim's `/search` endpoint, used by the
	 * AddTour autocomplete. Returns up to {@code limit} matches in the
	 * configured display language. Cached by lowercase trimmed query + lang
	 * for 7 days — place names don't change.
	 *
	 * Nominatim policy: 1 req/sec recommended max, and they ask we set a
	 * descriptive User-Agent. Cache hit rate is high in practice (user types
	 * "Mall" → "Mallo" → "Mallorca" sharing the early prefixes is unusual,
	 * but identical follow-up sessions reuse).
	 */
	// Use positional arg names (#a0/#a1) — Java doesn't ship parameter names
	// to SpEL unless the class is compiled with `-parameters`. Also coalesce
	// a null/blank q to "" so SpEL doesn't NPE before the method body runs.
	@Cacheable(value = "geocode", key = "'search:' + (#a0 == null ? '' : #a0).toLowerCase().trim() + '|' + #a1")
	public List<PlaceProposal> searchPlaces(String q, String lang, int limit) {
		if (q == null || q.trim().length() < 2)
			return List.of();
		try {
			String url = "https://nominatim.openstreetmap.org/search"
					+ "?q=" + java.net.URLEncoder.encode(q.trim(), java.nio.charset.StandardCharsets.UTF_8)
					+ "&format=jsonv2"
					+ "&addressdetails=1"
					+ "&limit=" + Math.max(1, Math.min(10, limit))
					+ "&accept-language=" + lang;
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("User-Agent", "TourGaze/1.0 (https://github.com/tourgaze)")
					.timeout(Duration.ofSeconds(8))
					.build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				log.debug("Nominatim search {} for '{}': {}", resp.statusCode(), q, resp.body());
				return List.of();
			}
			JsonNode arr = objectMapper.readTree(resp.body());
			if (!arr.isArray())
				return List.of();

			List<PlaceProposal> out = new ArrayList<>();
			for (JsonNode hit : arr) {
				JsonNode addr = hit.path("address");
				String name = firstNonBlank(
						addr.path("village").asText(null),
						addr.path("town").asText(null),
						addr.path("city").asText(null),
						addr.path("hamlet").asText(null),
						addr.path("suburb").asText(null),
						addr.path("island").asText(null),
						addr.path("county").asText(null),
						addr.path("state").asText(null),
						hit.path("name").asText(null));
				String region = firstNonBlank(
						addr.path("state").asText(null),
						addr.path("county").asText(null),
						addr.path("region").asText(null));
				String country = addr.path("country_code").asText(null);
				String displayLabel = hit.path("display_name").asText(null);
				Double lat = hit.has("lat") ? hit.path("lat").asDouble() : null;
				Double lon = hit.has("lon") ? hit.path("lon").asDouble() : null;
				// Nominatim returns boundingbox as [south, north, west, east]
				// (strings) — the place's real extent, used for "near:" filtering.
				JsonNode bb = hit.path("boundingbox");
				Double south = null, north = null, west = null, east = null;
				if (bb.isArray() && bb.size() == 4) {
					south = bb.get(0).asDouble();
					north = bb.get(1).asDouble();
					west = bb.get(2).asDouble();
					east = bb.get(3).asDouble();
				}
				if (name == null)
					continue;
				out.add(new PlaceProposal(name,
						country == null ? null : country.toUpperCase(Locale.ROOT),
						region, displayLabel, lat, lon, south, north, west, east));
			}
			return out;
		} catch (Exception e) {
			log.warn("Nominatim search failed for '{}': {}", q, e.getMessage());
			return List.of();
		}
	}

	// ── Similar-activity tag suggestions ────────────────────────────────────

	/**
	 * Bounding-box query over `activity` for rides whose start is within
	 * ~200m of the new ride's start. Each matched ride's tags vote;
	 * top-N by frequency become the suggestions.
	 *
	 * Indexed by start_lat / start_lon — the migration could add a covering
	 * index here later but at v1 scale (hundreds of activities) it's irrelevant.
	 */
	private List<String> suggestSimilarTagIds(double lat, double lon) {
		double minLat = lat - SIMILAR_START_RADIUS_DEG;
		double maxLat = lat + SIMILAR_START_RADIUS_DEG;
		double minLon = lon - SIMILAR_START_RADIUS_DEG;
		double maxLon = lon + SIMILAR_START_RADIUS_DEG;

		List<Activity> near = activityRepo.findByStartLatBetweenAndStartLonBetween(
				minLat, maxLat, minLon, maxLon);
		if (near.isEmpty())
			return List.of();

		Map<String, Integer> votes = new HashMap<>();
		for (Activity a : near) {
			if (a.getTags() == null)
				continue;
			for (var t : a.getTags()) {
				if (t.getId() == null)
					continue;
				votes.merge(t.getId(), 1, Integer::sum);
			}
		}
		return votes.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(SUGGESTED_TAG_LIMIT)
				.map(Map.Entry::getKey)
				.toList();
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private static String firstNonBlank(String... candidates) {
		for (String c : candidates) {
			if (c != null && !c.isBlank())
				return c;
		}
		return null;
	}
}
