/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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
        Instant createdAt,
        Instant retiredAt
) {
}
