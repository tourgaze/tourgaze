/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.util.List;

/**
 * What the frontend should pre-fill on the AddTour form before the user has
 * touched it. All fields nullable / empty — the UI treats anything non-null
 * as a soft suggestion, not a hard write.
 *
 * @param startLocation       Localised display string for the start point, e.g.
 *                            "Tegernsee, Bayern" or "Pollença, Mallorca". Built
 *                            from the most-specific Nominatim address levels
 *                            in the user's configured language.
 * @param endLocation         Same shape for the end point, only set when the
 *                            ride didn't loop back near the start.
 * @param suggestedName       Heuristic tour name. Today: "{startLocation} ride"
 *                            or "{startLocation} → {endLocation}". v2 can lift
 *                            this from Hausrunde cluster names once the
 *                            similarity helper lands.
 * @param suggestedTagIds     Tags lifted from existing activities whose start
 *                            is within ~200m of this ride's start. Sorted by
 *                            frequency. Frontend pre-checks them in the tag
 *                            picker; the user can untick any they don't want.
 * @param country             ISO 3166-1 alpha-2 from Nominatim ("DE", "ES"...).
 * @param region              Top-level admin region name ("Bayern", "Balearen").
 *                            Useful for filtering in the Tours view.
 * @param language            The language code used to localise the strings —
 *                            echoed back so the UI knows what it asked for.
 */
public record PredictionDto(
        String startLocation,
        String endLocation,
        String suggestedName,
        List<String> suggestedTagIds,
        String country,
        String region,
        String language
) {}
