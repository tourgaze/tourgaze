/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.tourgaze.parser.TrackPoint;
import io.github.tourgaze.util.Geo;

/**
 * Per-point speed derivation for GPX/recovered tracks (no device speed field).
 */
class TrackCacheServiceSpeedTest {

	private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

	@Test
	void derivesSpeedFromPositionAndTime() {
		// ~0.001° lat ≈ 111 m; 10 s apart → ~11 m/s.
		TrackPoint a = new TrackPoint(T0, 47.0, 11.0, null, null, null);
		TrackPoint b = new TrackPoint(T0.plusSeconds(10), 47.001, 11.0, null, null, null);
		List<TrackPoint> out = TrackCacheService.withDerivedSpeed(List.of(a, b));

		double expected = Geo.distanceM(47.0, 11.0, 47.001, 11.0) / 10.0;
		assertThat(out.get(0).speedMs()).isNull(); // first point has no predecessor
		assertThat(out.get(1).speedMs()).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.01));
	}

	@Test
	void keepsExistingDeviceSpeed() {
		TrackPoint a = new TrackPoint(T0, 47.0, 11.0, null, null, 5.0);
		TrackPoint b = new TrackPoint(T0.plusSeconds(10), 47.001, 11.0, null, null, 6.0);
		// No nulls → returns the same list untouched.
		assertThat(TrackCacheService.withDerivedSpeed(List.of(a, b))).containsExactly(a, b);
	}

	@Test
	void dropsGpsGlitchSpikes() {
		// 1° jump (~111 km) in 1 s → ~111 km/s, way over the 40 m/s cap → dropped.
		TrackPoint a = new TrackPoint(T0, 47.0, 11.0, null, null, null);
		TrackPoint b = new TrackPoint(T0.plusSeconds(1), 48.0, 11.0, null, null, null);
		assertThat(TrackCacheService.withDerivedSpeed(List.of(a, b)).get(1).speedMs()).isNull();
	}
}
