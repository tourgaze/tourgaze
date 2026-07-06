/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class GpxParserTest {

	private static final String GPX = """
			<?xml version="1.0" encoding="UTF-8"?>
			<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
			  <trk><trkseg>
			    <trkpt lat="47.5000" lon="11.5000"><ele>600</ele><time>2024-05-01T07:00:00Z</time></trkpt>
			    <trkpt lat="47.5100" lon="11.5100"><ele>650</ele><time>2024-05-01T07:10:00Z</time></trkpt>
			    <trkpt lat="47.5200" lon="11.5200"><ele>700</ele><time>2024-05-01T07:20:00Z</time></trkpt>
			  </trkseg></trk>
			</gpx>
			""";

	@Test
	void parsesPointsDistanceAscentAndTime() {
		var r = new GpxParser().parse(GPX.getBytes(StandardCharsets.UTF_8));
		assertThat(r.points()).hasSize(3);
		assertThat(r.distanceM()).isNotNull().isGreaterThan(0);
		assertThat(r.ascentM()).isNotNull().isGreaterThan(0); // climbing 600→700
		assertThat(r.startTime()).isNotNull();
		assertThat(r.endTime()).isNotNull();
	}

	@Test
	void gradualSubMetreClimbStillAccumulatesAscent() {
		// Regression: dense GPX climbs sub-metre per point. The old per-segment
		// noise gate dropped every such step, reporting ~0 m ascent on long real
		// climbs. Hysteresis must accumulate a steady 0.6 m/point rise (12 m total).
		StringBuilder sb = new StringBuilder(
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
						+ "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>\n");
		double ele = 500.0;
		for (int i = 0; i <= 20; i++) {
			sb.append(String.format(java.util.Locale.ROOT,
					"<trkpt lat=\"%.5f\" lon=\"11.5000\"><ele>%.1f</ele><time>2024-05-01T07:%02d:00Z</time></trkpt>%n",
					47.5000 + i * 0.001, ele, i));
			ele += 0.6; // every step < 1 m → the old gate counted NONE of it
		}
		sb.append("</trkseg></trk></gpx>");

		var r = new GpxParser().parse(sb.toString().getBytes(StandardCharsets.UTF_8));
		// 20 * 0.6 m = 12 m of genuine climb; hysteresis captures the bulk of it.
		assertThat(r.ascentM()).as("gradual sub-metre climb must not be discarded")
				.isNotNull().isGreaterThan(8.0);
	}
}
