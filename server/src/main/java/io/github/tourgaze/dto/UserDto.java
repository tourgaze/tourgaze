/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;
import java.time.LocalDate;

public record UserDto(
        String id,
        String username,
        String displayName,
        Instant createdAt,
        LocalDate dateOfBirth,
        Integer heightCm,
        Double weightKg,
        String gender,
        Integer restingHr,
        Integer maxHr
) {
}
