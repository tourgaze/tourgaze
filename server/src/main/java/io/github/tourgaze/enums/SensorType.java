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
 * A data channel / sensor a ride can carry, recorded in {@code activity
 * attributes.sensors}. Published as a named OpenAPI schema (api-first) so the
 * frontend gets the constants as a generated type instead of magic strings.
 *
 * <p>
 * Wire values are stable identifiers — never rename one once shipped (it's
 * persisted in the ride sidecars). Add new ones (RADAR, BAROMETER, …) as the
 * parser learns to detect them.
 */
@Schema(name = "SensorType", enumAsRef = true, description = "A data channel / sensor a ride carries.")
public enum SensorType {

	HEART_RATE("hr"), CADENCE("cadence"), POWER("power"), SPEED("speed"), ALTITUDE("altitude"), TEMPERATURE(
			"temperature"), BAROMETER("barometer"), RADAR("radar"), GPS("gps");

	private final String wire;

	SensorType(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	/** Parse a wire value back to the enum, or null if unknown (forward-compat). */
	@JsonCreator
	public static SensorType fromWire(String wire) {
		if (wire == null)
			return null;
		for (SensorType s : values())
			if (s.wire.equals(wire))
				return s;
		return null;
	}
}
