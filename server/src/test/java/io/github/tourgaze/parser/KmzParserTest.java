/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class KmzParserTest {

	private static final String KML = """
			<?xml version="1.0" encoding="UTF-8"?>
			<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
			 <Document><Placemark><gx:MultiTrack><gx:Track>
			  <when>2024-05-01T07:00:00Z</when><when>2024-05-01T07:00:10Z</when><when>2024-05-01T07:00:20Z</when>
			  <gx:coord>11.5000 47.5000 600.0</gx:coord>
			  <gx:coord>11.5010 47.5010 605.0</gx:coord>
			  <gx:coord>11.5020 47.5020 610.0</gx:coord>
			  <ExtendedData><SchemaData>
			   <gx:SimpleArrayData name="heartrate">
			    <gx:value>120</gx:value><gx:value>130</gx:value><gx:value>140</gx:value>
			   </gx:SimpleArrayData>
			  </SchemaData></ExtendedData>
			 </gx:Track></gx:MultiTrack></Placemark></Document>
			</kml>
			""";

	private static byte[] kmz() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("doc.kml"));
			zip.write(KML.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return out.toByteArray();
	}

	@Test
	void parsesOpenTracksGxTrackWithCoordsAndHeartRate() throws Exception {
		var r = new KmzParser().parse(kmz());
		assertThat(r.points()).hasSize(3);
		// coords are "lon lat ele" → first point lat 47.5, lon 11.5
		assertThat(r.points().get(0).lat()).isEqualTo(47.5000);
		assertThat(r.points().get(0).lon()).isEqualTo(11.5000);
		assertThat(r.points().get(0).hr()).isEqualTo(120);
		assertThat(r.maxHr()).isEqualTo(140);
		assertThat(r.distanceM()).isNotNull().isGreaterThan(0);
	}

	@Test
	void parsesBareKmlLineStringFallback() {
		String line = """
				<?xml version="1.0"?>
				<kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark><LineString>
				<coordinates>11.50,47.50,600 11.51,47.51,610</coordinates>
				</LineString></Placemark></Document></kml>
				""";
		var r = new KmzParser().parse(line.getBytes(StandardCharsets.UTF_8));
		assertThat(r.points()).hasSize(2);
		assertThat(r.points().get(0).lat()).isEqualTo(47.50);
	}
}
