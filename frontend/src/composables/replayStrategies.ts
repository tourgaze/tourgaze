import { distanceM } from '@/lib/geo'
import type { TrackPoint } from '@/api/client'

/**
 * Replay camera "strategies" — the cinematic profiles the user picks in the
 * playback toolbar. Every active strategy reads from a pre-computed
 * {@link SmoothedBearings} array so the camera never jitters frame-to-frame.
 *
 * Strategy field reference:
 *   pitch       — final camera tilt in degrees (0 = top-down, 60+ = third-person)
 *   bearingMode — 'smoothed' uses the pre-baked EMA bearing array; 'fixed' locks
 *                 to north; 'snake' uses the per-segment raw direction (jumpy
 *                 but accurate to every corner — useful for "drone" mode).
 *   smoothAlpha — EMA alpha for that strategy's smoothed bearing array. Lower
 *                 means smoother (helicopter); higher means more responsive
 *                 (chase). Ignored when bearingMode != 'smoothed'.
 *   lookAhead   — how many points ahead of the rider the camera centers on.
 *                 Higher feels more like a chase helicopter.
 *   minZoom     — never zoom out below this when active.
 *   centerBias  — 0 → camera sits exactly on the rider; 1 → on the look-ahead
 *                 point. Tuned per strategy for "Tour de France" framing.
 *   offsetY     — vertical offset of the rider in the viewport, as a fraction
 *                 of canvas height. Positive pushes rider toward bottom of
 *                 frame, leaving room ahead.
 *   duration    — easeTo duration in ms.
 *   requires3D  — only fully active when the hillshade/terrain provider is on.
 */
export type ReplayStrategy = 'helicopter' | 'drone' | 'follow' | 'topdown'

export type ReplayStrategyConfig = {
  id: ReplayStrategy
  label: string
  description: string
  pitch: number
  pitchFlat: number
  bearingMode: 'smoothed' | 'fixed'
  smoothAlpha: number
  lookAhead: number
  minZoom: number
  centerBias: number
  offsetY: number
  duration: number
  requires3D?: boolean
  /**
   * Camera deadzone as a fraction of the smaller viewport dimension — how far the
   * rider may roam from centre before the camera moves. Bigger = calmer map (the
   * drone "hovers" and lets the rider drift in-frame). Defaults to 0.08.
   */
  deadzoneFrac?: number
}

export const REPLAY_STRATEGIES: ReplayStrategyConfig[] = [
  // Pitches: `pitch` is used when the active basemap has DEM (terrain
  // hillshade / 3D mesh) — go all-in on the tilt because the elevation
  // payoff is real. `pitchFlat` is used on every other basemap (OSM, Carto,
  // ESRI, OpenFreeMap-vector). Even without DEM we still tilt — MapLibre
  // supports pitch on flat raster + vector styles; the projected raster
  // gives a fake-3D depth feel that reads way better than top-down for
  // replay. Set to 0 only for strategies whose whole identity IS top-down
  // (follow / topdown below).
  {
    id: 'drone',
    label: 'Drone',
    description: 'Quadcopter feel — leans into the terrain for an elevation view, smooth tight follow. Reads like footage from a DJI hovering close behind you.',
    // "Trench run": camera sits low behind the rider, steeply pitched so you look
    // straight down the road/valley ahead (the rider rushing forward, world coming
    // at you). Rider a bit below centre with a lead up the road so the "trench"
    // fills the upper frame. Gentle deadzone keeps it from flickering (no hard
    // lock) while the map rotates to travel direction via the windowed heading.
    pitch: 68, pitchFlat: 58,   // steep forward look down the corridor
    bearingMode: 'smoothed', smoothAlpha: 0.10,
    lookAhead: 10,
    minZoom: 15.5,
    centerBias: 0.5,      // look-at well ahead → down the trench
    offsetY: 0.16,        // rider lower → road ahead fills the frame
    duration: 320,
    deadzoneFrac: 0.1,    // gentle give → smooth, no flicker
  },
  {
    id: 'helicopter',
    label: 'Helicopter',
    description: 'US chase-chopper style. Camera hangs off the right side, road runs along one half of the frame, big slow swings between pre-planned shots.',
    pitch: 78, pitchFlat: 40,
    bearingMode: 'smoothed', smoothAlpha: 0.04,
    lookAhead: 25,
    minZoom: 13,
    centerBias: 0.7,
    offsetY: 0.28,
    duration: 600,
  },
  {
    id: 'follow',
    label: 'Follow (north-up)',
    description: 'Map north stays up — no rotation, easy to track street names.',
    pitch: 0, pitchFlat: 0,
    bearingMode: 'fixed', smoothAlpha: 1,
    lookAhead: 0,
    minZoom: 13,
    centerBias: 0,
    offsetY: 0,
    duration: 320,
  },
  {
    id: 'topdown',
    label: 'Top-down',
    description: 'Bird\'s eye — pure 2D, locked north.',
    pitch: 0, pitchFlat: 0,
    bearingMode: 'fixed', smoothAlpha: 1,
    lookAhead: 0,
    minZoom: 13.5,
    centerBias: 0,
    offsetY: 0,
    duration: 280,
  },
]

export const REPLAY_STRATEGY_MAP: Record<ReplayStrategy, ReplayStrategyConfig> = Object.fromEntries(
  REPLAY_STRATEGIES.map(s => [s.id, s]),
) as Record<ReplayStrategy, ReplayStrategyConfig>

/**
 * Cosine/sine-pair EMA over an array of bearings (degrees). Doing the EMA on
 * the unit vector avoids the 359° → 0° wrap discontinuity that plain scalar
 * EMA blows up on.
 */
function emaCircularBearings(raw: number[], alpha: number): Float32Array {
  const N = raw.length
  const out = new Float32Array(N)
  if (N === 0) return out
  let cx = Math.cos(raw[0] * Math.PI / 180)
  let cy = Math.sin(raw[0] * Math.PI / 180)
  out[0] = raw[0]
  for (let i = 1; i < N; i++) {
    const r = raw[i] * Math.PI / 180
    cx = alpha * Math.cos(r) + (1 - alpha) * cx
    cy = alpha * Math.sin(r) + (1 - alpha) * cy
    out[i] = Math.atan2(cy, cx) * 180 / Math.PI
  }
  return out
}

export type SmoothedBearings = {
  raw: Float32Array
  byStrategy: Record<ReplayStrategy, Float32Array>
  /**
   * 1 = the rider's position will return within {@link HOLD_RETURN_RADIUS_M} of
   * its current position within {@link HOLD_LOOKAHEAD_POINTS} points → the
   * camera should just sit and wait through the out-and-back / dead-end side
   * street rather than chase the wiggle.
   */
  holdHint: Uint8Array
}

/**
 * One cinematic "shot" for the helicopter strategy — a fixed camera pose plus
 * the contiguous range of track indices it covers. While the rider is inside
 * `[fromIdx, toIdx]`, the camera holds and the rider drifts across the frame.
 * Entering the next shot triggers a slow pan to its (different) zoom + centre,
 * so each cut is a meaningful camera move — TdF helicopter style, not tight
 * tracking.
 */
export type HeliShot = {
  fromIdx: number
  toIdx: number
  centerLng: number
  centerLat: number
  /** Per-scene zoom, picked so the chunk fits with padding. */
  zoom: number
  /** Degrees. Aligned with the road's average direction through this segment. */
  bearing: number
}

/**
 * Pick a Mercator zoom level (float) such that a bbox of the given half-extents
 * in metres fits inside `viewportPxW × viewportPxH` with 15 % padding. The
 * limiting dimension wins (whichever requires more zoom-out). Mean latitude
 * cancels distortion in the Mercator mpp formula.
 */
function zoomForBbox(
  halfWidthM: number, halfHeightM: number,
  viewportPxW: number, viewportPxH: number,
  meanLat: number,
): number {
  const mppForW = halfWidthM / (viewportPxW * 0.5 * 0.85)
  const mppForH = halfHeightM / (viewportPxH * 0.5 * 0.85)
  const mpp = Math.max(mppForW, mppForH)
  if (mpp <= 0) return 18
  return Math.log2(156543.03392 * Math.cos(meanLat * Math.PI / 180) / mpp)
}

/**
 * Plan a small number of cinematic helicopter shots for the whole ride.
 *
 * Approach:
 *  1. Sum total ride distance to pick a target shot count
 *     (~1 shot per 8 km, clamped to [2, 6]).
 *  2. Walk the track and commit a shot once the accumulated distance hits
 *     `totalDist / targetCount`. Each shot's zoom is computed from its bbox
 *     so the chunk fits the viewport — short tight twisty sections zoom in,
 *     long sweeping straights zoom way out.
 *  3. Result: few scenes, each covering substantial route, with bigger pans
 *     and zoom shifts between them. The camera stays still much more of the
 *     time; when it moves, the move reads as a deliberate cinematic swing.
 *
 * Bearings come from the already-baked helicopter EMA so the shot bearing
 * matches the average travel direction through the scene.
 */
export function planHeliShots(
  points: TrackPoint[],
  viewportPxW: number,
  viewportPxH: number,
  maxZoom: number,
  bearings: Float32Array,
): HeliShot[] {
  if (points.length < 2 || viewportPxW <= 0 || viewportPxH <= 0) return []

  // ── Distance-based shot count ───────────────────────────────────────────
  let totalDist = 0
  const cumDist = new Float32Array(points.length)
  for (let i = 1; i < points.length; i++) {
    const d = distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
    totalDist += d
    cumDist[i] = totalDist
  }
  // ~1 shot per 8 km of riding, clamped to [2, 6]. Tuned so a 30 km Hausrunde
  // gets ~4 shots, a 100 km alpine day gets 6, anything under 16 km gets 2.
  const targetShotCount = Math.max(2, Math.min(6, Math.round(totalDist / 8000)))
  const distPerShot = totalDist / targetShotCount

  // ── Walk and commit shots at distance boundaries ───────────────────────
  const shots: HeliShot[] = []
  let chunkStart = 0
  let nextBoundary = distPerShot

  for (let i = 1; i < points.length; i++) {
    if (cumDist[i] < nextBoundary && i < points.length - 1) continue

    const chunkEnd = i  // inclusive
    // Compute bbox of [chunkStart..chunkEnd]
    let minLat = points[chunkStart].lat, maxLat = minLat
    let minLon = points[chunkStart].lon, maxLon = minLon
    for (let k = chunkStart + 1; k <= chunkEnd; k++) {
      if (points[k].lat < minLat) minLat = points[k].lat
      else if (points[k].lat > maxLat) maxLat = points[k].lat
      if (points[k].lon < minLon) minLon = points[k].lon
      else if (points[k].lon > maxLon) maxLon = points[k].lon
    }
    const midLat = (minLat + maxLat) / 2
    const midLon = (minLon + maxLon) / 2
    const halfHeight_m = distanceM(minLat, midLon, maxLat, midLon) / 2
    const halfWidth_m = distanceM(midLat, minLon, midLat, maxLon) / 2

    // Pick zoom that fits this chunk; clamp into [9.5, maxZoom] so we don't
    // zoom out to continent level on a single big straight, and don't zoom
    // in past the strategy's intended ceiling on a tight micro-chunk. The
    // 1.8× slop leaves room for the lateral side-chase offset below so the
    // route never gets pushed off the visible half of the frame.
    let z = zoomForBbox(halfWidth_m * 1.8, halfHeight_m * 1.8, viewportPxW, viewportPxH, midLat)
    z = Math.max(9.5, Math.min(maxZoom, z))

    // Circular mean of bearings over the chunk.
    let cx = 0, cy = 0
    for (let k = chunkStart; k <= chunkEnd; k++) {
      cx += Math.cos(bearings[k] * Math.PI / 180)
      cy += Math.sin(bearings[k] * Math.PI / 180)
    }
    const avgBearing = Math.atan2(cy, cx) * 180 / Math.PI

    // ── Side selection: hover on the OUTSIDE of the curve ──────────────────
    // Without DEM data we can't see where the mountain actually is, so we
    // use the route's own curvature as a proxy: the outside of a turn is
    // (almost always) the open side with the view — that's the TdF / chase-
    // chopper cinematography convention. If we put the camera on the INSIDE
    // of a curve, the mountain face occludes the track.
    //
    // Sum the per-step bearing deltas, wrapped to [-180, 180]:
    //   > 0  → route turns clockwise (right) → camera goes LEFT (outside)
    //   < 0  → route turns counter-clockwise (left) → camera goes RIGHT
    //   ~0   → straight → default RIGHT (matches the existing chase feel)
    let netRotation = 0
    for (let k = chunkStart + 1; k <= chunkEnd; k++) {
      let delta = bearings[k] - bearings[k - 1]
      if (delta > 180) delta -= 360
      else if (delta < -180) delta += 360
      netRotation += delta
    }
    const CURVE_THRESHOLD_DEG = 12
    const sideSign = netRotation > CURVE_THRESHOLD_DEG ? -1
                   : netRotation < -CURVE_THRESHOLD_DEG ? 1
                   : 1  // default RIGHT for near-straight sections

    // Smaller offset magnitude than before so the rider never gets pushed
    // off-frame at the strategy's zoom — clamp to [200m, 700m] depending on
    // bbox size. Combined with the 1.8× zoom slop above, the rider sits at
    // ~25-35 % of viewport width from center on the inside edge of frame.
    const sideOffsetM = Math.max(200, Math.min(700, (halfWidth_m + halfHeight_m) * 0.22))
    const offsetDirRad = ((avgBearing + 90 * sideSign) * Math.PI) / 180
    const dNorthM = sideOffsetM * Math.cos(offsetDirRad)
    const dEastM = sideOffsetM * Math.sin(offsetDirRad)
    const dLat = dNorthM / 111000
    const dLon = dEastM / (111000 * Math.cos(midLat * Math.PI / 180))

    shots.push({
      fromIdx: chunkStart,
      toIdx: chunkEnd,
      centerLng: midLon + dLon,
      centerLat: midLat + dLat,
      zoom: z,
      bearing: avgBearing,
    })

    chunkStart = chunkEnd + 1
    nextBoundary += distPerShot
    if (chunkStart >= points.length) break
  }

  return shots
}

/** Binary search the shot containing `idx`. Returns last shot if past the end. */
export function findHeliShotIdx(shots: HeliShot[], idx: number): number {
  if (!shots.length) return -1
  if (idx <= shots[0].fromIdx) return 0
  if (idx >= shots[shots.length - 1].toIdx) return shots.length - 1
  let lo = 0, hi = shots.length - 1
  while (lo <= hi) {
    const m = (lo + hi) >> 1
    if (idx < shots[m].fromIdx) hi = m - 1
    else if (idx > shots[m].toIdx) lo = m + 1
    else return m
  }
  return Math.max(0, Math.min(shots.length - 1, lo))
}

const HOLD_LOOKAHEAD_POINTS = 30   // ~30s at 1 Hz FIT sample rate
const HOLD_RETURN_RADIUS_M = 120


/**
 * Build one raw bearing array + per-strategy smoothed copies in a single pass
 * over the track. Runs in O(N · #strategies) — fine even for 50k-point rides
 * because each iteration is a handful of trig ops.
 */
export function precomputeSmoothedBearings(points: TrackPoint[]): SmoothedBearings {
  const N = points.length
  const raw = new Float32Array(N)
  if (N === 0) {
    return {
      raw,
      byStrategy: Object.fromEntries(REPLAY_STRATEGIES.map(s => [s.id, new Float32Array(0)])) as Record<ReplayStrategy, Float32Array>,
      holdHint: new Uint8Array(0),
    }
  }
  // Heading from a point ~LOOKAHEAD_M AHEAD, not the next sample. Consecutive GPS
  // points are centimetres apart, so a per-step heading is dominated by jitter and
  // makes the camera (and thus the rider) flicker. We know the whole track, so we
  // look ahead to a point far enough away that the direction is stable — "where
  // you're going", computed in advance. The EMA below then just polishes it.
  const LOOKAHEAD_M = 30
  for (let i = 0; i < N; i++) {
    let j = i + 1
    while (j < N - 1 && distanceM(points[i].lat, points[i].lon, points[j].lat, points[j].lon) < LOOKAHEAD_M)
      j++
    const a = points[i]
    const b = points[Math.min(j, N - 1)]
    const dLon = b.lon - a.lon
    const dLat = b.lat - a.lat
    raw[i] = (Math.abs(dLon) + Math.abs(dLat) > 1e-9)
      ? Math.atan2(dLon, dLat) * 180 / Math.PI
      : (i > 0 ? raw[i - 1] : 0)
  }

  const rawArr = Array.from(raw)
  const byStrategy = {} as Record<ReplayStrategy, Float32Array>
  for (const s of REPLAY_STRATEGIES) {
    byStrategy[s.id] = s.bearingMode === 'smoothed'
      ? emaCircularBearings(rawArr, s.smoothAlpha)
      : raw
  }

  // Hold hints: park the camera through tight out-and-backs (cul-de-sacs,
  // dead-end side streets, switchbacks that return to the same spot soon).
  // Without this, the camera jitters in and out of every wiggle.
  const holdHint = new Uint8Array(N)
  for (let i = 0; i < N; i++) {
    const lastJ = Math.min(N - 1, i + HOLD_LOOKAHEAD_POINTS)
    let returns = false
    for (let j = i + 5; j <= lastJ; j++) {
      if (distanceM(points[i].lat, points[i].lon, points[j].lat, points[j].lon) < HOLD_RETURN_RADIUS_M) {
        returns = true; break
      }
    }
    holdHint[i] = returns ? 1 : 0
  }

  return { raw, byStrategy, holdHint }
}
