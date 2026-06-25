/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.util;

/**
 * Cheap magic-byte sniff for the raster tile formats we proxy (PNG/JPEG/GIF/
 * WEBP). Used to reject non-image upstream responses — tile servers, CDNs and
 * captive portals routinely answer HTTP 200 with an HTML error page or empty
 * body, and caching that as a {@code .png} would poison the immutable tile
 * cache forever.
 */
public final class ImageSniff {

	private ImageSniff() {
	}

	/** True if the bytes start with a known image signature. */
	public static boolean isImage(byte[] b) {
		if (b == null || b.length < 4)
			return false;
		// PNG: 89 50 4E 47
		if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
			return true;
		// JPEG: FF D8
		if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8)
			return true;
		// GIF: "GIF"
		if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F')
			return true;
		// WEBP: "RIFF"...."WEBP"
		if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
				&& b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
			return true;
		return false;
	}
}
