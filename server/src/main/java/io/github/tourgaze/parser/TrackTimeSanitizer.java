/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Repairs GPS "pre-lock" clock garbage in a freshly parsed track.
 *
 * Many devices stamp the first few standstill samples with a sentinel clock
 * (often ~1999/2000, or the firmware build epoch) before the GPS fix arrives.
 * Once it locks, the clock jumps forward — by years — to the real time and the
 * ride begins. The raw parse then reports a start time decades before the end
 * and a multi-year "duration" (e.g. session.start_time = 1999 while the records
 * run in 2026).
 *
 * Detection targets exactly that artefact and nothing else: a leading (or
 * trailing) run of points that is a small fraction of the whole and is separated
 * from the bulk by an implausibly large ({@code > JUMP_THRESHOLD}) time jump.
 * That small side is retimed to sit immediately beside the real ride, preserving
 * its internal spacing. Normal files — including legitimate multi-day tours,
 * which record continuously and so never contain a 30-day gap between
 * consecutive samples — are returned untouched.
 *
 * A garbage summary start time (FIT {@code session.start_time}) that sits far
 * before the first real point is also repaired, even when the points themselves
 * are already clean.
 *
 * Applied once, centrally, in {@link TrackParser} so import, the track cache and
 * export all see the same corrected times.
 */
public final class TrackTimeSanitizer {

	private TrackTimeSanitizer() {
	}

	/**
	 * A gap larger than this between two consecutive samples cannot be a real ride
	 * gap — a continuously recording device never pauses 30 days mid-file, whereas
	 * pre-lock garbage is years off. Comfortably above any real activity, far below
	 * the artefact.
	 */
	static final Duration JUMP_THRESHOLD = Duration.ofDays(30);

	// The garbage cluster is always the SMALLER side of the jump and must fit
	// within this cap, so a >30-day jump that splits the file roughly in half (not
	// the pre-lock signature) is left alone. The jump itself is the real guard;
	// this just confirms the cluster is a brief warm-up, not half the ride.
	private static final double CLUSTER_MAX_FRACTION = 0.10;
	private static final int CLUSTER_MIN_POINTS = 30;
	private static final int CLUSTER_MAX_POINTS = 200;

	/**
	 * Return {@code r} with any pre-lock clock garbage repaired, or {@code r}
	 * unchanged (same instance) when nothing qualifies.
	 */
	public static ParseResult sanitize(ParseResult r) {
		if (r == null)
			return r;

		List<TrackPoint> fixed = retimeClockGarbage(r.points());
		boolean pointsChanged = fixed != r.points();

		Instant firstT = firstTime(fixed);
		Instant lastT = lastTime(fixed);

		// FIT-style: the summary start sits far BEFORE the first real point even
		// though the points are clean → the summary clock itself is the garbage.
		boolean startGarbage = r.startTime() != null && firstT != null
				&& r.startTime().isBefore(firstT)
				&& Duration.between(r.startTime(), firstT).compareTo(JUMP_THRESHOLD) > 0;

		if (!pointsChanged && !startGarbage)
			return r;

		Instant newStart = firstT != null ? firstT : r.startTime();
		Instant newEnd = lastT != null ? lastT : r.endTime();
		Integer newDuration = (newStart != null && newEnd != null && !newEnd.isBefore(newStart))
				? (int) Duration.between(newStart, newEnd).getSeconds()
				: r.durationS();

		// Moving time can't exceed elapsed; clamp a now-bogus session value.
		Integer newMoving = r.movingTimeS();
		if (newMoving != null && newDuration != null && newMoving > newDuration)
			newMoving = newDuration;

		// Average speed derived from a garbage duration (GPX) or a garbage session
		// clock (FIT) is meaningless; recompute it from distance over the real
		// duration. Max speed is per-segment and unaffected by the clock jump.
		Double newAvgSpeed = (r.distanceM() != null && newDuration != null && newDuration > 0)
				? r.distanceM() / newDuration
				: r.avgSpeedMs();

		return ParseResult.builder()
				.points(fixed)
				.sport(r.sport())
				.distanceM(r.distanceM())
				.ascentM(r.ascentM())
				.descentM(r.descentM())
				.startTime(newStart)
				.endTime(newEnd)
				.durationS(newDuration)
				.movingTimeS(newMoving)
				.avgHr(r.avgHr())
				.maxHr(r.maxHr())
				.avgSpeedMs(newAvgSpeed)
				.maxSpeedMs(r.maxSpeedMs())
				.avgCadence(r.avgCadence())
				.maxCadence(r.maxCadence())
				.avgPowerW(r.avgPowerW())
				.maxPowerW(r.maxPowerW())
				.subSport(r.subSport())
				.build();
	}

	/**
	 * Retime a leading or trailing pre-lock garbage cluster; return the input list
	 * unchanged (same instance) when there is no qualifying jump or neither side of
	 * it is a small cluster.
	 */
	static List<TrackPoint> retimeClockGarbage(List<TrackPoint> pts) {
		int n = pts.size();
		if (n < 3)
			return pts;

		// Original indices of the points that actually carry a timestamp, in order.
		List<Integer> timed = new ArrayList<>();
		for (int i = 0; i < n; i++)
			if (pts.get(i).time() != null)
				timed.add(i);
		if (timed.size() < 3)
			return pts;

		// The single largest jump between consecutive timed samples (either
		// direction — pre-lock garbage may be older OR, rarely, a stray future
		// stamp). Must exceed the threshold to qualify.
		int boundary = -1; // index into `timed` of the sample just BEFORE the jump
		Duration biggest = JUMP_THRESHOLD;
		for (int k = 0; k < timed.size() - 1; k++) {
			Duration gap = Duration.between(pts.get(timed.get(k)).time(), pts.get(timed.get(k + 1)).time()).abs();
			if (gap.compareTo(biggest) > 0) {
				biggest = gap;
				boundary = k;
			}
		}
		if (boundary < 0)
			return pts; // no implausible jump → clean track

		int splitOrigIdx = timed.get(boundary); // last original index on the lead side
		int leadCount = splitOrigIdx + 1;
		int tailCount = n - leadCount;
		int clusterCap = Math.min(CLUSTER_MAX_POINTS, Math.max(CLUSTER_MIN_POINTS, (int) (n * CLUSTER_MAX_FRACTION)));

		if (leadCount <= tailCount && leadCount <= clusterCap) {
			// Leading garbage: shift [0 .. splitOrigIdx] to end one second before the
			// first real point, preserving the cluster's own spacing.
			Instant goodFirst = pts.get(timed.get(boundary + 1)).time();
			Instant garbageLast = pts.get(splitOrigIdx).time();
			Duration shift = Duration.between(garbageLast, goodFirst.minusSeconds(1));
			return shiftTimes(pts, 0, splitOrigIdx, shift);
		}
		if (tailCount < leadCount && tailCount <= clusterCap) {
			// Trailing garbage: shift [splitOrigIdx+1 .. n-1] to begin one second
			// after the last real point.
			Instant goodLast = pts.get(splitOrigIdx).time();
			Instant garbageFirst = pts.get(timed.get(boundary + 1)).time();
			Duration shift = Duration.between(garbageFirst, goodLast.plusSeconds(1));
			return shiftTimes(pts, splitOrigIdx + 1, n - 1, shift);
		}
		return pts; // jump exists but both sides are substantial → treat as legit
	}

	private static List<TrackPoint> shiftTimes(List<TrackPoint> pts, int from, int to, Duration shift) {
		List<TrackPoint> out = new ArrayList<>(pts);
		for (int i = from; i <= to; i++) {
			TrackPoint p = pts.get(i);
			if (p.time() == null)
				continue;
			out.set(i, new TrackPoint(p.time().plus(shift), p.lat(), p.lon(), p.altM(),
					p.hr(), p.speedMs(), p.cadence(), p.power()));
		}
		return out;
	}

	private static Instant firstTime(List<TrackPoint> pts) {
		for (TrackPoint p : pts)
			if (p.time() != null)
				return p.time();
		return null;
	}

	private static Instant lastTime(List<TrackPoint> pts) {
		for (int i = pts.size() - 1; i >= 0; i--)
			if (pts.get(i).time() != null)
				return pts.get(i).time();
		return null;
	}
}
