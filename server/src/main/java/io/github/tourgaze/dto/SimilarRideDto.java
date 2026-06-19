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
 * A ride that looks like the same route as a reference ride — surfaced in the
 * ghost-chase compare picker. {@code score} is the GPS route overlap (Jaccard,
 * 0–1; tag-only matches get a small base score); {@code matchType} is
 * "gps" / "tag" / "both".
 */
public record SimilarRideDto(
        String id,
        String name,
        String activityType,
        Instant startTime,
        Double distanceKm,
        Integer durationS,
        String startLocation,
        @Schema(description = "Route overlap (Jaccard 0–1); tag-only matches get a base score.")
        double score,
        @Schema(description = "gps | tag | both") String matchType
) {}
