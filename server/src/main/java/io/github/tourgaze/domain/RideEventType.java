/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.domain;

/**
 * Well-known {@link io.github.tourgaze.dto.RideEventDto#type()} values. The
 * field
 * is deliberately an OPEN string, not an enum: the system emits the constants
 * here (e.g. {@link #WEATHER_RAIN} on import), but a user can annotate a ride
 * with any event kind of their own — {@code DRINK_BREAK}, {@code PUNCTURE},
 * {@code VIEWPOINT}, … — without a code or schema change. These constants just
 * keep the built-in kinds spelled consistently.
 *
 * Convention: {@code WEATHER_*} for weather-derived events,
 * {@code SCREAMING_SNAKE}
 * for everything else.
 */
public final class RideEventType {

	private RideEventType() {
	}

	/** A rain shower occurred at this point/time of the ride (auto-detected). */
	public static final String WEATHER_RAIN = "WEATHER_RAIN";

	/** Rider stopped for a drink/snack — example of a user-defined kind. */
	public static final String DRINK_BREAK = "DRINK_BREAK";
}
