import { describe, it, expect } from 'vitest'
import {
  simulateReplayPath, pathMetrics,
  straightTrack, wiggleTrack, turnTrack, outAndBackTrack,
} from '@/composables/replaySimulator'
import { normalizeDeg } from '@/composables/replayCameras'

/**
 * Behavioural regression tests for the replay cameras, asserted on the headless
 * simulator. These lock in the feel we shipped: the drone settles to the travel
 * direction on long legs, holds its orientation through wiggles, parks on
 * dead-ends, and never teleports; follow/top-down stay north-up.
 */

const angleClose = (a: number, b: number, tolDeg: number) =>
  Math.abs(normalizeDeg(a - b)) <= tolDeg

describe('drone — settles to the travel direction (rear follow on long legs)', () => {
  for (const [name, heading] of [['north', 0], ['east', 90], ['south', 180], ['west', -90]] as const) {
    it(`straight ${name} → final bearing ≈ ${heading}°`, () => {
      const pts = straightTrack(heading, 2500)
      const path = simulateReplayPath('drone', pts)
      const m = pathMetrics(path, pts)
      expect(angleClose(m.finalBearing, heading, 12)).toBe(true)
    })
  }

  it('south is steered like every other direction (the old "just pans" bug stays dead)', () => {
    const pts = straightTrack(180, 2500)
    const m = pathMetrics(simulateReplayPath('drone', pts), pts)
    // It must actually have rotated to face south, not stayed north-up.
    expect(Math.abs(normalizeDeg(m.finalBearing - 180))).toBeLessThan(12)
  })
})

describe('drone — minimises map rotation (rotation is stress)', () => {
  it('wiggles around a heading barely rotate the map', () => {
    const pts = wiggleTrack(0, 2500, 30, 150)
    const m = pathMetrics(simulateReplayPath('drone', pts), pts)
    // A ±30° wiggle every 150 m would spin a naive per-curve camera wildly; the
    // 1 km "general direction" baseline should keep total rotation small.
    expect(m.totalRotationDeg).toBeLessThan(90)
    expect(angleClose(m.finalBearing, 0, 15)).toBe(true)
  })

  it('a sustained turn DOES re-orient (settle on long direction change)', () => {
    const pts = turnTrack(0, 90, 1800)
    const m = pathMetrics(simulateReplayPath('drone', pts), pts)
    expect(angleClose(m.finalBearing, 90, 20)).toBe(true)
    expect(m.totalRotationDeg).toBeGreaterThan(45)   // it really turned
  })
})

describe('drone — smooth, no teleports', () => {
  it('per-step pan and rotation stay bounded', () => {
    const pts = straightTrack(45, 2500)
    const m = pathMetrics(simulateReplayPath('drone', pts), pts)
    expect(m.maxStepPanM).toBeLessThan(60)        // momentum, no jumps
    expect(m.maxStepRotationDeg).toBeLessThan(15) // banks, never whips
  })
})

describe('drone — parks on dead-ends', () => {
  it('a cul-de-sac (out-and-back) triggers holds, a steady climb does not', () => {
    // Lead-in then a 90° cul-de-sac: the camera should park at the junction.
    const cul = pathMetrics(simulateReplayPath('drone', outAndBackTrack(0)), outAndBackTrack(0))
    expect(cul.holdCount).toBeGreaterThan(0)
    // A plain straight (a "climb" — slow but always progressing) must NOT hold,
    // i.e. the camera keeps flowing in the travel direction.
    const straight = straightTrack(0, 2000)
    const flow = pathMetrics(simulateReplayPath('drone', straight), straight)
    expect(flow.holdCount).toBe(0)
  })
})

describe('fixed strategies stay north-up', () => {
  for (const strat of ['follow', 'topdown'] as const) {
    it(`${strat} never rotates`, () => {
      const pts = turnTrack(0, 90, 1500)
      const path = simulateReplayPath(strat, pts)
      expect(path.every(p => Math.abs(p.bearing) < 1e-6)).toBe(true)
    })
  }

  it('top-down is flat (pitch 0)', () => {
    const pts = straightTrack(0, 800)
    const path = simulateReplayPath('topdown', pts)
    expect(path.every(p => p.pitch === 0)).toBe(true)
  })
})

describe('empty track is safe', () => {
  it('returns no poses', () => {
    expect(simulateReplayPath('drone', [])).toEqual([])
  })
})
