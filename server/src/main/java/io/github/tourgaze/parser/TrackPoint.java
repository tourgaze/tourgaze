/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.time.Instant;

/** A single GPS sample from a ride file, format-agnostic. */
public record TrackPoint(Instant time, double lat, double lon, Double altM, Integer hr, Double speedMs) {}
