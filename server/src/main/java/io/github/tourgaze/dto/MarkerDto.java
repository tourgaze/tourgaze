/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wire shape for a map marker. On create/update {@code id} and {@code createdAt}
 * are ignored (server-assigned); {@code activityId} null marks a general marker.
 */
public record MarkerDto(
        String id,
        @Schema(description = "Ride this marker belongs to, or null for a general (always-shown) marker.", nullable = true)
        String activityId,
        double lat,
        double lon,
        String label,
        @Schema(nullable = true) String description,
        @Schema(description = "Icon category: food, peak, viewpoint, water, repair, star, …", example = "food")
        String category,
        Instant createdAt
) {}
