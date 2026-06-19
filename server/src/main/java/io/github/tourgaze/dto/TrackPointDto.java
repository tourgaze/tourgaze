/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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
        Integer rawIdx
) {
    /** Convenience constructor for the full-resolution cache (no rawIdx). */
    public TrackPointDto(double lat, double lon, Double altM, Integer hr, Double speedMs) {
        this(lat, lon, altM, hr, speedMs, null);
    }
}
