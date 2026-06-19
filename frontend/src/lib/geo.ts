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
