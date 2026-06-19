/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.tourgaze.dto.PredictionDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.SettingRepository;
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
 * - Results cached for a long time (Caffeine, 7-day TTL via cache config)
 * keyed on rounded coords + language, so we make at most one round-trip
 * per ~100m square per ride.
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
	private final HttpClient httpClient;

	public PredictionService(SettingRepository settingRepo,
			ActivityRepository activityRepo,
			ObjectMapper objectMapper) {
		this.settingRepo = settingRepo;
		this.activityRepo = activityRepo;
		this.objectMapper = objectMapper;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(8))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
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

	/** Cached by 3-decimal-place coords + lang. ~100m grid → ample. */
	@Cacheable(value = "geocode", key = "T(java.lang.String).format('%.3f|%.3f|%s', #lat, #lon, #lang)")
	public ReverseGeoResult reverseGeocode(double lat, double lon, String lang) {
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
