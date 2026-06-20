/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoTest {

	@Test
	void oneDegreeOfLongitudeAtEquatorIsAbout111Km() {
		// WGS84 equatorial: circumference/360 ≈ 111319 m.
		assertThat(Geo.distanceM(0, 0, 0, 1)).isCloseTo(111319, within(50.0));
	}

	@Test
	void distanceKmIsMetresOverThousand() {
		double m = Geo.distanceM(48.0, 11.0, 48.5, 11.5);
		assertThat(Geo.distanceKm(48.0, 11.0, 48.5, 11.5)).isCloseTo(m / 1000.0, within(1e-9));
	}

	@Test
	void samePointIsZero() {
		assertThat(Geo.distanceM(47.1, 11.2, 47.1, 11.2)).isZero();
	}
}
