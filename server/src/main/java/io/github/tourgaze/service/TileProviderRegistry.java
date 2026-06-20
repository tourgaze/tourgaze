/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import io.github.tourgaze.dto.TileProviderDto;
import io.github.tourgaze.entity.MapProvider;
import io.github.tourgaze.repository.MapProviderRepository;

/**
 * Hardcoded catalog of map tile providers. Raster providers route through
 * our `TileController` proxy so we get local caching + a stable per-provider
 * URL. The vector provider (OpenFreeMap) is fetched directly by MapLibre via
 * its style JSON — no key needed, no rate-limit configured.
 *
 * To add a new provider:
 * 1. Add a new TileProviderDto row in {@link #ALL}.
 * 2. For raster: also add an entry to PROVIDER_DEFAULTS in TileController
 * and TileWarmerService so the proxy + warmer know its upstream URL.
 * 3. For vector with an API-key requirement, read the key from
 * SettingRepository and interpolate into urlTemplate / styleUrl at list
 * time (extend this service rather than hardcoding).
 */
@Service
public class TileProviderRegistry {

	private static final List<TileProviderDto> ALL = List.of(
			new TileProviderDto(
					"osm",
					"Standard",
					"OpenStreetMap — classic streets, balanced for general use.",
					"raster", "maplibre",
					"/api/tiles/{z}/{x}/{y}.png?providerid=osm", null,
					19,
					"© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors",
					false, false),
			new TileProviderDto(
					"carto-light",
					"Carto Light",
					"Pale background tuned for routes — high contrast against colored tracks.",
					"raster", "maplibre",
					"/api/tiles/{z}/{x}/{y}.png?providerid=carto-light", null,
					19,
					"© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors, © <a href=\"https://carto.com/attributions\">CARTO</a>",
					false, false),
			new TileProviderDto(
					"carto-dark",
					"Carto Dark",
					"Dark mode basemap — great with bright HR-colored tracks at night.",
					"raster", "maplibre",
					"/api/tiles/{z}/{x}/{y}.png?providerid=carto-dark", null,
					19,
					"© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors, © <a href=\"https://carto.com/attributions\">CARTO</a>",
					false, true),
			new TileProviderDto(
					"esri-topo",
					"ESRI Topo",
					"Sharp topographic basemap with contour lines — outdoor / alpine focus.",
					"raster", "maplibre",
					"/api/tiles/{z}/{x}/{y}.png?providerid=esri-topo", null,
					19,
					"Tiles © Esri — Esri, DeLorme, NAVTEQ, TomTom",
					false, false),
			new TileProviderDto(
					"esri-imagery",
					"Satellite",
					"ESRI World Imagery — orthorectified satellite/aerial photography.",
					"raster", "maplibre",
					"/api/tiles/{z}/{x}/{y}.png?providerid=esri-imagery", null,
					19,
					"Tiles © Esri — Source: Esri, Maxar, GeoEye, Earthstar Geographics",
					false, true),
			new TileProviderDto(
					"openfreemap",
					"OpenFreeMap (vector)",
					"Free vector basemap, OSM data with crisp labels at any zoom. No key, no rate limit.",
					"vector", "maplibre",
					null,
					"https://tiles.openfreemap.org/styles/liberty",
					20,
					"© <a href=\"https://openfreemap.org/\">OpenFreeMap</a> · © <a href=\"https://www.openmaptiles.org/\">OpenMapTiles</a> · © <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>",
					false, false),
			// Hillshade DEM — not a basemap, used as an overlay source.
			new TileProviderDto(
					"terrain",
					"Hillshade DEM",
					"Terrarium-encoded elevation tiles. Used by the hillshade overlay and 3D terrain mesh.",
					"raster", "maplibre",
					"/api/tiles/{z}/{x}/{y}.png?providerid=terrain", null,
					15,
					"Terrain © <a href=\"https://www.mapzen.com/rights\">Mapzen</a>",
					true, false));

	private final MapProviderRepository customRepo;

	public TileProviderRegistry(MapProviderRepository customRepo) {
		this.customRepo = customRepo;
	}

	/** Built-in catalog + user-defined providers, merged. */
	public List<TileProviderDto> all() {
		List<TileProviderDto> out = new ArrayList<>(ALL);
		for (MapProvider mp : customRepo.findAllByOrderByNameAsc()) {
			boolean vector = "vector".equals(mp.getType());
			out.add(new TileProviderDto(
					mp.getId(),
					mp.getName(),
					mp.getDescription(),
					mp.getType(),
					"maplibre",
					// Raster custom providers still load through our caching proxy.
					vector ? null : "/api/tiles/{z}/{x}/{y}.png?providerid=" + mp.getId(),
					vector ? mp.getStyleUrl() : null,
					mp.getMaxZoom() != null ? mp.getMaxZoom() : 19,
					mp.getAttribution(),
					false,
					mp.isDark()));
		}
		return out;
	}
}
