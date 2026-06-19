/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

/** Editable shape of a user-defined {@link io.github.tourgaze.entity.MapProvider}. */
public record MapProviderDto(
        String id,
        String name,
        String description,
        String type,
        String urlTemplate,
        String styleUrl,
        Integer maxZoom,
        String attribution,
        boolean dark
) {}
