/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
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
