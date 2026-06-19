/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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
