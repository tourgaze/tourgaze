/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CalorieEstimatorTest {

	@Test
	void powerMeterCycling_kcalEqualsMechanicalKj() {
		// 200 W for 3600 s = 720 kJ mechanical ≈ 720 kcal (efficiency cancels).
		assertThat(CalorieEstimator.estimate("cycling", 200, 140, 3600, 3600, 75.0, 40, "male"))
				.isEqualTo(720);
	}

	@Test
	void prefersMovingTimeOverElapsedForPower() {
		// 200 W over 1800 s moving (vs 3600 elapsed) = 360 kJ ≈ 360 kcal.
		assertThat(CalorieEstimator.estimate("cycling", 200, 140, 1800, 3600, 75.0, 40, "male"))
				.isEqualTo(360);
	}

	@Test
	void heartRateFallback_whenNoPower_keytel() {
		// Male, HR 150, 75 kg, 40 y, 60 min →
		// (-55.0969 + 0.6309*150 + 0.1988*75 + 0.2017*40)/4.184 ≈ 14.94 kcal/min × 60.
		Integer kcal = CalorieEstimator.estimate("running", null, 150, 3600, 3600, 75.0, 40, "male");
		assertThat(kcal).isNotNull();
		assertThat(kcal).isBetween(850, 950);
	}

	@Test
	void powerOnlyCountsForCycling_elseFallsBackToHr() {
		// A non-cycling sport with power present ignores power → uses HR instead.
		assertThat(CalorieEstimator.estimate("running", 300, 150, 3600, 3600, 75.0, 40, "male"))
				.isBetween(850, 950);
	}

	@Test
	void nullWhenNoUsableSignal() {
		// No power, no HR → can't estimate.
		assertThat(CalorieEstimator.estimate("hiking", null, null, 3600, 3600, 75.0, 40, "male")).isNull();
		// HR present but no body profile → can't run Keytel.
		assertThat(CalorieEstimator.estimate("running", null, 150, 3600, 3600, null, null, "male")).isNull();
		// No duration → null.
		assertThat(CalorieEstimator.estimate("cycling", 200, 140, null, null, 75.0, 40, "male")).isNull();
	}
}
