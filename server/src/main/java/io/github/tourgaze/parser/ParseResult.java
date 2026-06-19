/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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
