import { distanceM } from '@/lib/geo'
/**
 * Replay camera strategy abstraction.
 *
 * Each camera profile (chase, drone, hollywood, helicopter, follow, topdown)
 * is a class implementing {@link ReplayCamera}. The dispatcher in
 * `ActivityMap.vue` calls `step(ctx)` per rAF tick during playback and
 * applies the returned {@link CameraStep} to MapLibre via `jumpTo` / `easeTo`.
 * Cameras own their own per-track state (helicopter shots, dead-end anchors)
 * and never touch MapLibre directly — that keeps them testable as pure
 * functions and makes the dispatcher in ActivityMap a thin translation layer.
 *
 * To add a new strategy:
 *   1. Add the id to {@link ReplayStrategy}, push a config in REPLAY_STRATEGIES.
 *   2. Either reuse {@link PerFrameFollowCamera} (most cases) or write a new
 *      class implementing {@link ReplayCamera}.
 *   3. Register it in {@link createCamera}.
 *
 * No code in ActivityMap.vue should know about the new strategy.
 */

import type { TrackPoint } from '@/api/client'
import {
  REPLAY_STRATEGY_MAP,
  planHeliShots, findHeliShotIdx,
  type ReplayStrategy, type ReplayStrategyConfig,
  type SmoothedBearings, type HeliShot,
} from '@/composables/replayStrategies'
import { Momentum, DEFAULT_TUNING, type MotionTuning } from '@/composables/replayPhysics'

// ── Public types ───────────────────────────────────────────────────────────

/**
 * Camera move emitted by a strategy each tick. The dispatcher translates
 * these into MapLibre calls — strategies stay agnostic of the renderer.
 */
export type CameraStep =
  /** Do nothing this frame. Used while parked in a scene / dead-end. */
  | { kind: 'hold' }
  /** Instant move, no animation. Per-frame follow strategies use this. */
  | {
      kind: 'jump'
      center: [number, number]
      zoom?: number
      pitch?: number
      bearing?: number
      /** Pixel offset baked into center by the dispatcher (jumpTo has no offset param). */
      offsetPx?: [number, number]
    }
  /** Animated move, used by scene-cuts (helicopter) and entry transitions. */
  | {
      kind: 'ease'
      center: [number, number]
      zoom?: number
      pitch?: number
      bearing?: number
      offsetPx?: [number, number]
      durationMs: number
      /** If set, block the per-frame step loop for this many ms while the ease runs. */
      lockMs?: number
    }

export interface TrackContext {
  points: TrackPoint[]
  bearings: SmoothedBearings | null
  viewportPxW: number
  viewportPxH: number
}

export interface StepContext extends TrackContext {
  idx: number
  hoverCoords: [number, number] | null
  is3D: boolean
  currentBearing: number
  currentZoom: number
}

export interface ReplayCamera {
  readonly id: ReplayStrategy
  /** Recompute per-track state (heli shots, etc). Called on track / viewport / strategy change. */
  onTrackChange?(ctx: TrackContext): void
  /** Called once when this camera becomes active (play start, strategy switch). */
  onActivate?(ctx: StepContext): CameraStep | null
  /** Called once when this camera is replaced or playback stops. */
  onDeactivate?(): void
  /** Called every rAF tick during playback. Return the desired camera move. */
  step(ctx: StepContext): CameraStep | null
}

// ── Angle helpers ──────────────────────────────────────────────────────────

function normalizeDeg(d: number): number {
  let out = d % 360
  if (out > 180) out -= 360
  if (out < -180) out += 360
  return out
}
export function lerpAngleDeg(from: number, to: number, alpha: number): number {
  const delta = normalizeDeg(to - from)
  return normalizeDeg(from + delta * alpha)
}
/** Shortest signed angular gap `to - from`, in (-180, 180]. Exported for the sim. */
export { normalizeDeg }

// ── Per-frame follow (chase, drone, hollywood, follow, topdown) ────────────

/**
 * Generic per-frame follow camera driven entirely by the strategy's config
 * record (no per-strategy code). Covers chase, drone, hollywood, follow,
 * topdown — anything where the camera tracks the rider every frame.
 *
 * Owns the dead-end hold anchor so out-and-back side streets don't cause
 * the camera to wobble. {@link HelicopterSceneCamera} has separate scene
 * logic and skips this.
 */
/** Release a dead-end hold once the rider gets this far from the park anchor. */
const HOLD_RELEASE_M = 150
/**
 * Camera "deadzone" (a game-style camera box): the rider can roam freely within
 * a margin of the current centre before the camera bothers to move, and even
 * then it only catches up to the box edge. This kills the nervous micro-panning
 * on roundabouts / switchbacks and means a dead-end shorter than the margin
 * needs *no* camera movement at all (ride in, come back — map stays put).
 *
 * The margin is VIEWPORT-RELATIVE — a fraction of the smaller on-screen
 * dimension — so it scales with zoom and applies equally to drone (tight) and
 * follow / top-down (wide): "give it a margin" reads the same at every scale.
 * A small metre floor keeps it sane when extremely zoomed in.
 */
const DEADZONE_VIEWPORT_FRAC = 0.08
const DEADZONE_MIN_M = 10

export class PerFrameFollowCamera implements ReplayCamera {
  private lastHoldAnchor = -1
  // Once a hold has been released within the current contiguous hold region we
  // don't re-park until holdHint drops back to 0 — otherwise a long slow stretch
  // (which the holdHint heuristic flags wholesale) would re-anchor every frame
  // and freeze the camera while the rider rides away.
  private holdReleased = false
  // Last committed camera centre, for the deadzone follow below.
  private lastCenter: [number, number] | null = null
  // Momentum smoothers — one per centre axis + one for the bearing — so the pan
  // and the rotation both carry speed (accelerate in, coast, settle) instead of
  // snapping to each tick's eased target. Built from the injected tuning so the
  // simulator/optimizer can vary the feel; defaults reproduce the shipped feel.
  private readonly mx: Momentum
  private readonly my: Momentum
  private readonly mBearing: Momentum

  constructor(
    public readonly id: ReplayStrategy,
    public readonly config: ReplayStrategyConfig,
    tuning: MotionTuning = DEFAULT_TUNING,
  ) {
    this.mx = new Momentum(tuning.centerEase, tuning.centerAccel)
    this.my = new Momentum(tuning.centerEase, tuning.centerAccel)
    this.mBearing = new Momentum(tuning.bearingEase, tuning.bearingAccel)
  }

  onActivate(ctx: StepContext): CameraStep | null {
    // One-shot ease into the strategy's pose so play-start doesn't snap.
    const pt = ctx.points[ctx.idx]
    if (!pt) return null
    const aheadIdx = Math.min(ctx.points.length - 1, ctx.idx + this.config.lookAhead)
    const aheadPt = ctx.points[aheadIdx] ?? pt
    const center: [number, number] = [
      pt.lon * (1 - this.config.centerBias) + aheadPt.lon * this.config.centerBias,
      pt.lat * (1 - this.config.centerBias) + aheadPt.lat * this.config.centerBias,
    ]
    const pitch = ctx.is3D ? this.config.pitch : this.config.pitchFlat
    // Ease to THIS strategy's own zoom on (re)entry — including zooming OUT when
    // switching from a tighter strategy (e.g. drone 15.5 → a wider one). Using
    // Math.max here would pin the old, closer zoom and the map would never widen.
    const zoom = this.config.minZoom
    const offsetY = Math.round(ctx.viewportPxH * this.config.offsetY)
    // ALWAYS set bearing explicitly. MapLibre's easeTo treats an undefined
    // bearing as "interpolate from current to current" — and combined with
    // a non-trivial `offset` param the internal animation engine ends up
    // writing NaN into transform.bearing for one frame, which permanently
    // corrupts the projection matrix (`map.project()` returns (NaN, NaN)
    // for every subsequent call). The marker can no longer paint at the
    // new lngLat. Explicit bearing avoids the entire undefined codepath.
    const bearing = ctx.is3D ? ctx.currentBearing : 0
    this.lastCenter = center   // seed the deadzone from the eased-in pose
    this.mx.reset(); this.my.reset(); this.mBearing.reset()   // start at rest
    return {
      kind: 'ease',
      center, zoom, pitch, bearing,
      offsetPx: [0, offsetY],
      durationMs: 400, lockMs: 400,
    }
  }

  onDeactivate(): void {
    this.lastHoldAnchor = -1; this.holdReleased = false; this.lastCenter = null
    this.mx.reset(); this.my.reset(); this.mBearing.reset()
  }

  step(ctx: StepContext): CameraStep | null {
    const pt = ctx.points[ctx.idx]
    if (!pt) return null

    // Anchor on the marker's interpolated coords (lockstep with marker, no
    // visible vibration at fast replay) and bias toward look-ahead.
    const here = ctx.hoverCoords ?? [pt.lon, pt.lat]

    // Dead-end / out-and-back hold: park the camera while the rider loops a
    // short cul-de-sac so the view doesn't zoom in-and-out on side streets.
    // CRITICAL: only stay parked while the rider remains within
    // HOLD_RELEASE_M of the park anchor. holdHint flags whole slow/dense
    // stretches (a 1 Hz log of any sub-~15 km/h section), not just real
    // dead-ends — without the distance release the camera would park on the
    // first hold frame and never follow again, stranding the rider off-screen
    // (the bug that made drone / hollywood look completely broken).
    const isHold = ctx.bearings?.holdHint?.[ctx.idx] === 1
    if (isHold) {
      if (this.lastHoldAnchor < 0 && !this.holdReleased) this.lastHoldAnchor = ctx.idx
      if (this.lastHoldAnchor >= 0) {
        const a = ctx.points[this.lastHoldAnchor]
        if (a && distanceM(here[1], here[0], a.lat, a.lon) < HOLD_RELEASE_M) {
          return { kind: 'hold' }
        }
        // Rider has left the anchor → stop parking for the rest of this region.
        this.lastHoldAnchor = -1
        this.holdReleased = true
      }
    } else {
      this.lastHoldAnchor = -1
      this.holdReleased = false
    }
    const aheadIdx = Math.min(ctx.points.length - 1, ctx.idx + this.config.lookAhead)
    const aheadPt = ctx.points[aheadIdx] ?? pt
    const desired: [number, number] = [
      here[0] * (1 - this.config.centerBias) + aheadPt.lon * this.config.centerBias,
      here[1] * (1 - this.config.centerBias) + aheadPt.lat * this.config.centerBias,
    ]

    // Deadzone follow: only chase the rider once they've drifted past the
    // viewport-relative margin from the committed centre, and then only far
    // enough to park them back on the box edge. Inside the box the centre is
    // unchanged — no per-frame micro-pans — which kills the nervous orbiting on
    // roundabouts / switchbacks and lets short dead-ends resolve with no
    // movement. `held` also freezes the bearing so the map doesn't rotate in
    // place while parked.
    const mpp = 156543.03392 * Math.cos((pt.lat * Math.PI) / 180) / Math.pow(2, ctx.currentZoom)
    const deadzoneFrac = this.config.deadzoneFrac ?? DEADZONE_VIEWPORT_FRAC
    // 0 = no deadzone → the camera locks onto the rider every frame (chase-cam).
    const deadzoneM = deadzoneFrac <= 0 ? 0
      : Math.max(DEADZONE_MIN_M, deadzoneFrac * Math.min(ctx.viewportPxW, ctx.viewportPxH) * mpp)
    let target = desired
    if (this.lastCenter) {
      const d = distanceM(this.lastCenter[1], this.lastCenter[0], desired[1], desired[0])
      if (d < deadzoneM) {
        target = this.lastCenter
      } else {
        const f = (d - deadzoneM) / d   // pull only to the zone edge
        target = [
          this.lastCenter[0] + (desired[0] - this.lastCenter[0]) * f,
          this.lastCenter[1] + (desired[1] - this.lastCenter[1]) * f,
        ]
      }
    }
    // Momentum glide toward the target: the centre carries speed, so it
    // accelerates into a move and eases out of it — a drone with mass, not a
    // constant-rate slide or a teleport. (See {@link Momentum}.)
    const center: [number, number] = this.lastCenter
      ? [
          this.mx.advance(this.lastCenter[0], target[0] - this.lastCenter[0]),
          this.my.advance(this.lastCenter[1], target[1] - this.lastCenter[1]),
        ]
      : target
    this.lastCenter = center

    // Bearing: the drone steers by the GENERAL travel direction — the heading to
    // a point ~1 km down the track (see SmoothedBearings.general), NOT the tight
    // per-curve heading. The long baseline is the whole trick: sub-kilometre
    // wiggles, roundabouts and switchbacks barely move it, so the map holds a
    // steady orientation through them (minimal, low-stress rotation); only a
    // sustained change in overall direction swings it. We then momentum-ease the
    // shown bearing toward that general heading, so it settles into long
    // direction changes (rider followed from the rear on each leg) and banks
    // smoothly in/out rather than snapping. This also keeps the old "south just
    // pans, never turns" bug dead — every direction is steered the same way.
    let bearing = ctx.currentBearing
    if (this.config.bearingMode === 'smoothed') {
      const gen = ctx.bearings?.general
      const general = gen && gen.length ? gen[Math.min(ctx.idx, gen.length - 1)] : ctx.currentBearing
      bearing = normalizeDeg(this.mBearing.advance(bearing, normalizeDeg(general - bearing)))
    } else {
      bearing = 0   // fixed-bearing strategies stay north-up
    }

    const pitch = ctx.is3D ? this.config.pitch : this.config.pitchFlat
    const zoom = Math.max(ctx.currentZoom, this.config.minZoom)
    const offsetY = Math.round(ctx.viewportPxH * this.config.offsetY)

    return {
      kind: 'jump',
      center, zoom, pitch, bearing,
      offsetPx: [0, offsetY],
    }
  }
}

// ── Helicopter (cinematic pre-planned scenes) ──────────────────────────────

/**
 * TdF-broadcast-style camera: pre-plans the route as a handful of static
 * shots and only moves on shot boundaries. The rider drifts across the
 * stationary viewport while inside a shot; crossing into the next shot
 * fires a 2 s cinematic ease (centre + zoom + bearing simultaneously).
 *
 * Shots come from {@link planHeliShots} — distance-chunked, adaptive zoom,
 * curve-outside lateral offset.
 */
export class HelicopterSceneCamera implements ReplayCamera {
  readonly id: ReplayStrategy = 'helicopter'
  private shots: HeliShot[] = []
  private currentShotIdx = -1
  private static readonly PAN_MS = 2000

  constructor(public readonly config: ReplayStrategyConfig) {}

  onTrackChange(ctx: TrackContext): void {
    if (!ctx.points.length || !ctx.bearings) {
      this.shots = []
      this.currentShotIdx = -1
      return
    }
    this.shots = planHeliShots(
      ctx.points,
      ctx.viewportPxW, ctx.viewportPxH,
      this.config.minZoom,
      ctx.bearings.byStrategy['helicopter'],
    )
    this.currentShotIdx = -1
  }

  onActivate(_ctx: StepContext): CameraStep | null {
    // Skip the regular entry-snap: the first step() call will issue the
    // cinematic ease into the first shot directly, with the full 2 s lock.
    // (Double-easing would visibly fight itself for ~600 ms.)
    return null
  }

  onDeactivate(): void { this.currentShotIdx = -1 }

  step(ctx: StepContext): CameraStep | null {
    if (!this.shots.length) return null
    const shotIdx = findHeliShotIdx(this.shots, ctx.idx)
    if (shotIdx === this.currentShotIdx) return { kind: 'hold' }
    this.currentShotIdx = shotIdx
    const shot = this.shots[shotIdx]
    return {
      kind: 'ease',
      center: [shot.centerLng, shot.centerLat],
      zoom: shot.zoom,
      pitch: ctx.is3D ? this.config.pitch : this.config.pitchFlat,
      bearing: ctx.is3D ? shot.bearing : 0,
      durationMs: HelicopterSceneCamera.PAN_MS,
      lockMs: HelicopterSceneCamera.PAN_MS,
    }
  }
}

// ── Factory ────────────────────────────────────────────────────────────────

/**
 * Single point of registration — adding a new strategy means adding one
 * branch here. Returns the camera instance the dispatcher will drive.
 */
export function createCamera(id: ReplayStrategy, tuning: MotionTuning = DEFAULT_TUNING): ReplayCamera {
  const config = REPLAY_STRATEGY_MAP[id]
  switch (id) {
    case 'helicopter':
      return new HelicopterSceneCamera(config)
    case 'drone':
    case 'follow':
    case 'topdown':
    default:
      return new PerFrameFollowCamera(id, config, tuning)
  }
}
