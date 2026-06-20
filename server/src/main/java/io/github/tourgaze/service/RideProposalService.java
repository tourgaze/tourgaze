/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.enums.ActivityType;
import io.github.tourgaze.repository.ActivityRepository;

/**
 * Sensible import defaults so the AddTour form arrives pre-filled instead of
 * blank. Two cheap heuristics, no over-engineering:
 * - activity type: trust the device sport, else infer from pace, else cycling
 * (the app's primary use);
 * - gear: pick whichever bike/shoes your past rides of similar pace + hilliness
 * used — learned purely from your own history, so fast/flat → your race bike,
 * slow/climby → your MTB, with no hard-coded names.
 */
@Service
public class RideProposalService {

	private final ActivityRepository activityRepo;

	public RideProposalService(ActivityRepository activityRepo) {
		this.activityRepo = activityRepo;
	}

	public ActivityType proposeType(String deviceSport, Double avgSpeedKmh) {
		ActivityType fromDevice = ActivityType.from(deviceSport);
		if (fromDevice != null && fromDevice != ActivityType.GENERIC && fromDevice != ActivityType.OTHER) {
			return fromDevice;
		}
		double v = avgSpeedKmh == null ? 0 : avgSpeedKmh;
		if (v >= 12)
			return ActivityType.CYCLING;
		if (v >= 7)
			return ActivityType.RUNNING;
		if (v > 0)
			return ActivityType.HIKING;
		return ActivityType.CYCLING;
	}

	public record GearProposal(String gearId, String gearName) {
	}

	@Transactional(readOnly = true)
	public GearProposal proposeGear(Double avgSpeedKmh, Double distanceKm, Double elevGainM, ActivityType type) {
		if (avgSpeedKmh == null || distanceKm == null || distanceKm <= 0)
			return null;
		double elevPerKm = (elevGainM != null ? elevGainM : 0) / distanceKm;

		// gearId -> [sumSpeed, sumElevPerKm, count]; prefer same-type history, else
		// any.
		Map<String, double[]> agg = new HashMap<>();
		Map<String, String> names = new HashMap<>();
		aggregate(type == null ? null : type.wire(), agg, names);
		if (agg.isEmpty()) {
			aggregate(null, agg, names);
		}
		if (agg.isEmpty())
			return null;

		String best = null;
		double bestD = Double.MAX_VALUE;
		for (var e : agg.entrySet()) {
			double[] s = e.getValue();
			double gSpeed = s[0] / s[2], gElev = s[1] / s[2];
			// Normalised distance in (pace, hilliness) space — ~10 km/h and ~12 m/km
			// scales.
			double d = Math.pow((avgSpeedKmh - gSpeed) / 10.0, 2) + Math.pow((elevPerKm - gElev) / 12.0, 2);
			if (d < bestD) {
				bestD = d;
				best = e.getKey();
			}
		}
		return best == null ? null : new GearProposal(best, names.get(best));
	}

	private void aggregate(String typeWire, Map<String, double[]> agg, Map<String, String> names) {
		for (Activity a : activityRepo.findAll()) {
			if (a.getGear() == null || a.getAvgSpeedKmh() == null
					|| a.getDistanceKm() == null || a.getDistanceKm() <= 0)
				continue;
			if (typeWire != null && !typeWire.equalsIgnoreCase(a.getActivityType()))
				continue;
			String gid = a.getGear().getId();
			String gname;
			try {
				gname = a.getGear().getName(); // lazy load — may be a dangling ref
			} catch (Exception e) {
				// Gear was deleted without nulling this activity's FK — skip it rather
				// than fail the whole proposal (and the inbox parse that calls it).
				continue;
			}
			names.put(gid, gname);
			double[] cur = agg.computeIfAbsent(gid, k -> new double[3]);
			cur[0] += a.getAvgSpeedKmh();
			cur[1] += (a.getElevationGainM() != null ? a.getElevationGainM() : 0) / a.getDistanceKm();
			cur[2] += 1;
		}
	}
}
