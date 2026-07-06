/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import org.springframework.stereotype.Service;

import io.github.tourgaze.enums.ActivityType;

/**
 * Sensible import defaults so the AddTour form arrives pre-filled instead of
 * blank:
 * - activity type: trust the device sport, else infer from pace, else cycling
 * (the app's primary use);
 * - gear: delegated to {@link GearPredictionService} — the sophisticated
 * successor to this class's old naive centroid, now the single source of truth
 * for gear prediction (sub-sport, per-library fast/slow bands, and a speed/watt
 * profile learned from your trusted history).
 */
@Service
public class RideProposalService {

	private final GearPredictionService gearPrediction;

	public RideProposalService(GearPredictionService gearPrediction) {
		this.gearPrediction = gearPrediction;
	}

	/**
	 * Infer the sport. The device-reported sport wins when it's specific (Garmin
	 * FITs say "cycling" etc.); otherwise — GPX/TCX or generic FITs — fall back to
	 * average moving speed, with distance as a tie-breaker in the run/ride overlap.
	 *
	 * Pace bands (km/h): &lt;6.5 walking / hiking (wandern), ≥14 cycling, and the
	 * ambiguous 6.5–14 band is a run unless it's a long ride (≥30 km ⇒ cycling).
	 * No speed at all → cycling (this is a cyclist's library by default).
	 */
	public ActivityType proposeType(String deviceSport, Double avgSpeedKmh, Double distanceKm) {
		ActivityType fromDevice = ActivityType.from(deviceSport);
		if (fromDevice != null && fromDevice != ActivityType.GENERIC && fromDevice != ActivityType.OTHER) {
			return fromDevice;
		}
		double v = avgSpeedKmh == null ? 0 : avgSpeedKmh;
		double d = distanceKm == null ? 0 : distanceKm;
		if (v <= 0)
			return ActivityType.CYCLING;
		if (v < 6.5)
			return ActivityType.HIKING; // walking / wandern
		if (v >= 14)
			return ActivityType.CYCLING;
		// 6.5–14: fast walk/run vs leisure ride — a long distance tips it to a ride.
		return d >= 30 ? ActivityType.CYCLING : ActivityType.RUNNING;
	}

	public record GearProposal(String gearId, String gearName) {
	}

	/**
	 * Suggest a bike for a freshly-parsed ride — delegates to the unified
	 * predictor.
	 */
	public GearProposal proposeGear(Double avgSpeedKmh, Double distanceKm, Double elevGainM,
			Integer avgPowerW, String subSport, ActivityType type) {
		GearPredictionService.GearGuess g = gearPrediction.proposeGear(
				avgSpeedKmh, distanceKm, elevGainM, avgPowerW, subSport, type);
		return g == null ? null : new GearProposal(g.gearId(), g.gearName());
	}
}
