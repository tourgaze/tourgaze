/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

/**
 * A user-managed ride-event type. {@code key} is the stable value stored on a
 * ride event ({@code RideEvent.type}); {@code name}/{@code icon}/{@code color}
 * drive display. Seeded with the built-in kinds (WEATHER_RAIN, …); editable in
 * Settings.
 */
public record EventTypeDto(
        String id,
        String key,
        String name,
        String icon,
        String color,
        int ordinal,
        boolean enabled,
        /** System kind the app relies on (e.g. WEATHER_RAIN) — can't be deleted. */
        boolean builtin
) {}
