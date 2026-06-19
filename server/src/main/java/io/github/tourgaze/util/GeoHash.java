/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.util;

/**
 * Minimal geohash encoder (standard base-32 algorithm). Precision 7 ≈ a
 * 153 m × 153 m cell — the right granularity for "did these two rides go down
 * the same roads" route matching. Dependency-free.
 */
public final class GeoHash {

	private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();

	private GeoHash() {
	}

	public static String encode(double lat, double lon, int precision) {
		double latMin = -90, latMax = 90, lonMin = -180, lonMax = 180;
		StringBuilder gh = new StringBuilder(precision);
		boolean even = true;
		int bit = 0, ch = 0;
		while (gh.length() < precision) {
			if (even) {
				double mid = (lonMin + lonMax) / 2;
				if (lon >= mid) {
					ch = (ch << 1) | 1;
					lonMin = mid;
				} else {
					ch <<= 1;
					lonMax = mid;
				}
			} else {
				double mid = (latMin + latMax) / 2;
				if (lat >= mid) {
					ch = (ch << 1) | 1;
					latMin = mid;
				} else {
					ch <<= 1;
					latMax = mid;
				}
			}
			even = !even;
			if (++bit == 5) {
				gh.append(BASE32[ch]);
				bit = 0;
				ch = 0;
			}
		}
		return gh.toString();
	}

	/**
	 * Bounding box of a geohash cell: {@code [minLat, minLon, maxLat, maxLon]}.
	 * Inverse of {@link #encode} — turns a coverage cell back into the area to
	 * query for peaks/passes.
	 */
	public static double[] decodeBbox(String hash) {
		double latMin = -90, latMax = 90, lonMin = -180, lonMax = 180;
		boolean even = true;
		for (int i = 0; i < hash.length(); i++) {
			int cd = indexOf(hash.charAt(i));
			for (int mask = 16; mask != 0; mask >>= 1) {
				if (even) {
					double mid = (lonMin + lonMax) / 2;
					if ((cd & mask) != 0)
						lonMin = mid;
					else
						lonMax = mid;
				} else {
					double mid = (latMin + latMax) / 2;
					if ((cd & mask) != 0)
						latMin = mid;
					else
						latMax = mid;
				}
				even = !even;
			}
		}
		return new double[] { latMin, lonMin, latMax, lonMax };
	}

	private static int indexOf(char c) {
		for (int i = 0; i < BASE32.length; i++)
			if (BASE32[i] == c)
				return i;
		throw new IllegalArgumentException("not a geohash char: " + c);
	}
}
