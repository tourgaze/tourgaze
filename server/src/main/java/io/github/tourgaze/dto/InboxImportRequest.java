/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;
import java.util.List;

/** User-supplied overrides applied at the moment of import-from-inbox. */
public record InboxImportRequest(
        String name,
        String description,
        String gearId,
        String userId,
        List<String> tagIds,
        // Tag NAMES to find-or-create at import (e.g. accepted region/country
        // proposals that don't exist as tags yet). Applied alongside tagIds.
        List<String> tagNames,
        Double weatherTempC,
        Integer weatherHumidityPct,
        Double weatherWindKph,
        String weatherCondition,
        Double weightKg,
        String startLocation,
        String startCountry,
        String endLocation,
        String endCountry,
        // Sport override (wire value, e.g. "hiking"). Null → keep the auto-detected
        // type. Lets the user correct the detected sport in the import dropdown.
        String activityType,
        // Start-time override. Null → keep the parsed time. Garmin/devices sometimes
        // record a wrong start (epoch/clock not set), so the import form lets the
        // user correct it; end time shifts to keep the same duration.
        Instant startTime
) {}
