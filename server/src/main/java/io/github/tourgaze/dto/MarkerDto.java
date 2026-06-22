/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wire shape for a global map marker (a place, not a ride annotation). On
 * create/update {@code id} and {@code createdAt} are ignored (server-assigned).
 */
public record MarkerDto(
        String id,
        double lat,
        double lon,
        String label,
        @Schema(nullable = true) String description,
        @Schema(description = "Icon category: food, peak, viewpoint, water, repair, star, …", example = "food")
        String category,
        Instant createdAt
) {}
