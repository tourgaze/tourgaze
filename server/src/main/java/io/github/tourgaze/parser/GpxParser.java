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
		double distanceM = 0, ascentM = 0, maxSpeedMs = 0;
		Double prevLat = null, prevLon = null, prevEle = null;
		Instant prevTime = null;

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
				}
				if (prevTime != null && time != null) {
					double dt = (time.toEpochMilli() - prevTime.toEpochMilli()) / 1000.0;
					if (dt > 0)
						maxSpeedMs = Math.max(maxSpeedMs, seg / dt);
				}
			}

			points.add(new TrackPoint(time, lat, lon, ele, null, null));
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

		return new ParseResult(
				points,
				null, // sport — not in GPX
				distanceM > 0 ? distanceM : null,
				ascentM > 0 ? ascentM : null,
				startTime, endTime,
				durationS,
				null, // moving time — not derivable reliably
				null, null, // avg / max HR — not standardised in GPX
				avgSpeedMs,
				maxSpeedMs > 0 ? maxSpeedMs : null);
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
