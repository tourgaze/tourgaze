/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.util.List;

/** User-supplied overrides applied at the moment of import-from-inbox. */
public record InboxImportRequest(
        String name,
        String description,
        String gearId,
        String userId,
        List<String> tagIds,
        Double weatherTempC,
        Integer weatherHumidityPct,
        Double weatherWindKph,
        String weatherCondition,
        Double weightKg,
        String startLocation,
        String startCountry,
        String endLocation,
        String endCountry
) {}
