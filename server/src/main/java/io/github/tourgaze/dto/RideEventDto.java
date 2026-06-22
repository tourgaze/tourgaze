/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;

import io.github.tourgaze.domain.RideEventType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One notable event along a ride — a typed entry in the activity's free-form
 * {@code attributes} map (stored as a list under {@link #ATTRIBUTES_KEY}). Carries
 * what happened ({@link #type}), where ({@link #lat}/{@link #lon}) and when
 * ({@link #time}) so the frontend can pin it on the replay map.
 */
@Schema(name = "RideEvent", description = "A notable point-in-time event along a ride (e.g. a rain shower).")
public record RideEventDto(
		@Schema(description = "What happened.") RideEventType type,
		@Schema(description = "Short human label, e.g. 'Rain shower'.") String label,
		@Schema(description = "Latitude where it occurred.") Double lat,
		@Schema(description = "Longitude where it occurred.") Double lon,
		@Schema(description = "When it occurred (UTC).") Instant time) {

	/** The key under an activity's {@code attributes} map holding the event list. */
	public static final String ATTRIBUTES_KEY = "events";
}
