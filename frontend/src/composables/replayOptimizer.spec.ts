import { describe, it, expect } from 'vitest'
import { optimizeTuning } from '@/composables/replayOptimizer'

/**
 * The optimizer is a guard rail, not an auto-tuner: it confirms the shipped
 * DEFAULT_TUNING is still near the best the search can find across the scenario
 * battery. If a future change to the camera/physics makes the defaults clearly
 * sub-optimal, `improvement` drops well below 1 and this test fails — a prompt to
 * re-examine the constants rather than a silent regression.
 */
describe('replay tuning optimizer', () => {
  it('the shipped defaults are in the right neighbourhood of the search optimum', () => {
    const r = optimizeTuning()
    expect(r.evaluated).toBeGreaterThan(100)        // both phases ran
    // The defaults are hand-tuned for FEEL (deliberately heavy/inertial), which
    // the blunt cost model rates a little sub-optimal — that's expected. This
    // only catches a GROSS divergence (a future change that makes the shipped
    // feel far from anything the search would pick).
    expect(r.improvement).toBeGreaterThan(0.65)
    expect(r.improvement).toBeLessThanOrEqual(1.0)  // search never does worse
  })
})
