/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.time.Instant;

/**
 * A photo embedded in a ride file (e.g. an OpenTracks KMZ {@code <PhotoOverlay>}
 * referencing an {@code images/…} entry in the same archive). {@code name} is the
 * basename to store it under; {@code lat}/{@code lon} are the capture location
 * from the KML (null if absent), {@code time} the capture timestamp.
 */
public record EmbeddedPhoto(String name, byte[] bytes, Double lat, Double lon, Instant time) {}
