/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import io.github.tourgaze.dto.RideEventDto;
import io.github.tourgaze.repository.ActivityRepository;

/**
 * Proves the free-form {@code attributes} map round-trips through the native
 * {@code json} column on the H2-in-PostgreSQL-mode DB (same engine/mode the app
 * runs on). Guards the {@code @JdbcTypeCode(JSON)} mapping: a real flush + a
 * cleared-session reload, so the JDBC bind/extract is actually exercised.
 */
@SpringBootTest
@Transactional
class ActivityAttributesJsonTest {

	@Autowired
	ActivityRepository repo;

	@Autowired
	EntityManager em;

	@Test
	void attributesRoundTripThroughJsonColumn() {
		Activity a = new Activity();
		a.setSourceHash("json-test-" + System.nanoTime());
		a.setSourceFilename("x.fit");
		a.setSourceFormat("fit");

		Map<String, Object> attrs = new HashMap<>();
		attrs.put("weather", "rainshower");
		attrs.put("note", "felt great");
		attrs.put(RideEventDto.ATTRIBUTES_KEY, List.of(Map.of(
				"type", "WEATHER_RAIN",
				"label", "Rain showers",
				"lat", 47.123,
				"lon", 11.456,
				"time", "2026-06-22T10:00:00Z")));
		a.setAttributes(attrs);

		String id = repo.saveAndFlush(a).getId();
		em.clear(); // drop the 1st-level cache so findById re-reads the json column

		Activity reloaded = repo.findById(id).orElseThrow();
		assertThat(reloaded.getAttributes())
				.containsEntry("weather", "rainshower")
				.containsEntry("note", "felt great")
				.containsKey(RideEventDto.ATTRIBUTES_KEY);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> events = (List<Map<String, Object>>) reloaded.getAttributes()
				.get(RideEventDto.ATTRIBUTES_KEY);
		assertThat(events).hasSize(1);
		assertThat(events.get(0))
				.containsEntry("type", "WEATHER_RAIN")
				.containsEntry("time", "2026-06-22T10:00:00Z");
	}
}
