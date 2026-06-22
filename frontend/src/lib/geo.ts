// Single source for lat/lon distance on the frontend — mirrors the backend
// `io.github.tourgaze.util.Geo`. Haversine (spherical) is plenty accurate for
// UI distances (chart axis, replay camera, compare gaps, segment slopes); we
// deliberately avoid pulling a geodesic npm dependency for sub-metre precision
// nothing here needs. Always (lat, lon, lat, lon) order.

const EARTH_R_M = 6_371_000

/** Great-circle distance between two lat/lon points, in metres. */
export function distanceM(aLat: number, aLon: number, bLat: number, bLon: number): number {
  const dLat = (bLat - aLat) * Math.PI / 180
  const dLon = (bLon - aLon) * Math.PI / 180
  const s = Math.sin(dLat / 2) ** 2
    + Math.cos(aLat * Math.PI / 180) * Math.cos(bLat * Math.PI / 180) * Math.sin(dLon / 2) ** 2
  return EARTH_R_M * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s))
}

/** Same, in kilometres. */
export function distanceKm(aLat: number, aLon: number, bLat: number, bLon: number): number {
  return distanceM(aLat, aLon, bLat, bLon) / 1000
}

export type BBox = { minLat: number; maxLat: number; minLon: number; maxLon: number }

/**
 * Axis-aligned bounding box of a set of points, optionally grown by `bufferKm`
 * on every side (km → degrees; longitude scaled by latitude). Null for empty
 * input. Used to keep ride-local lists (markers) relevant — a Sweden ride
 * shouldn't list a Mallorca marker.
 */
export function bboxOf(points: { lat: number; lon: number }[], bufferKm = 0): BBox | null {
  if (!points.length) return null
  let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity
  for (const p of points) {
    if (p.lat < minLat) minLat = p.lat
    if (p.lat > maxLat) maxLat = p.lat
    if (p.lon < minLon) minLon = p.lon
    if (p.lon > maxLon) maxLon = p.lon
  }
  if (bufferKm > 0) {
    const dLat = bufferKm / 111.32
    const midLat = (minLat + maxLat) / 2
    const dLon = bufferKm / (111.32 * Math.max(0.05, Math.cos((midLat * Math.PI) / 180)))
    minLat -= dLat; maxLat += dLat; minLon -= dLon; maxLon += dLon
  }
  return { minLat, maxLat, minLon, maxLon }
}

/** True if the point is inside the box. A null box (no track) matches everything. */
export function inBBox(lat: number, lon: number, b: BBox | null): boolean {
  return !b || (lat >= b.minLat && lat <= b.maxLat && lon >= b.minLon && lon <= b.maxLon)
}
