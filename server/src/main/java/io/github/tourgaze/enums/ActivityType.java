/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Sport / activity type of a ride. The values mirror what the FIT parser
 * decodes from the Garmin {@code Sport} message; GPX/KML carry no reliable
 * sport, so those rides have a null type until the user sets one.
 *
 * The lowercase {@link #wire()} is the value stored in the DB
 * {@code activity_type} column and emitted in JSON, so existing rows and
 * exported sidecars map unchanged. Parsing is lenient: any unrecognised value
 * collapses to {@link #OTHER} rather than failing, so a future FIT sport or a
 * hand-edited file never breaks deserialization. {@code @Schema(enumAsRef)}
 * publishes it as a named OpenAPI schema → the generated frontend type is a
 * {@code 'cycling' | 'running' | …} union.
 */
@Schema(name = "ActivityType", enumAsRef = true, description = "Sport / activity type of a ride. Unknown or unsupported sports map to 'other'.")
public enum ActivityType {
	CYCLING("cycling"), RUNNING("running"), WALKING("walking"), HIKING("hiking"), SWIMMING("swimming"), GENERIC(
			"generic"), OTHER("other");

	private final String wire;

	ActivityType(String wire) {
		this.wire = wire;
	}

	/**
	 * Lowercase wire value used in JSON and the DB column, e.g. {@code "cycling"}.
	 */
	@JsonValue
	public String wire() {
		return wire;
	}

	/** Lenient parse: case-insensitive, unknown → OTHER, null/blank → null. */
	@JsonCreator
	public static ActivityType from(String value) {
		if (value == null || value.isBlank())
			return null;
		String v = value.trim();
		for (ActivityType t : values())
			if (t.wire.equalsIgnoreCase(v))
				return t;
		return OTHER;
	}
}
