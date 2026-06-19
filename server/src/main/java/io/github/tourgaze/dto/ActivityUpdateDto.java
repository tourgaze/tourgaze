/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.util.List;

/**
 * Partial update payload for an activity. Any null/absent field is left untouched.
 * {@code tagIds} is treated specially: if non-null, it replaces the activity's
 * full tag set (so the caller can clear all tags by passing an empty list).
 */
public record ActivityUpdateDto(
        String name,
        String description,
        String gearId,
        Double weatherTempC,
        Integer weatherHumidityPct,
        Double weatherWindKph,
        String weatherCondition,
        Double weightKg,
        String startLocation,
        String startCountry,
        String endLocation,
        String endCountry,
        List<String> tagIds
) {}
