/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
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
        String routeGeocells,
        // Display name of the ride's owner (null if unassigned) — lets the compare
        // picker label cross-user rides, since compare spans all riders.
        String riderName
) {}
