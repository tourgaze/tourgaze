/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;

/** Gear (bike, shoes, wetsuit, …) a rider can attach to an activity. */
public record GearDto(
        String id,
        String userId,
        String name,
        String type,
        String description,
        String icon,
        boolean assisted,
        /** Gear weight (kg) — added to body weight for the power estimate. */
        Double weightKg,
        Instant createdAt,
        Instant retiredAt
) {
}
