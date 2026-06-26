/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

/**
 * Estimated energy expenditure (kcal) for a ride, using the best available
 * signal:
 *
 * <ol>
 * <li><b>Power meter (cycling)</b> — mechanical work {@code W = avgPower x
 * movingTime}. Metabolic energy = W / efficiency(~0.24); in kcal that lands at
 * almost exactly {@code kJ_mechanical}, the well-known "1 kJ ~ 1 kcal" rule for
 * cycling. Elevation is already folded in (climbing costs power).</li>
 * <li><b>Heart rate</b> — the Keytel et al. (2005) regression on HR + body
 * mass + age + sex. Effort from climbing is captured implicitly (you HR up on a
 * climb), so this is the elevation-aware fallback when there's no power.</li>
 * <li>Otherwise {@code null} — not enough to estimate honestly.</li>
 * </ol>
 */
public final class CalorieEstimator {

	private CalorieEstimator() {
	}

	/** kcal, or null when neither power nor heart rate (+ profile) is available. */
	public static Integer estimate(String activityType, Integer avgPowerW, Integer avgHr,
			Integer movingTimeS, Integer durationS, Double weightKg, Integer ageYears, String gender) {
		Integer durS = (movingTimeS != null && movingTimeS > 0) ? movingTimeS : durationS;
		if (durS == null || durS <= 0)
			return null;

		// 1) Power meter on a bike: mechanical kJ ~ metabolic kcal.
		if ("cycling".equals(activityType) && avgPowerW != null && avgPowerW > 0) {
			return (int) Math.round(avgPowerW * (durS / 1000.0));
		}

		// 2) Heart-rate (Keytel 2005). Needs HR + body mass + age + sex.
		if (avgHr != null && avgHr > 0 && weightKg != null && weightKg > 0 && ageYears != null) {
			double minutes = durS / 60.0;
			boolean female = "female".equalsIgnoreCase(gender);
			double kcalPerMin = female
					? (-20.4022 + 0.4472 * avgHr - 0.1263 * weightKg + 0.074 * ageYears) / 4.184
					: (-55.0969 + 0.6309 * avgHr + 0.1988 * weightKg + 0.2017 * ageYears) / 4.184;
			double kcal = kcalPerMin * minutes;
			return kcal > 0 ? (int) Math.round(kcal) : null;
		}

		return null;
	}
}
