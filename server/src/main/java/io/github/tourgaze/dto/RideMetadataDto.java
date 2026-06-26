/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;
import java.util.List;

import io.github.tourgaze.parser.SourceFormat;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Recovery/export shape of a single ride's metadata — the contents of each
 * {@code <source-basename>.metadata.json} sidecar in store/, and the body of
 * {@code GET /api/activities/{id}/metadata}. Carries everything needed to
 * rebuild the DB row from the source files alone, with gear / rider / tags
 * resolved BY NAME so a recovery survives id changes. Track points are NOT
 * included — they're recomputed from the source FIT/GPX on demand.
 */
@Schema(
        name = "RideMetadata",
        description = "Full, self-contained metadata for one ride — the contents of its "
                + "store/<name>.metadata.json sidecar. Enough to rebuild the DB row from the "
                + "source files alone; gear/rider/tags are resolved by name so recovery survives id changes.")
public record RideMetadataDto(
        @Schema(description = "Sidecar format version, for forward-compatible recovery.", example = "1")
        int schemaVersion,
        @Schema(description = "When this metadata snapshot was written.")
        Instant exportedAt,
        @Schema(description = "Activity id (short UUID v7).")
        String id,
        String name,
        String description,
        @Schema(description = "Sport key: cycling, running, hiking, …", example = "cycling")
        String activityType,
        Instant startTime,
        Instant endTime,
        Integer durationS,
        Integer movingTimeS,
        Double distanceKm,
        Double elevationGainM,
        Double elevationLossM,
        Integer avgHr,
        Integer maxHr,
        Double avgSpeedKmh,
        Double maxSpeedKmh,
        Double startLat,
        Double startLon,
        String startLocation,
        String startCountry,
        String endLocation,
        String endCountry,
        Double weatherTempC,
        Integer weatherHumidityPct,
        Double weatherWindKph,
        String weatherCondition,
        Instant weatherFetchedAt,
        Double weightKg,
        Instant importedAt,
        @Schema(description = "Provenance of the original ride file.")
        SourceRef source,
        @Schema(description = "Gear used, resolved by name (null if none).", nullable = true)
        GearRef gear,
        @Schema(description = "Rider, resolved by name (null if unassigned).", nullable = true)
        RiderRef rider,
        @Schema(description = "Applied tags, resolved by name.")
        List<TagRef> tags,
        @Schema(description = "Free-form attributes incl. ride events (attributes.events) — "
                + "user data not derivable from the source file, so it must live in the sidecar.")
        java.util.Map<String, Object> attributes
) {
    @Schema(name = "RideSourceRef", description = "Original ride-file provenance. sourceFilename is the on-disk store name; originalFilename is what the user dropped.")
    public record SourceRef(String sourceFilename, String originalFilename, SourceFormat sourceFormat, String sourceHash) {}

    @Schema(name = "RideGearRef")
    public record GearRef(String id, String name, String type, Double weightKg) {}

    @Schema(name = "RideRiderRef")
    public record RiderRef(String id, String username, String displayName) {}

    @Schema(name = "RideTagRef")
    public record TagRef(String id, String name) {}
}
