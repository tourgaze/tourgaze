/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.time.Instant;
import java.util.List;

/**
 * Format-agnostic result of parsing a ride file (FIT, GPX, KML/KMZ, …).
 *
 * Built via {@link #builder()} so each parser sets only the fields its format
 * actually carries — FIT has a rich Session summary, GPX/KML derive most of it
 * from the points — without padding positional {@code null}s, and so adding a
 * new metric never forces a change at every call site.
 */
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
        Double maxSpeedMs,
        // Cadence (rpm) and power (watts) — present when a cadence sensor / power
        // meter recorded them. Null otherwise.
        Integer avgCadence,
        Integer maxCadence,
        Integer avgPowerW,
        Integer maxPowerW,
        // Device sub-sport (FIT), e.g. "road", "mountain", "gravel_cycling",
        // "trail". Captured for future use (discipline detail); null for formats
        // that don't carry it (GPX/TCX).
        String subSport) {

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder — unset fields default to null (or an empty point list). */
    public static final class Builder {
        private List<TrackPoint> points = List.of();
        private String sport;
        private Double distanceM;
        private Double ascentM;
        private Instant startTime;
        private Instant endTime;
        private Integer durationS;
        private Integer movingTimeS;
        private Integer avgHr;
        private Integer maxHr;
        private Double avgSpeedMs;
        private Double maxSpeedMs;
        private Integer avgCadence;
        private Integer maxCadence;
        private Integer avgPowerW;
        private Integer maxPowerW;
        private String subSport;

        public Builder points(List<TrackPoint> v) {
            this.points = v != null ? v : List.of();
            return this;
        }

        public Builder sport(String v) {
            this.sport = v;
            return this;
        }

        public Builder distanceM(Double v) {
            this.distanceM = v;
            return this;
        }

        public Builder ascentM(Double v) {
            this.ascentM = v;
            return this;
        }

        public Builder startTime(Instant v) {
            this.startTime = v;
            return this;
        }

        public Builder endTime(Instant v) {
            this.endTime = v;
            return this;
        }

        public Builder durationS(Integer v) {
            this.durationS = v;
            return this;
        }

        public Builder movingTimeS(Integer v) {
            this.movingTimeS = v;
            return this;
        }

        public Builder avgHr(Integer v) {
            this.avgHr = v;
            return this;
        }

        public Builder maxHr(Integer v) {
            this.maxHr = v;
            return this;
        }

        public Builder avgSpeedMs(Double v) {
            this.avgSpeedMs = v;
            return this;
        }

        public Builder maxSpeedMs(Double v) {
            this.maxSpeedMs = v;
            return this;
        }

        public Builder avgCadence(Integer v) {
            this.avgCadence = v;
            return this;
        }

        public Builder maxCadence(Integer v) {
            this.maxCadence = v;
            return this;
        }

        public Builder avgPowerW(Integer v) {
            this.avgPowerW = v;
            return this;
        }

        public Builder maxPowerW(Integer v) {
            this.maxPowerW = v;
            return this;
        }

        public Builder subSport(String v) {
            this.subSport = v;
            return this;
        }

        public ParseResult build() {
            return new ParseResult(points, sport, distanceM, ascentM, startTime, endTime,
                    durationS, movingTimeS, avgHr, maxHr, avgSpeedMs, maxSpeedMs,
                    avgCadence, maxCadence, avgPowerW, maxPowerW, subSport);
        }
    }
}
