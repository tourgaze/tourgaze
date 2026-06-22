import type { StyleSpecification } from 'maplibre-gl'

/** Local proxied raster tile URL template for a provider id. */
export function tileUrl(providerId: string): string {
  return `${window.location.origin}/api/tiles/{z}/{x}/{y}.png?providerid=${providerId}`
}

/**
 * Tile attribution per provider — REQUIRED to be shown on the map by OSM's and
 * CARTO's terms. Keep in sync with TileController's provider list.
 */
const TILE_ATTRIBUTION: Record<string, string> = {
  osm: '© OpenStreetMap contributors',
  'carto-light': '© OpenStreetMap contributors, © CARTO',
  'carto-dark': '© OpenStreetMap contributors, © CARTO',
}

/**
 * A minimal single-raster MapLibre style — the one used by the lightweight maps
 * (Compare, Markers, start-location preview). The main ActivityMap has its own
 * richer builder (DEM/hillshade overlays); this is the shared simple case.
 *
 * The source carries `attribution`, so any map using this style shows the
 * required credit once its AttributionControl is enabled (don't pass
 * `attributionControl: false` without surfacing attribution another way).
 */
export function rasterStyle(providerId = 'osm'): StyleSpecification {
  return {
    version: 8,
    sources: {
      osm: {
        type: 'raster',
        tiles: [tileUrl(providerId)],
        tileSize: 256,
        attribution: TILE_ATTRIBUTION[providerId] ?? '© OpenStreetMap contributors',
      },
    },
    layers: [{ id: 'osm', type: 'raster', source: 'osm' }],
  }
}
