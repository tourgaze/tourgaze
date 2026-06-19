/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.tourgaze.parser.TrackPoint;

class RouteSimilarityServiceTest {

	private static TrackPoint p(double lat, double lon) {
		return new TrackPoint(null, lat, lon, null, null, null);
	}

	@Test
	void fingerprintIsDeterministicAndDeduped() {
		List<TrackPoint> pts = List.of(p(48.137, 11.575), p(48.137, 11.575), p(48.140, 11.580));
		String fp = RouteSimilarityService.fingerprint(pts);
		// Two identical adjacent points → one cell, so at most 2 distinct cells.
		assertThat(fp.split(" ")).hasSizeLessThanOrEqualTo(2);
		assertThat(RouteSimilarityService.fingerprint(pts)).isEqualTo(fp);
	}

	@Test
	void identicalRoutesShareEveryCell() {
		List<TrackPoint> a = List.of(p(48.10, 11.50), p(48.20, 11.60), p(48.30, 11.70));
		assertThat(RouteSimilarityService.fingerprint(a))
				.isEqualTo(RouteSimilarityService.fingerprint(a));
	}
}
