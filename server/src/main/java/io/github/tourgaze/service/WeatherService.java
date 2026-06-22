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
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.tourgaze.entity.Activity;
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

	@Async
	@Transactional
	public void fetchAndStoreAsync(String activityId) {
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
			activityRepo.save(a);
			events.publishEvent(new io.github.tourgaze.event.ActivityEvents.Changed(activityId));
			log.info("Weather populated for activity {} ({}°C, {})", activityId, w.tempC(), w.condition());
		} catch (Exception e) {
			log.warn("Weather fetch errored for {}: {}", activityId, e.getMessage());
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
