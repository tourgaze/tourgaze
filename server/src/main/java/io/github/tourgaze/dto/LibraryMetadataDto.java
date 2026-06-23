/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Library-level recovery sidecar ({@code store/library.metadata.json}): the
 * rider profile(s) and the full gear list. These aren't derivable from any ride
 * file and a rider's profile (or an unused bike) isn't referenced by any
 * per-ride sidecar — so without this, a DB rebuilt purely from the per-ride
 * sidecars would lose the user and any gear not attached to a ride. Recovery
 * restores these first, then links rides to them by id / name.
 */
@Schema(name = "LibraryMetadata", description = "Rider profiles + full gear list for disaster recovery.")
public record LibraryMetadataDto(
        int schemaVersion,
        Instant exportedAt,
        List<UserDto> users,
        List<GearDto> gear
) {
    public static final int SCHEMA_VERSION = 1;
}
