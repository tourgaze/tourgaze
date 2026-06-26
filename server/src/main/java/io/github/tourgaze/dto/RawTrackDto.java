/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Columnar per-point raw data for the ride-detail page —
 * {@code GET /api/activities/{id}/raw?channels=cadence,power&reduced=true}.
 *
 * <p>
 * One parallel array per <em>requested</em> channel (others omitted via
 * {@code NON_NULL}); arrays line up by point index. Columnar (not a list of
 * objects) so a chart can bind a channel directly without per-point unpacking.
 * Indexed by point position — the cache carries no per-point timestamp.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RawTrackDto(
        int count,
        boolean reduced,
        List<Double> lat,
        List<Double> lon,
        List<Double> alt,
        List<Integer> hr,
        List<Double> speed,
        List<Integer> cadence,
        List<Integer> power
) {
}
