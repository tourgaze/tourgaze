/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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
