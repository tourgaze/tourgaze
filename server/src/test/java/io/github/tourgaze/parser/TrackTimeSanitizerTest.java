/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TrackTimeSanitizerTest {

	private static TrackPoint pt(Instant t) {
		return new TrackPoint(t, 49.4, 11.1, 280.0, null, null);
	}

	/** A run of `count` points starting at `start`, one second apart. */
	private static List<TrackPoint> run(Instant start, int count) {
		List<TrackPoint> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
			out.add(pt(start.plusSeconds(i)));
		return out;
	}

	private static ParseResult resultOf(List<TrackPoint> pts) {
		Instant s = pts.get(0).time();
		Instant e = pts.get(pts.size() - 1).time();
		return ParseResult.builder()
				.points(pts)
				.startTime(s)
				.endTime(e)
				.durationS((int) Duration.between(s, e).getSeconds())
				.distanceM(1000.0)
				.build();
	}

	@Test
	void retimesLeadingPreLockGarbageToTheRealRide() {
		// 6 stationary 1999 warm-up points, then 300 real 2026 points.
		Instant real = Instant.parse("2026-06-05T17:32:01Z");
		List<TrackPoint> pts = new ArrayList<>(run(Instant.parse("1999-08-22T00:09:48Z"), 6));
		pts.addAll(run(real, 300));

		var r = TrackTimeSanitizer.sanitize(resultOf(pts));

		assertThat(r.points()).hasSize(306); // nothing dropped
		assertThat(r.startTime()).isAfterOrEqualTo(Instant.parse("2026-06-05T00:00:00Z"));
		assertThat(r.endTime()).isEqualTo(real.plusSeconds(299));
		// 6-point warm-up (5s span) retimed to sit just before the real start.
		assertThat(r.points().get(0).time()).isBetween(real.minusSeconds(30), real);
		assertThat(r.points().get(5).time()).isEqualTo(real.minusSeconds(1));
		assertThat(r.durationS()).isLessThan(1000); // ~5 min warm-up + 299 s ride
	}

	@Test
	void overridesGarbageSummaryStartWhenPointsAreClean() {
		// FIT-style: every record is real 2014, but session.start_time is 1999.
		List<TrackPoint> pts = run(Instant.parse("2014-07-10T05:00:00Z"), 200);
		var raw = ParseResult.builder()
				.points(pts)
				.startTime(Instant.parse("1999-08-21T14:00:47Z")) // garbage session clock
				.endTime(pts.get(pts.size() - 1).time())
				.durationS(1573267)
				.distanceM(20000.0)
				.movingTimeS(1500000)
				.build();

		var r = TrackTimeSanitizer.sanitize(raw);

		assertThat(r.startTime()).isEqualTo(Instant.parse("2014-07-10T05:00:00Z"));
		assertThat(r.durationS()).isEqualTo(199);
		assertThat(r.movingTimeS()).isLessThanOrEqualTo(r.durationS()); // clamped
		assertThat(r.points().get(0).time()).isEqualTo(Instant.parse("2014-07-10T05:00:00Z")); // untouched
	}

	@Test
	void leavesNormalSingleDayRideUntouched() {
		ParseResult raw = resultOf(run(Instant.parse("2024-05-01T07:00:00Z"), 500));
		var r = TrackTimeSanitizer.sanitize(raw);
		assertThat(r).isSameAs(raw); // exact no-op, same instance
	}

	@Test
	void leavesLegitMultiDayTourUntouched() {
		// Continuous 3-day tour: each day ~6h of points, overnight gaps of ~12h —
		// well under the 30-day jump threshold, so nothing is treated as garbage.
		List<TrackPoint> pts = new ArrayList<>();
		Instant t = Instant.parse("2025-06-01T08:00:00Z");
		for (int day = 0; day < 3; day++) {
			pts.addAll(run(t, 400));
			t = t.plus(Duration.ofHours(20)); // next morning
		}
		ParseResult raw = resultOf(pts);
		var r = TrackTimeSanitizer.sanitize(raw);
		assertThat(r).isSameAs(raw);
		assertThat(r.startTime()).isEqualTo(Instant.parse("2025-06-01T08:00:00Z"));
	}

	@Test
	void leavesPointsWithoutTimestampsUntouched() {
		List<TrackPoint> pts = new ArrayList<>();
		for (int i = 0; i < 50; i++)
			pts.add(new TrackPoint(null, 49.4, 11.1, 280.0, null, null));
		var raw = ParseResult.builder().points(pts).build();
		var r = TrackTimeSanitizer.sanitize(raw);
		assertThat(r).isSameAs(raw);
	}
}
