import { distanceM } from '../lib/geo'
type Point = {
  lat: number
  lon: number
  altM: number | null
  hr: number | null
  speedMs: number | null
}

type SegmentFeature = {
  type: 'Feature'
  properties: { hr: number; slope: number }
  geometry: {
    type: 'LineString'
    coordinates: [[number, number], [number, number]]
  }
}

type SegReq = { id: number; points: Point[] }
type SegRes = { id: number; features: SegmentFeature[] }


self.onmessage = (e: MessageEvent<SegReq>) => {
  const { id, points } = e.data
  const n = points.length
  const features: SegmentFeature[] = new Array(Math.max(0, n - 1))
  for (let i = 0; i < n - 1; i++) {
    const p = points[i]
    const p2 = points[i + 1]
    const distM = distanceM(p.lat, p.lon, p2.lat, p2.lon)
    const altDelta = (p2.altM ?? 0) - (p.altM ?? 0)
    const slope = distM > 0.5 ? +((altDelta / distM) * 100).toFixed(1) : 0
    features[i] = {
      type: 'Feature',
      properties: { hr: p.hr ?? 0, slope },
      geometry: {
        type: 'LineString',
        coordinates: [
          [p.lon, p.lat],
          [p2.lon, p2.lat],
        ],
      },
    }
  }
  const res: SegRes = { id, features }
  ;(self as unknown as Worker).postMessage(res)
}
