/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.time.Instant;
import java.util.List;

/** Format-agnostic result of parsing a ride file (FIT, GPX, …). */
public record ParseResult(
        List<TrackPoint> points,
        String sport,
        Double distanceM,
        Double ascentM,
        Instant startTime,
        Instant endTime,
        Integer durationS,
        Integer movingTimeS,
        Integer avgHr,
        Integer maxHr,
        Double avgSpeedMs,
        Double maxSpeedMs
) {}
