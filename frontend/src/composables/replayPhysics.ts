/**
 * Replay camera physics — the small, renderer-free core the cameras, the
 * simulator and the optimizer all share.
 *
 * Keeping the "mass" of the camera in one place (rather than scattered easing
 * constants) is what lets us (a) prove behaviour in a headless sim and (b) let
 * the optimizer vary the feel without touching the camera or the app. The live
 * cameras use {@link DEFAULT_TUNING}, so production behaviour is unchanged; the
 * sim/optimizer pass alternative {@link MotionTuning} objects.
 */

// ── Second-order smoothing ──────────────────────────────────────────────────

/**
 * Second-order smoothing of a scalar — the building block for the drone's
 * non-linear, drone-like motion. It eases toward a target (position low-pass via
 * `ease`, which brakes on approach → ease-out) AND low-passes its own velocity
 * (`accel` → it can't change speed instantly → ease-in). Together they give a
 * body with mass: accelerate in, coast, settle. The centre pan uses one per axis
 * and the map rotation uses one for the bearing, so all share identical physics.
 *
 * Stateful and frame-driven: call {@link advance} once per tick, {@link reset}
 * on (de)activation so a fresh shot starts at rest.
 */
export class Momentum {
  private vel = 0
  constructor(private readonly ease: number, private readonly accel: number) {}
  reset(): void { this.vel = 0 }
  /**
   * Advance one tick. `delta` is the signed gap to the target (callers pass
   * `normalizeDeg(target - pos)` for angles so the shortest way round wins).
   * Returns the new value; the caller normalises angles if needed.
   */
  advance(pos: number, delta: number): number {
    const targetVel = delta * this.ease
    this.vel += (targetVel - this.vel) * this.accel
    return pos + this.vel
  }
}

// ── Tuning ──────────────────────────────────────────────────────────────────

/**
 * The handful of physics knobs that define the camera "feel". The four follow
 * cameras (drone/follow/topdown/…) read these; they are injectable so the
 * optimizer can search the space without editing the camera. `*Ease` is the
 * braking pull toward the target (ease-out); `*Accel` low-passes the velocity so
 * speed ramps in (ease-in). Lower = heavier / laggier / calmer.
 */
export interface MotionTuning {
  centerEase: number
  centerAccel: number
  bearingEase: number
  bearingAccel: number
}

/**
 * Production feel — these are the exact values the live replay shipped with, so
 * `createCamera(id)` (no tuning arg) reproduces it byte-for-byte. The optimizer
 * exists to confirm these stay near-optimal, not to silently change them.
 */
export const DEFAULT_TUNING: MotionTuning = {
  // `*Ease` sets steady-state lag (how far the rider sits ahead of centre); keep
  // it moderate so the rider stays framed. `*Accel` sets how fast the velocity
  // itself can change — lower = heavier glide / more inertia, smoother accel and
  // braking — without adding lag. Tuned heavy for a calm, floaty drone.
  centerEase: 0.16,
  centerAccel: 0.12,
  bearingEase: 0.18,
  bearingAccel: 0.12,
}
