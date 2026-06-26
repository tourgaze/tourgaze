/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.tourgaze.parser.SourceFormat;

public record ActivitySummaryDto(
        String id,
        String name,
        // Sport key (see Sport masterdata, e.g. "cycling").
        String activityType,
        // Device sub-sport (FIT) captured for future use, e.g. "road", "mountain".
        String subSport,
        String description,
        Instant startTime,
        Instant endTime,
        Integer durationS,
        Integer movingTimeS,
        Double distanceKm,
        Double elevationGainM,
        Double elevationLossM,
        Integer avgHr,
        Integer maxHr,
        Double avgSpeedKmh,
        Double maxSpeedKmh,
        Integer avgCadence,
        Integer maxCadence,
        Integer avgPowerW,
        Integer maxPowerW,
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
        String gearName,
        // Owner of the ride (display name, or username; null if unassigned). Lets
        // the UI tell whose ride this is — relevant once compare spans riders.
        String riderName,
        // Free-form user annotations (weather, coordinates, notes, …) as JSON.
        Map<String, Object> attributes,
        // Typed view of the notable events stored in attributes (rain showers, …),
        // ready for the replay map to pin. Derived from attributes — read-only.
        List<RideEventDto> events
) {}
