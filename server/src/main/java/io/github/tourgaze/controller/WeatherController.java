/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.tourgaze.service.WeatherService;

/**
 * Used by the AddTour modal to populate the weather section from the
 * detected start-coords + start-time before the user confirms the import.
 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

	private final WeatherService weatherService;

	public WeatherController(WeatherService weatherService) {
		this.weatherService = weatherService;
	}

	@GetMapping
	public WeatherService.WeatherResult lookup(@RequestParam("lat") double lat,
			@RequestParam("lon") double lon,
			@RequestParam("time") String iso) {
		return weatherService.fetchWeather(lat, lon, Instant.parse(iso));
	}

	@GetMapping("/conditions")
	public List<String> conditionOptions() {
		return weatherService.conditionOptions();
	}
}
