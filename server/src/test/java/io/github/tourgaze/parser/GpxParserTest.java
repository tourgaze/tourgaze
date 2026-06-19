/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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
}
