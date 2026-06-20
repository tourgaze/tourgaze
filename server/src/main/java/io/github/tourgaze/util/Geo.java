/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.util;

import net.sf.geographiclib.Geodesic;

/**
 * Geographic distance, via GeographicLib (Karney's geodesic on the WGS-84
 * ellipsoid) — replaces the hand-rolled haversine copies scattered across the
 * parsers and services. More accurate than the spherical approximation and one
 * call instead of ten lines of trig.
 */
public final class Geo {

	private Geo() {
	}

	/** Great-circle (geodesic) distance between two lat/lon points, in metres. */
	public static double distanceM(double aLat, double aLon, double bLat, double bLon) {
		return Geodesic.WGS84.Inverse(aLat, aLon, bLat, bLon).s12;
	}

	/** Same, in kilometres. */
	public static double distanceKm(double aLat, double aLon, double bLat, double bLon) {
		return distanceM(aLat, aLon, bLat, bLon) / 1000.0;
	}
}
