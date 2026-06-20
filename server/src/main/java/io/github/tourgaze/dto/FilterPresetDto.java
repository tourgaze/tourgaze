/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

/**
 * Flat filter-preset DTO. {@code query} is the raw faceted search string the
 * Tours bar parses client-side; {@code groupBy} mirrors the grouping pill.
 */
public record FilterPresetDto(
        String id,
        String name,
        String query,
        String groupBy,
        String groupTagId
) {}
