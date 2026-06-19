/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;

import io.github.tourgaze.enums.ActivityType;
import io.github.tourgaze.parser.SourceFormat;

/**
 * A parsed but not-yet-imported file sitting in the inbox.
 * Pure projection — there's no DB row for inbox items; they live on disk in
 * the inbox/ directory and are re-parsed on each list call.
 *
 * @param filename       the file's name in inbox/
 * @param sha256         SHA-256 hex of the bytes (drives dedup detection)
 * @param sizeBytes      file size on disk
 * @param format         "fit", "gpx", "tcx"
 * @param suggestedName  filename with extension stripped — pre-fills the form
 * @param activityType   inferred from FIT Session message
 * @param startTime      parsed start time, if available
 * @param distanceKm     parsed distance
 * @param durationS      parsed elapsed time
 * @param startLat       first GPS point latitude
 * @param startLon       first GPS point longitude
 * @param existingActivityId  if this hash is already in the DB, the existing activity's ID
 */
public record InboxItemDto(
        String filename,
        String sha256,
        long sizeBytes,
        SourceFormat format,
        String suggestedName,
        ActivityType activityType,
        Instant startTime,
        Double distanceKm,
        Integer durationS,
        Double startLat,
        Double startLon,
        // Last GPS point of the FIT track. Surfaced so the AddTour form can
        // reverse-geocode the END location as well, not just the start —
        // helpful for point-to-point rides (Mallorca east coast → west, etc).
        Double endLat,
        Double endLon,
        String existingActivityId,
        // Proposed gear (heuristic from past rides of similar pace + hilliness) —
        // pre-fills the AddTour gear picker. Null if no usable history.
        String suggestedGearId,
        String suggestedGearName
) {}
