/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeoHashTest {

	@Test
	void encodesTheClassicReferencePoint() {
		// geohash(57.64911, 10.40744) = "u4pruydqqvj"; precision-7 prefix:
		assertThat(GeoHash.encode(57.64911, 10.40744, 7)).isEqualTo("u4pruyd");
	}

	@Test
	void isDeterministicAndCorrectLength() {
		assertThat(GeoHash.encode(48.137, 11.575, 7)).hasSize(7)
				.isEqualTo(GeoHash.encode(48.137, 11.575, 7));
	}

	@Test
	void nearbyPointsShareAPrefix() {
		// ~50 m apart → same precision-7 (~150 m) cell.
		assertThat(GeoHash.encode(48.1370, 11.5750, 7))
				.isEqualTo(GeoHash.encode(48.1373, 11.5752, 7));
	}
}
