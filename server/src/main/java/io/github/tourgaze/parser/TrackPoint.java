/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.time.Instant;

/** A single GPS sample from a ride file, format-agnostic. */
public record TrackPoint(Instant time, double lat, double lon, Double altM, Integer hr, Double speedMs) {}
