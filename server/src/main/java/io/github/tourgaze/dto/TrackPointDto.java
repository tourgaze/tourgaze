/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single GPS track point — used in the on-disk JSON cache.
 * {@code rawIdx} is only set in the chart-resolution cache (LTTB-reduced);
 * it carries the point's original index in the full-resolution array so the
 * frontend can keep map-cursor and chart-cursor in the same index space.
 * {@code @JsonInclude(NON_NULL)} omits rawIdx from the full-resolution cache
 * to avoid bloating it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrackPointDto(
        double lat,
        double lon,
        Double altM,
        Integer hr,
        Double speedMs,
        Integer cadence,
        Integer power,
        Integer rawIdx
) {
    /** Convenience constructor for the full-resolution cache (no rawIdx). */
    public TrackPointDto(double lat, double lon, Double altM, Integer hr, Double speedMs,
            Integer cadence, Integer power) {
        this(lat, lon, altM, hr, speedMs, cadence, power, null);
    }
}
