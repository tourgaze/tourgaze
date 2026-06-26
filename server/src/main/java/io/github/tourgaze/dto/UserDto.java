/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
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
        Integer maxHr,
        Integer ftpW
) {
}
