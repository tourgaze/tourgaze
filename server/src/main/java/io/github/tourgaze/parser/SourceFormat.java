/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The ride-file formats TourGaze can import. Lives alongside the
 * {@link TrackFileParser} providers because the set of formats and the set of
 * parsers are the same concern — adding a format means adding a provider and a
 * constant here.
 *
 * The lowercase {@link #ext()} is the wire value: it's what the JSON API emits,
 * what the inbox accept-list checks, and what's stored in the DB
 * {@code source_format} column — so JSON round-trips and existing rows map
 * unchanged. {@code @Schema(enumAsRef)} publishes it as a named OpenAPI schema,
 * so the generated frontend type is a {@code 'fit' | 'gpx' | …} union.
 */
@Schema(name = "SourceFormat", enumAsRef = true, description = "Supported ride-file formats for import.")
public enum SourceFormat {
	FIT("fit"), GPX("gpx"), TCX("tcx"), KMZ("kmz"), KML("kml");

	private final String ext;

	SourceFormat(String ext) {
		this.ext = ext;
	}

	/** Lowercase file extension / wire value, e.g. {@code "fit"}. */
	@JsonValue
	public String ext() {
		return ext;
	}

	/** Lenient parse from an extension (case-insensitive); unknown/null → null. */
	@JsonCreator
	public static SourceFormat from(String value) {
		if (value == null)
			return null;
		String v = value.trim().toLowerCase();
		for (SourceFormat f : values())
			if (f.ext.equals(v))
				return f;
		return null;
	}

	/** All accepted extensions, e.g. {@code [fit, gpx, tcx, kmz, kml]}. */
	public static List<String> extensions() {
		return Arrays.stream(values()).map(SourceFormat::ext).toList();
	}
}
