/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

/**
 * Map tile provider catalog entry — surfaced by `GET /api/tile-providers`.
 * The frontend uses these to populate the basemap picker and to decide which
 * renderer component owns the map area (the "center placeholder" is renderer-
 * pluggable: MapLibre handles raster + vector tile styles, but future entries
 * could declare a different renderer like a Three.js-based 3D city engine).
 *
 * @param id           Stable identifier; used in URLs and persisted to the
 *                     `map.provider` setting. Must match what `TileController`
 *                     expects for raster providers that proxy through us.
 * @param label        UI display label.
 * @param description  Short one-line hint shown in the picker.
 * @param type         "raster" or "vector". The frontend builds different
 *                     MapLibre source specs for each.
 * @param renderer     Which frontend renderer owns this provider. Today only
 *                     "maplibre" — but the abstraction lets us plug in a
 *                     streets.gl / Cesium / Three.js renderer per-provider
 *                     without touching the rest of the app.
 * @param urlTemplate  For raster: the proxy URL we serve from
 *                     (`/api/tiles/{z}/{x}/{y}.png?providerid=...`). For
 *                     vector: null (use `styleUrl` instead).
 * @param styleUrl     For vector: the upstream MapLibre style JSON URL.
 *                     For raster: null.
 * @param maxZoom      Highest zoom the source supports.
 * @param attribution  HTML-safe attribution string shown by MapLibre's
 *                     attribution control.
 * @param isElevation  True for DEM sources used by the hillshade / 3D
 *                     terrain layer rather than as a basemap.
 * @param isDark       True for dark-themed basemaps (lets the UI sync the
 *                     app theme if desired).
 */
public record TileProviderDto(
        String id,
        String label,
        String description,
        String type,
        String renderer,
        String urlTemplate,
        String styleUrl,
        Integer maxZoom,
        String attribution,
        boolean isElevation,
        boolean isDark
) {}
