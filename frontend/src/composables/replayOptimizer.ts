/**
 * Two-phase tuning optimizer for the replay camera feel.
 *
 * This is a dev/verification tool, not production code: it searches {@link
 * MotionTuning} space to find the physics constants that minimise {@link
 * pathCost} across a set of scenario tracks, then reports how the shipped
 * {@link DEFAULT_TUNING} compares. The point isn't to auto-change the feel
 * (the current feel is the one we like) — it's a guard rail: if a future edit
 * pushes the defaults far from the optimum, a test catches it.
 *
 * "Two phase" keeps it cheap and good enough without a real optimiser library:
 *   phase 1 — a coarse grid finds the right neighbourhood,
 *   phase 2 — a fine grid around that winner refines it.
 * 3^4 = 81 evaluations per phase; deterministic, no randomness.
 */

import type { TrackPoint } from '@/api/client'
import type { ReplayStrategy } from '@/composables/replayStrategies'
import { DEFAULT_TUNING, type MotionTuning } from '@/composables/replayPhysics'
import {
  simulateReplayPath, pathMetrics, pathCost,
  straightTrack, wiggleTrack, turnTrack,
} from '@/composables/replaySimulator'

export interface Scenario {
  name: string
  strategy: ReplayStrategy
  points: TrackPoint[]
}

export interface OptimizeResult {
  best: MotionTuning
  cost: number
  baseline: { tuning: MotionTuning; cost: number }
  /** best.cost / baseline.cost — ≤ 1 means the search found nothing much better. */
  improvement: number
  evaluated: number
}

/** A small, representative battery: a wiggle that shouldn't turn, a turn that should. */
export function defaultScenarios(): Scenario[] {
  return [
    { name: 'straight-N', strategy: 'drone', points: straightTrack(0) },
    { name: 'straight-S', strategy: 'drone', points: straightTrack(180) },
    { name: 'wiggle-N', strategy: 'drone', points: wiggleTrack(0) },
    { name: 'turn-N-E', strategy: 'drone', points: turnTrack(0, 90) },
  ]
}

function totalCost(tuning: MotionTuning, scenarios: Scenario[]): number {
  let c = 0
  for (const s of scenarios) {
    const path = simulateReplayPath(s.strategy, s.points, { tuning })
    c += pathCost(pathMetrics(path, s.points))
  }
  return c
}

interface Grid {
  centerEase: number[]
  centerAccel: number[]
  bearingEase: number[]
  bearingAccel: number[]
}

function searchGrid(grid: Grid, scenarios: Scenario[]): { tuning: MotionTuning; cost: number; evaluated: number } {
  let best: MotionTuning = DEFAULT_TUNING
  let bestCost = Infinity
  let evaluated = 0
  for (const centerEase of grid.centerEase)
    for (const centerAccel of grid.centerAccel)
      for (const bearingEase of grid.bearingEase)
        for (const bearingAccel of grid.bearingAccel) {
          const tuning: MotionTuning = { centerEase, centerAccel, bearingEase, bearingAccel }
          const cost = totalCost(tuning, scenarios)
          evaluated++
          if (cost < bestCost) { bestCost = cost; best = tuning }
        }
  return { tuning: best, cost: bestCost, evaluated }
}

/** Three points {v-d, v, v+d}, clamped to a sane (0, 1] easing range. */
function around(v: number, d: number): number[] {
  return [Math.max(0.02, v - d), v, Math.min(1, v + d)]
}

export function optimizeTuning(scenarios: Scenario[] = defaultScenarios()): OptimizeResult {
  // Phase 1 — coarse: locate the neighbourhood.
  const coarse = searchGrid({
    centerEase: [0.08, 0.16, 0.24],
    centerAccel: [0.12, 0.22, 0.34],
    bearingEase: [0.10, 0.18, 0.28],
    bearingAccel: [0.10, 0.18, 0.28],
  }, scenarios)

  // Phase 2 — fine: refine around the coarse winner.
  const fine = searchGrid({
    centerEase: around(coarse.tuning.centerEase, 0.04),
    centerAccel: around(coarse.tuning.centerAccel, 0.06),
    bearingEase: around(coarse.tuning.bearingEase, 0.05),
    bearingAccel: around(coarse.tuning.bearingAccel, 0.05),
  }, scenarios)

  const winner = fine.cost <= coarse.cost ? fine : coarse
  const baselineCost = totalCost(DEFAULT_TUNING, scenarios)
  return {
    best: winner.tuning,
    cost: winner.cost,
    baseline: { tuning: DEFAULT_TUNING, cost: baselineCost },
    improvement: winner.cost / baselineCost,
    evaluated: coarse.evaluated + fine.evaluated,
  }
}
