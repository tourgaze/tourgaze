/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

/**
 * Flat tag DTO — the parent link is by ID, so the frontend can rebuild the
 * tree without N+1 lazy loads on the server.
 */
public record TagDto(
        String id,
        String parentId,
        String name,
        String color,
        String icon
) {}
