import type { StyleSpecification } from 'maplibre-gl'

/** Local proxied raster tile URL template for a provider id. */
export function tileUrl(providerId: string): string {
  return `${window.location.origin}/api/tiles/{z}/{x}/{y}.png?providerid=${providerId}`
}

/**
 * A minimal single-raster MapLibre style — the one used by the lightweight maps
 * (Compare, Markers, start-location preview). The main ActivityMap has its own
 * richer builder (DEM/hillshade overlays); this is the shared simple case.
 */
export function rasterStyle(providerId = 'osm'): StyleSpecification {
  return {
    version: 8,
    sources: { osm: { type: 'raster', tiles: [tileUrl(providerId)], tileSize: 256 } },
    layers: [{ id: 'osm', type: 'raster', source: 'osm' }],
  }
}
