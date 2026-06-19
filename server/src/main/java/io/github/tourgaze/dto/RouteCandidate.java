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
 * Lightweight projection of an activity for route-similarity scoring — just the
 * scalar columns the compare picker needs plus the precomputed route fingerprint
 * ({@code routeGeocells}). Loading these instead of full managed entities avoids
 * a heavy {@code findAll()} and the per-row tag N+1 on every /similar request.
 * Tag membership is resolved separately in one bulk query.
 */
public record RouteCandidate(
        String id,
        String name,
        String activityType,
        Instant startTime,
        Double distanceKm,
        Integer durationS,
        String startLocation,
        String routeGeocells
) {}
