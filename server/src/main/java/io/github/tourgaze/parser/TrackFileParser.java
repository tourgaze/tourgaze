/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

/**
 * A ride-file format provider. Each supported format (FIT, GPX, …) is a Spring
 * bean implementing this; {@link TrackParser} discovers them all and dispatches
 * by {@link #supports(String)} — so adding a format is just adding a provider,
 * with no format conditionals anywhere else in the codebase.
 */
public interface TrackFileParser {

	/**
	 * Whether this provider handles the given lowercase format/extension (e.g.
	 * "fit", "gpx").
	 */
	boolean supports(String format);

	/** Parse the raw file bytes into a format-agnostic {@link ParseResult}. */
	ParseResult parse(byte[] data);

	/**
	 * Photos embedded in the file (e.g. KMZ {@code <PhotoOverlay>}s). Most formats
	 * carry none, so the default returns empty — only providers that bundle images
	 * override this.
	 */
	default java.util.List<EmbeddedPhoto> extractPhotos(byte[] data) {
		return java.util.List.of();
	}
}
