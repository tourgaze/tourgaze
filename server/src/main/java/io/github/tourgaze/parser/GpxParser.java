/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import io.github.tourgaze.util.Geo;
import io.jenetics.jpx.GPX;
import io.jenetics.jpx.WayPoint;

/**
 * GPX parser (Garmin / Strava / Komoot / phone exports) backed by the jpx
 * library. Produces the same {@link ParseResult} shape as the FIT
 * parser so the rest of the pipeline (import, track cache, export) is format-
 * agnostic.
 *
 * GPX carries far less summary metadata than FIT — usually just
 * lat/lon/ele/time
 * per track point — so distance, ascent and speeds are derived from the points.
 * Heart rate (Garmin TrackPointExtension) and "sport" are not standardised in
 * GPX, so they're left null for the user to fill in on the AddTour form.
 */
@Component
public class GpxParser implements TrackFileParser {

	/**
	 * Ignore sub-metre elevation wobble so GPS noise doesn't inflate total ascent.
	 */
	private static final double ASCENT_NOISE_M = 1.0;

	@Override
	public boolean supports(String format) {
		return "gpx".equalsIgnoreCase(format);
	}

	@Override
	public ParseResult parse(byte[] data) {
		if (data == null || data.length < 16) {
			throw new IllegalArgumentException("File too small to be a valid GPX file");
		}

		List<WayPoint> wps = readWayPoints(data);
		if (wps.isEmpty()) {
			throw new IllegalArgumentException("GPX file contains no track or route points");
		}

		List<TrackPoint> points = new ArrayList<>(wps.size());
		double distanceM = 0, ascentM = 0, descentM = 0, maxSpeedMs = 0;
		Double prevLat = null, prevLon = null, prevEle = null;
		Instant prevTime = null;
		// Cadence / power live in each point's <extensions> (Garmin
		// TrackPointExtension <gpxtpx:cad>, power as <power>/PowerInWatts).
		// Accumulate avg + max over the points that recorded them.
		long cadSum = 0, powSum = 0;
		int cadCount = 0, cadMax = 0, powCount = 0, powMax = 0;

		for (WayPoint wp : wps) {
			double lat = wp.getLatitude().doubleValue();
			double lon = wp.getLongitude().doubleValue();
			Double ele = wp.getElevation().map(Number::doubleValue).orElse(null);
			Instant time = wp.getTime().orElse(null);

			if (prevLat != null) {
				double seg = Geo.distanceM(prevLat, prevLon, lat, lon);
				distanceM += seg;
				if (prevEle != null && ele != null) {
					double climb = ele - prevEle;
					if (climb > ASCENT_NOISE_M)
						ascentM += climb;
					else if (climb < -ASCENT_NOISE_M)
						descentM += -climb;
				}
				if (prevTime != null && time != null) {
					double dt = (time.toEpochMilli() - prevTime.toEpochMilli()) / 1000.0;
					if (dt > 0)
						maxSpeedMs = Math.max(maxSpeedMs, seg / dt);
				}
			}

			Integer cad = extInt(wp, "cad", "cadence");
			if (cad != null) {
				cadSum += cad;
				cadCount++;
				cadMax = Math.max(cadMax, cad);
			}
			Integer pow = extInt(wp, "power", "powerinwatts", "watts");
			if (pow != null) {
				powSum += pow;
				powCount++;
				powMax = Math.max(powMax, pow);
			}

			points.add(new TrackPoint(time, lat, lon, ele, null, null, cad, pow));
			prevLat = lat;
			prevLon = lon;
			prevEle = ele;
			prevTime = time;
		}

		Instant startTime = points.get(0).time();
		Instant endTime = points.get(points.size() - 1).time();
		Integer durationS = (startTime != null && endTime != null)
				? (int) (endTime.getEpochSecond() - startTime.getEpochSecond())
				: null;
		Double avgSpeedMs = (durationS != null && durationS > 0) ? distanceM / durationS : null;

		// sport, moving time and HR aren't standardised in plain GPX → left unset
		// (null).
		return ParseResult.builder()
				.points(points)
				.distanceM(distanceM > 0 ? distanceM : null)
				.ascentM(ascentM > 0 ? ascentM : null)
				.descentM(descentM > 0 ? descentM : null)
				.startTime(startTime)
				.endTime(endTime)
				.durationS(durationS)
				.avgSpeedMs(avgSpeedMs)
				.maxSpeedMs(maxSpeedMs > 0 ? maxSpeedMs : null)
				.avgCadence(cadCount > 0 ? (int) Math.round((double) cadSum / cadCount) : null)
				.maxCadence(cadCount > 0 ? cadMax : null)
				.avgPowerW(powCount > 0 ? (int) Math.round((double) powSum / powCount) : null)
				.maxPowerW(powCount > 0 ? powMax : null)
				.build();
	}

	/**
	 * First int value under a waypoint's {@code <extensions>} matching a local
	 * name.
	 */
	private static Integer extInt(WayPoint wp, String... localNames) {
		return wp.getExtensions().map(doc -> findInt(doc.getDocumentElement(), localNames)).orElse(null);
	}

	/**
	 * Depth-first search for the first element whose local name matches, parsed as
	 * int.
	 */
	private static Integer findInt(Node node, String[] names) {
		if (node == null)
			return null;
		NodeList kids = node.getChildNodes();
		for (int i = 0; i < kids.getLength(); i++) {
			Node n = kids.item(i);
			if (n.getNodeType() != Node.ELEMENT_NODE)
				continue;
			String ln = n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
			int colon = ln.indexOf(':');
			if (colon >= 0)
				ln = ln.substring(colon + 1);
			for (String want : names) {
				if (ln.equalsIgnoreCase(want)) {
					String txt = n.getTextContent();
					if (txt != null && !txt.isBlank()) {
						try {
							return (int) Math.round(Double.parseDouble(txt.trim()));
						} catch (NumberFormatException ignored) {
							/* keep scanning */ }
					}
				}
			}
			Integer deep = findInt(n, names);
			if (deep != null)
				return deep;
		}
		return null;
	}

	/**
	 * All track-segment points, falling back to route points if there are no
	 * tracks.
	 */
	private List<WayPoint> readWayPoints(byte[] data) {
		Path tmp = null;
		try {
			tmp = Files.createTempFile("tourgaze-gpx", ".gpx");
			Files.write(tmp, data);
			GPX gpx = GPX.read(tmp);

			List<WayPoint> pts = gpx.tracks()
					.flatMap(io.jenetics.jpx.Track::segments)
					.flatMap(io.jenetics.jpx.TrackSegment::points)
					.toList();
			if (pts.isEmpty()) {
				pts = gpx.routes().flatMap(io.jenetics.jpx.Route::points).toList();
			}
			return pts;
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to parse GPX file: " + e.getMessage(), e);
		} finally {
			if (tmp != null)
				try {
					Files.deleteIfExists(tmp);
				} catch (Exception ignored) {
					/* best-effort */ }
		}
	}
}
