/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;

/**
 * Uniform JSON error body returned by {@link io.github.tourgaze.config.GlobalExceptionHandler}.
 * Gives the frontend a stable shape ({@code status}, machine {@code code}, human
 * {@code message}, request {@code path}) instead of Spring's default Whitelabel /
 * raw stack-trace 500s.
 *
 * @param status  HTTP status code
 * @param code    short machine-readable code (e.g. CONFLICT, NOT_FOUND)
 * @param message human-readable detail
 * @param path    request URI that failed
 * @param timestamp when the error was produced
 */
public record ApiErrorResponse(
        int status,
        String code,
        String message,
        String path,
        Instant timestamp) {

    public static ApiErrorResponse of(int status, String code, String message, String path) {
        return new ApiErrorResponse(status, code, message, path, Instant.now());
    }
}
