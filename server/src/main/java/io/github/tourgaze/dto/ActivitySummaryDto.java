/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;
import java.util.List;

import io.github.tourgaze.enums.ActivityType;
import io.github.tourgaze.parser.SourceFormat;

public record ActivitySummaryDto(
        String id,
        String name,
        ActivityType activityType,
        String description,
        Instant startTime,
        Instant endTime,
        Integer durationS,
        Integer movingTimeS,
        Double distanceKm,
        Double elevationGainM,
        Integer avgHr,
        Integer maxHr,
        Double avgSpeedKmh,
        Double maxSpeedKmh,
        Double weatherTempC,
        Integer weatherHumidityPct,
        Double weatherWindKph,
        String weatherCondition,
        Double weightKg,
        String startLocation,
        String startCountry,
        String endLocation,
        String endCountry,
        Instant importedAt,
        // Source-file provenance (surfaced read-only on the EditTour panel so
        // the user can see which FIT this activity came from and where it
        // lives on disk).
        String sourceFilename,
        String originalFilename,
        SourceFormat sourceFormat,
        String sourceHash,
        Double startLat,
        Double startLon,
        List<String> tagIds,
        String gearId,
        String gearName
) {}
