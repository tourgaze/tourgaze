/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The kinds of notable "things that happened" along a ride — point-in-time,
 * point-in-space events stored in an activity's free-form {@code attributes}
 * (under {@link io.github.tourgaze.dto.RideEventDto#ATTRIBUTES_KEY}) and
 * rendered
 * on the replay map. Detected on import (e.g. a rain shower from the weather
 * API)
 * or added by the user. Extend this enum as new event kinds appear.
 */
@Schema(description = "Kind of ride event (e.g. a rain shower that hit during the ride).")
public enum RideEventType {
	/** A rain shower occurred at this point/time of the ride. */
	EVENT_RAIN
}
