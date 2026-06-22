/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.time.Instant;

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
 * @param duplicateOfId    if this track strongly overlaps an already-imported ride
 *                         (same route, not byte-identical), that ride's ID — so the
 *                         inbox can warn before importing a duplicate. Null otherwise.
 * @param duplicateOfName  name of the {@code duplicateOfId} ride, for display.
 */
public record InboxItemDto(
        String filename,
        String sha256,
        long sizeBytes,
        SourceFormat format,
        String suggestedName,
        // Sport key (see Sport masterdata), e.g. "cycling".
        String activityType,
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
        String suggestedGearName,
        // Same-route (not byte-identical) duplicate of an already-imported ride.
        String duplicateOfId,
        String duplicateOfName,
        // Proposal preview baked onto the card (matros-style): reverse-geocoded
        // start place, region, ISO country, and suggested tag names (existing
        // nearby-ride tags + region/country) the user can accept on import.
        String suggestedLocation,
        String region,
        String country,
        java.util.List<String> suggestedTagNames,
        // True while the proposal is still being computed in the background (the
        // file has GPS but the reverse-geocode/tag-vote hasn't run yet). The card
        // shows a "processing" state; an SSE push flips it to ready once warmed.
        boolean proposalPending,
        // True for a skeleton card: the file is listed (name/size/format known) but
        // not yet parsed by the warm job, so distance/type/duplicate are still null.
        // GET /inbox returns these instantly (just a directory listing); an SSE push
        // flips them to fully-parsed cards. Lets the inbox paint immediately.
        boolean parsing,
        // Label of the watch-folder this file was copied in from (Settings → Inbox
        // source), e.g. "Garmin" or "Dropbox". Null for files dropped/uploaded by
        // hand, which have no originating folder.
        String sourceLabel,
        // Which optional sensor channels the file actually carries (a HR strap /
        // cadence sensor / power meter recorded data). Surfaced on the import card
        // so the user sees at a glance what extra data the ride brings.
        boolean hasHeartRate,
        boolean hasCadence,
        boolean hasPower
) {}
