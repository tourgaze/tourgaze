/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.tourgaze.domain.RideEventType;
import io.github.tourgaze.dto.RideEventDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.parser.TrackPoint;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.SettingRepository;

/**
 * Fills in {@link Activity}'s weather columns from a historical-weather API.
 * Default provider is Open-Meteo's archive endpoint — no API key, free for
 * personal use, returns hourly data. Disabled if {@code weather.enabled}
 * setting is "false".
 *
 * GET https://archive-api.open-meteo.com/v1/archive
 * ?latitude=…&longitude=…
 * &start_date=YYYY-MM-DD&end_date=YYYY-MM-DD
 * &hourly=temperature_2m,relative_humidity_2m,windspeed_10m,weathercode
 * &timezone=UTC
 */
@Service
public class WeatherService {

	private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

	private static final String OPEN_METEO_URL = "https://archive-api.open-meteo.com/v1/archive"
			+ "?latitude=%.4f&longitude=%.4f"
			+ "&start_date=%s&end_date=%s"
			+ "&hourly=temperature_2m,relative_humidity_2m,windspeed_10m,weathercode"
			+ "&timezone=UTC";

	private final ActivityRepository activityRepo;
	private final SettingRepository settingRepo;
	private final HttpClient http;
	private final ObjectMapper json;
	private final org.springframework.context.ApplicationEventPublisher events;

	/**
	 * In-memory cache of successful lookups. Historical weather is immutable, so a
	 * result is reusable forever; we key by ~1 km-rounded location + the UTC hour,
	 * which collapses the many rides that start at the same place/time (and the
	 * inbox re-warms) onto one Open-Meteo call. Bounded; only real hits are cached
	 * (errors/empties stay uncached so they retry).
	 */
	private final Cache<String, WeatherResult> cache = Caffeine.newBuilder().maximumSize(10_000).build();

	public WeatherService(ActivityRepository activityRepo, SettingRepository settingRepo, ObjectMapper json,
			org.springframework.context.ApplicationEventPublisher events) {
		this.activityRepo = activityRepo;
		this.settingRepo = settingRepo;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		this.json = json;
		this.events = events;
	}

	/** Snapshot of weather at a point in space + time. */
    public record WeatherResult(Double tempC, Integer humidityPct, Double windKph, String condition, Integer wmoCode) {
        public static WeatherResult empty() { return new WeatherResult(null, null, null, null, null); }
    }

	public WeatherResult fetchWeather(Double lat, Double lon, Instant time) {
		if (!isEnabled() || lat == null || lon == null || time == null)
			return WeatherResult.empty();
		String cacheKey = String.format(java.util.Locale.ROOT, "%.2f,%.2f,%d",
				lat, lon, time.truncatedTo(ChronoUnit.HOURS).getEpochSecond());
		WeatherResult cached = cache.getIfPresent(cacheKey);
		if (cached != null)
			return cached;
		try {
			LocalDate day = time.atZone(ZoneOffset.UTC).toLocalDate();
			String url = String.format(java.util.Locale.ROOT, OPEN_METEO_URL, lat, lon, day, day);
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(10))
					.GET().build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				log.warn("Weather lookup HTTP {} for {}", resp.statusCode(), url);
				return WeatherResult.empty();
			}
			JsonNode hourly = json.readTree(resp.body()).path("hourly");
			JsonNode times = hourly.path("time");
			if (!times.isArray() || times.isEmpty())
				return WeatherResult.empty();
			int idx = nearestHourIndex(times, time);
			if (idx < 0)
				return WeatherResult.empty();
			Integer code = intAt(hourly, "weathercode", idx);
			WeatherResult result = new WeatherResult(
					doubleAt(hourly, "temperature_2m", idx),
					intAt(hourly, "relative_humidity_2m", idx),
					doubleAt(hourly, "windspeed_10m", idx),
					decodeWmoCode(code),
					code);
			// Cache only real data (don't pin errors/empties — let them retry).
			if (result.tempC() != null)
				cache.put(cacheKey, result);
			return result;
		} catch (Exception e) {
			log.warn("Weather lookup errored: {}", e.getMessage());
			return WeatherResult.empty();
		}
	}

	/**
	 * Populate an imported activity's weather AND scan the track for a rain shower
	 * or two — all in ONE async transaction so the two writes can't race (the
	 * entity is optimistically locked). {@code pts} are passed from the import so
	 * we
	 * don't re-parse; pass an empty list to skip the rain scan.
	 */
	@Async
	@Transactional
	public void fetchAndStoreAsync(String activityId, List<TrackPoint> pts) {
		try {
			Activity a = activityRepo.findById(activityId).orElse(null);
			if (a == null)
				return;
			if (a.getWeather().getFetchedAt() != null && a.getWeather().getTempC() != null)
				return;

			WeatherResult w = fetchWeather(a.getStartLat(), a.getStartLon(), a.getStartTime());
			if (w.tempC() == null && w.condition() == null)
				return;

			a.getWeather().setTempC(w.tempC());
			a.getWeather().setHumidityPct(w.humidityPct());
			a.getWeather().setWindKph(w.windKph());
			a.getWeather().setCondition(w.condition());
			a.getWeather().setFetchedAt(Instant.now());

			// Look for a rain shower or two along the route and pin them as events.
			// Store as plain JSON maps (ISO time via the Spring ObjectMapper) so the
			// json column only ever holds primitive JSON values.
			List<RideEventDto> rain = detectRainEvents(pts);
			if (!rain.isEmpty()) {
				Map<String, Object> attrs = new HashMap<>(a.getAttributes());
				List<Object> eventJson = rain.stream()
						.map(e -> (Object) json.convertValue(e, Map.class))
						.toList();
				attrs.put(RideEventDto.ATTRIBUTES_KEY, eventJson);
				a.setAttributes(attrs);
			}

			activityRepo.save(a);
			events.publishEvent(new io.github.tourgaze.event.ActivityEvents.Changed(activityId));
			log.info("Weather populated for activity {} ({}°C, {}); {} rain event(s)",
					activityId, w.tempC(), w.condition(), rain.size());
		} catch (Exception e) {
			log.warn("Weather/rain fetch errored for {}: {}", activityId, e.getMessage());
		}
	}

	/** All WMO condition labels — used to populate the AddTour dropdown. */
	public List<String> conditionOptions() {
		return List.of(
				"Clear sky", "Partly cloudy", "Fog", "Drizzle", "Freezing drizzle",
				"Rain", "Freezing rain", "Snow", "Snow grains", "Rain showers",
				"Snow showers", "Thunderstorm", "Thunderstorm with hail");
	}

	private boolean isEnabled() {
		return settingRepo.findById("weather.enabled")
				.map(s -> !"false".equalsIgnoreCase(s.getValue()))
				.orElse(true);
	}

	/**
	 * Returns the index of the time entry closest to {@code target}, or -1 if none.
	 */
	private int nearestHourIndex(JsonNode times, Instant target) {
		int best = -1;
		long bestDiff = Long.MAX_VALUE;
		for (int i = 0; i < times.size(); i++) {
			String iso = times.get(i).asText();
			try {
				Instant t = Instant.parse(iso + ":00Z");
				long diff = Math.abs(ChronoUnit.SECONDS.between(t, target));
				if (diff < bestDiff) {
					bestDiff = diff;
					best = i;
				}
			} catch (Exception ignored) {
			}
		}
		return best;
	}

	private Double doubleAt(JsonNode hourly, String field, int idx) {
		JsonNode arr = hourly.path(field);
		if (!arr.isArray() || idx >= arr.size() || arr.get(idx).isNull())
			return null;
		return arr.get(idx).asDouble();
	}

	private Integer intAt(JsonNode hourly, String field, int idx) {
		JsonNode arr = hourly.path(field);
		if (!arr.isArray() || idx >= arr.size() || arr.get(idx).isNull())
			return null;
		return arr.get(idx).asInt();
	}

	// ── Rain events along the ride ────────────────────────────────────────────
	// How many points along the track to probe the weather API for. The hour+~1km
	// cache collapses most of these onto a handful of real calls; keeping it modest
	// avoids hammering the free endpoint for a nicety.
	private static final int RAIN_SAMPLES = 12;
	// At most this many rain events per ride — "one or two are nice", not a log of
	// every drizzle minute.
	private static final int MAX_RAIN_EVENTS = 2;

	/**
	 * Did this WMO code mean rain/showers/thunder (anything wet enough to notice)?
	 */
	private static boolean isRain(Integer code) {
		if (code == null)
			return false;
		return (code >= 51 && code <= 67) // drizzle + rain (incl. freezing)
				|| (code >= 80 && code <= 82) // rain showers
				|| code >= 95; // thunderstorm
	}

	/**
	 * Probe the weather along the track and return up to {@link #MAX_RAIN_EVENTS}
	 * "a shower hit you" events — each the START of a wet stretch (dry→rain, or
	 * rain from the off). Point-in-space/time so the replay map can pin them.
	 */
	public List<RideEventDto> detectRainEvents(List<TrackPoint> pts) {
		List<RideEventDto> out = new ArrayList<>();
		if (!isEnabled() || pts == null || pts.size() < 2)
			return out;
		int n = pts.size();
		int samples = Math.min(RAIN_SAMPLES, n);
		boolean prevRain = false;
		for (int s = 0; s < samples; s++) {
			int i = (int) ((long) s * (n - 1) / (samples - 1));
			TrackPoint p = pts.get(i);
			if (p.time() == null)
				continue;
			WeatherResult w = fetchWeather(p.lat(), p.lon(), p.time());
			boolean rain = isRain(w.wmoCode());
			if (rain && !prevRain) { // onset: the shower starts here
				String label = w.condition() != null ? w.condition() : "Rain shower";
				out.add(new RideEventDto(RideEventType.WEATHER_RAIN, label, p.lat(), p.lon(), p.time()));
				if (out.size() >= MAX_RAIN_EVENTS)
					break;
			}
			prevRain = rain;
		}
		return out;
	}

	/** Compact WMO weather-code → human label. Open-Meteo uses this code set. */
	private String decodeWmoCode(Integer code) {
		if (code == null)
			return null;
		return switch (code) {
			case 0 -> "Clear sky";
			case 1, 2, 3 -> "Partly cloudy";
			case 45, 48 -> "Fog";
			case 51, 53, 55 -> "Drizzle";
			case 56, 57 -> "Freezing drizzle";
			case 61, 63, 65 -> "Rain";
			case 66, 67 -> "Freezing rain";
			case 71, 73, 75 -> "Snow";
			case 77 -> "Snow grains";
			case 80, 81, 82 -> "Rain showers";
			case 85, 86 -> "Snow showers";
			case 95 -> "Thunderstorm";
			case 96, 99 -> "Thunderstorm with hail";
			default -> "Unknown";
		};
	}
}
