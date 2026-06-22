/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

/**
 * A user-managed sport / activity type. {@code key} is the stable value stored on
 * activities ({@code activityType}); {@code name}/{@code icon}/{@code color} drive
 * display. Seeded Garmin-aligned; editable in Settings.
 */
public record SportDto(
        String id,
        String key,
        String name,
        String icon,
        String color,
        int ordinal,
        boolean enabled
) {}
