/**
 * Headless replay simulator — drives the real replay cameras over a whole track
 * up front and reports the camera path + quality metrics, with no map and no DOM.
 *
 * Why it exists: the camera "feel" used to be verifiable only by watching a ride.
 * This makes it a function. Change a physics constant or the bearing logic and a
 * unit test (or the optimizer) sees the trajectory change immediately. It reuses
 * the production cameras via {@link createCamera}, so what the sim measures is
 * exactly what ships — there is no second implementation to drift.
 *
 * Determinism: the live dispatcher feeds each frame's emitted bearing/zoom back
 * in as the next frame's "current". We do the same, one step per track point, so
 * the path is reproducible and renderer-free (the offset bake, which needs the
 * live projection, is the only thing left to the dispatcher and is irrelevant to
 * the qualities we measure: rotation, lag, smoothness, holds).
 */

import type { TrackPoint } from '@/api/client'
import { distanceM } from '@/lib/geo'
import { createCamera, normalizeDeg, lerpAngleDeg, type CameraStep, type StepContext } from '@/composables/replayCameras'
import {
  precomputeSmoothedBearings, REPLAY_STRATEGY_MAP,
  type ReplayStrategy, type SmoothedBearings,
} from '@/composables/replayStrategies'
import { DEFAULT_TUNING, type MotionTuning } from '@/composables/replayPhysics'

// Track-index sampling rate the live replay advances at 1× (playbackSpeed × 20).
// Time-based camera eases (helicopter shot pans, entry transitions) are baked
// into this many indices so the precomputed path is smooth when sampled, with no
// per-frame camera state left at playback time.
const INDICES_PER_SEC = 20

// ── Output ──────────────────────────────────────────────────────────────────

/** One resolved camera pose, one per track index. */
export interface ReplayPose {
  idx: number
  lng: number
  lat: number
  bearing: number
  pitch: number
  zoom: number
  /** Vertical pixel offset the dispatcher would bake in (rider-below-centre). */
  offsetY: number
  /** True when the camera parked this tick (dead-end hold / scene hold). */
  hold: boolean
}

export interface SimOptions {
  viewportPxW?: number
  viewportPxH?: number
  is3D?: boolean
  tuning?: MotionTuning
  /** Reuse already-computed bearings (the app has them); recomputed if omitted. */
  bearings?: SmoothedBearings
}

// ── Simulate ──────────────────────────────────────────────────────────────────

/**
 * Run `strategy` over `points` and return the full per-index camera path.
 */
export function simulateReplayPath(
  strategy: ReplayStrategy,
  points: TrackPoint[],
  opts: SimOptions = {},
): ReplayPose[] {
  const viewportPxW = opts.viewportPxW ?? 1280
  const viewportPxH = opts.viewportPxH ?? 720
  const is3D = opts.is3D ?? false
  const config = REPLAY_STRATEGY_MAP[strategy]
  if (!points.length) return []

  const bearings = opts.bearings ?? precomputeSmoothedBearings(points)
  const cam = createCamera(strategy, opts.tuning ?? DEFAULT_TUNING)
  const trackCtx = { points, bearings, viewportPxW, viewportPxH }
  cam.onTrackChange?.(trackCtx)

  const poses: ReplayPose[] = []
  let curBearing = 0
  let curZoom = config.minZoom
  let prev: ReplayPose | null = null

  // Ease-bake state: a 'ease' step (helicopter shot pan / entry) ramps over
  // several indices instead of snapping, so the sampled path stays smooth.
  let easeActive = false
  let easeI = 0
  let easeLen = 1
  let easeFrom: ReplayPose | null = null
  let easeTarget: ReplayPose | null = null

  for (let i = 0; i < points.length; i++) {
    const ctx: StepContext = {
      ...trackCtx,
      idx: i,
      hoverCoords: [points[i].lon, points[i].lat],
      is3D,
      currentBearing: curBearing,
      currentZoom: curZoom,
    }
    const pitchDefault = is3D ? config.pitch : config.pitchFlat
    const step: CameraStep | null = i === 0
      ? (cam.onActivate?.(ctx) ?? cam.step(ctx))
      : cam.step(ctx)

    if (step && step.kind === 'ease') {
      easeTarget = resolvePose(i, step, prev, ctx, pitchDefault)
      easeFrom = prev ?? easeTarget
      easeLen = Math.max(1, Math.round(((step.durationMs ?? 0) / 1000) * INDICES_PER_SEC))
      easeI = 0
      easeActive = true
    } else if (step && step.kind === 'jump') {
      easeActive = false   // a real per-frame follow move cancels any entry ease
    }

    let pose: ReplayPose
    if (easeActive && easeFrom && easeTarget) {
      easeI++
      const t = smoothstep(Math.min(1, easeI / easeLen))
      pose = lerpPose(easeFrom, easeTarget, t, i)
      if (easeI >= easeLen) easeActive = false
    } else if (step && step.kind === 'jump') {
      pose = resolvePose(i, step, prev, ctx, pitchDefault)
    } else {
      pose = prev ? { ...prev, idx: i, hold: true } : resolvePose(i, null, prev, ctx, pitchDefault)
    }

    curBearing = pose.bearing
    curZoom = pose.zoom
    poses.push(pose)
    prev = pose
  }
  return poses
}

const smoothstep = (t: number): number => t * t * (3 - 2 * t)

/** Blend two poses (centre/zoom/pitch/offset linear, bearing shortest-arc). */
export function lerpPose(a: ReplayPose, b: ReplayPose, t: number, idx: number): ReplayPose {
  return {
    idx,
    lng: a.lng + (b.lng - a.lng) * t,
    lat: a.lat + (b.lat - a.lat) * t,
    bearing: lerpAngleDeg(a.bearing, b.bearing, t),
    pitch: a.pitch + (b.pitch - a.pitch) * t,
    zoom: a.zoom + (b.zoom - a.zoom) * t,
    offsetY: a.offsetY + (b.offsetY - a.offsetY) * t,
    hold: a.hold && b.hold,
  }
}

/**
 * Sample the precomputed path at a (possibly fractional) index — the playback
 * read. Interpolates between adjacent poses so sub-index playback stays smooth.
 */
export function samplePath(path: ReplayPose[], frac: number): ReplayPose | null {
  if (!path.length) return null
  const clamped = Math.max(0, Math.min(path.length - 1, frac))
  const i = Math.floor(clamped)
  const f = clamped - i
  if (f === 0 || i >= path.length - 1) return path[i]
  return lerpPose(path[i], path[i + 1], f, i)
}

function resolvePose(
  idx: number,
  step: CameraStep | null,
  prev: ReplayPose | null,
  ctx: StepContext,
  pitchDefault: number,
): ReplayPose {
  if (!step || step.kind === 'hold') {
    // Camera parked — carry the previous pose forward (or seed on the rider).
    if (prev) return { ...prev, idx, hold: true }
    const here = ctx.hoverCoords ?? [0, 0]
    return { idx, lng: here[0], lat: here[1], bearing: ctx.currentBearing, pitch: pitchDefault, zoom: ctx.currentZoom, offsetY: 0, hold: true }
  }
  return {
    idx,
    lng: step.center[0],
    lat: step.center[1],
    bearing: step.bearing ?? ctx.currentBearing,
    pitch: step.pitch ?? pitchDefault,
    zoom: step.zoom ?? ctx.currentZoom,
    offsetY: step.offsetPx ? step.offsetPx[1] : 0,
    hold: false,
  }
}

// ── Metrics ───────────────────────────────────────────────────────────────────

export interface PathMetrics {
  /** Total absolute map rotation over the ride (deg). Lower = calmer/less stress. */
  totalRotationDeg: number
  /** Largest single-step rotation (deg) — spikes read as a jerky whip-around. */
  maxStepRotationDeg: number
  /** Final settled bearing (deg). */
  finalBearing: number
  /** Ticks the camera parked. */
  holdCount: number
  /** Largest single-step centre move (m) — guards against teleports. */
  maxStepPanM: number
  /** Mean lag: distance from the camera centre to the rider (m). */
  meanLagM: number
  /** Mean centre "jerk" (m) — 2nd difference magnitude; lower = smoother. */
  meanJerkM: number
}

export function pathMetrics(poses: ReplayPose[], points: TrackPoint[]): PathMetrics {
  let totalRotationDeg = 0
  let maxStepRotationDeg = 0
  let holdCount = 0
  let maxStepPanM = 0
  let lagSum = 0
  let jerkSum = 0
  let jerkN = 0

  for (let i = 0; i < poses.length; i++) {
    const p = poses[i]
    if (p.hold) holdCount++
    const pt = points[i]
    if (pt) lagSum += distanceM(p.lat, p.lng, pt.lat, pt.lon)
    if (i > 0) {
      const a = poses[i - 1]
      const rot = Math.abs(normalizeDeg(p.bearing - a.bearing))
      totalRotationDeg += rot
      if (rot > maxStepRotationDeg) maxStepRotationDeg = rot
      const pan = distanceM(a.lat, a.lng, p.lat, p.lng)
      if (pan > maxStepPanM) maxStepPanM = pan
    }
    if (i > 1) {
      // 2nd difference of the centre ≈ acceleration ≈ perceived jerk.
      const a = poses[i - 2], b = poses[i - 1], c = poses[i]
      const jx = (c.lng - 2 * b.lng + a.lng)
      const jy = (c.lat - 2 * b.lat + a.lat)
      // Convert the lat/lon 2nd-diff to metres at this latitude.
      const mPerDegLat = 111_320
      const mPerDegLon = mPerDegLat * Math.cos((c.lat * Math.PI) / 180)
      jerkSum += Math.hypot(jx * mPerDegLon, jy * mPerDegLat)
      jerkN++
    }
  }
  return {
    totalRotationDeg,
    maxStepRotationDeg,
    finalBearing: poses.length ? poses[poses.length - 1].bearing : 0,
    holdCount,
    maxStepPanM,
    meanLagM: poses.length ? lagSum / poses.length : 0,
    meanJerkM: jerkN ? jerkSum / jerkN : 0,
  }
}

// ── Scenario tracks ───────────────────────────────────────────────────────────
//
// Built at the equator (lat ≈ 0) so degree deltas map cleanly to compass
// headings — the camera reads headings via atan2(dLon, dLat) on raw degrees, and
// only at the equator is cos(lat) = 1, so "due east" reads as exactly 90°. This
// keeps the assertions exact without baking in projection distortion.

const M_PER_DEG = 111_320

function mkPoint(lat: number, lon: number, speedMs: number): TrackPoint {
  return { lat, lon, altM: null, hr: null, speedMs }
}

/** Walk a track from (0,0) following a per-step heading function (deg, 0=N,90=E). */
function walk(steps: number, stepM: number, headingAt: (i: number) => number, speedMs = 8): TrackPoint[] {
  const pts: TrackPoint[] = [mkPoint(0, 0, speedMs)]
  let lat = 0, lon = 0
  for (let i = 0; i < steps; i++) {
    const h = (headingAt(i) * Math.PI) / 180
    lat += (Math.cos(h) * stepM) / M_PER_DEG
    lon += (Math.sin(h) * stepM) / M_PER_DEG
    pts.push(mkPoint(lat, lon, speedMs))
  }
  return pts
}

/** Dead-straight leg on a fixed compass heading. */
export function straightTrack(headingDeg: number, lengthM = 2000, stepM = 8): TrackPoint[] {
  return walk(Math.round(lengthM / stepM), stepM, () => headingDeg)
}

/** A heading that oscillates ±amp around a base — wiggles that should NOT turn the map. */
export function wiggleTrack(baseDeg: number, lengthM = 2000, ampDeg = 30, periodM = 150, stepM = 8): TrackPoint[] {
  const n = Math.round(lengthM / stepM)
  return walk(n, stepM, i => baseDeg + ampDeg * Math.sin((2 * Math.PI * (i * stepM)) / periodM))
}

/** Two long straight legs — a sustained direction change the map SHOULD settle into. */
export function turnTrack(h1: number, h2: number, legM = 1500, stepM = 8): TrackPoint[] {
  const n = Math.round(legM / stepM)
  return walk(n * 2, stepM, i => (i < n ? h1 : h2))
}

/**
 * Lead-in straight, then a cul-de-sac that branches off 90°, out and back — the
 * camera should keep flowing on the lead-in and PARK at the junction for the
 * out-and-back. The lead-in keeps the cul-de-sac off index 0 (so the entry ease
 * doesn't mask it) and ensures the junction sees a real forward excursion.
 */
export function outAndBackTrack(headingDeg: number, leadM = 250, outM = 120, stepM = 6): TrackPoint[] {
  const lead = Math.round(leadM / stepM)
  const n = Math.round(outM / stepM)
  return walk(lead + n * 2, stepM, i => {
    if (i < lead) return headingDeg                 // lead-in
    if (i < lead + n) return headingDeg + 90        // into the cul-de-sac
    return headingDeg + 270                         // back out to the junction
  })
}

// ── Cost (for the optimizer) ────────────────────────────────────────────────

/**
 * A single scalar "how good is this feel" for one path, lower = better. It
 * encodes the stated priorities, in order: minimise map rotation (stress),
 * stay smooth (no jerk/teleport), and don't lag so far the rider leaves frame.
 * Weights are deliberately blunt — this ranks candidates, it isn't a physical
 * model.
 */
export function pathCost(m: PathMetrics): number {
  return (
    m.totalRotationDeg * 0.02 +   // rotation is the most disorienting → weight it
    m.meanJerkM * 2.0 +           // smoothness
    m.maxStepPanM * 0.5 +         // teleport guard
    m.meanLagM * 0.05             // keep the rider roughly framed
  )
}
