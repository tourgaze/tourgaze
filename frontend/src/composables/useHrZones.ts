import { computed, type Ref } from 'vue'
import type { TrackPoint, User } from '@/api/client'

export type HrZone = {
  index: 1 | 2 | 3 | 4 | 5
  name: string
  lo: number   // inclusive
  hi: number   // exclusive (open at the top for Z5)
  color: string
  pct: [number, number] // % of max (or HRR) lower / upper bound
}

const ZONE_NAMES = ['Recovery', 'Aerobic', 'Tempo', 'Threshold', 'VO2max'] as const
const ZONE_COLORS = ['#9ca3af', '#3b82f6', '#10b981', '#f59e0b', '#ef4444'] as const
const ZONE_PCTS: [number, number][] = [
  [0.50, 0.60],
  [0.60, 0.70],
  [0.70, 0.80],
  [0.80, 0.90],
  [0.90, 1.00],
]

function yearsBetween(dob: string, now: Date): number {
  const d = new Date(dob)
  const ageMs = now.getTime() - d.getTime()
  return ageMs / (365.25 * 24 * 3600 * 1000)
}

/**
 * Estimate maximum heart rate using the best-practice rules:
 *   1. User-measured value wins, always.
 *   2. Females → Gulati (206 − 0.88 × age) — derived from a large female-only study.
 *   3. Otherwise → Tanaka (208 − 0.7 × age) — modern de-facto standard, sex-independent.
 *   4. If we don't know the age, fall back to a sensible default.
 */
export function estimateMaxHr(user: User | null | undefined): number {
  if (!user) return 190
  if (user.maxHr != null) return user.maxHr
  if (!user.dateOfBirth) return 190
  const age = yearsBetween(user.dateOfBirth as unknown as string, new Date())
  if (!Number.isFinite(age) || age <= 0) return 190
  const formula = user.gender === 'female' ? (206 - 0.88 * age) : (208 - 0.7 * age)
  return Math.round(formula)
}

/**
 * Compute 5 zone boundaries.
 * If restingHr is known we use the Karvonen method (HR reserve) which is
 * meaningfully more accurate for athletes / very unfit users than %HRmax.
 */
export function computeZones(user: User | null | undefined): HrZone[] {
  const maxHr = estimateMaxHr(user)
  const restingHr = user?.restingHr ?? null

  const bpmAt = (pct: number) => {
    if (restingHr != null) {
      // Karvonen: target = HRrest + (HRmax − HRrest) × intensity
      return Math.round(restingHr + (maxHr - restingHr) * pct)
    }
    return Math.round(maxHr * pct)
  }

  return ZONE_PCTS.map((pct, i) => ({
    index: (i + 1) as HrZone['index'],
    name: ZONE_NAMES[i],
    lo: bpmAt(pct[0]),
    hi: bpmAt(pct[1]),
    color: ZONE_COLORS[i],
    pct,
  }))
}

/** Returns the zone index (1..5) for a given bpm, or null if outside Z1..Z5. */
export function zoneFor(bpm: number, zones: HrZone[]): number | null {
  if (bpm < zones[0].lo) return null
  for (const z of zones) {
    if (bpm >= z.lo && bpm < z.hi) return z.index
  }
  // Anything at or above Z5 upper bound still counts as Z5.
  return bpm >= zones[zones.length - 1].lo ? 5 : null
}

/**
 * Time spent in each zone, in seconds.
 * FIT files are typically sampled at 1 Hz, so we treat each point ≈ 1 second.
 * Returns [Z1, Z2, Z3, Z4, Z5] seconds. Below-Z1 / no-HR samples are ignored.
 */
export function timeInZone(points: TrackPoint[] | null | undefined, zones: HrZone[]): number[] {
  const out = [0, 0, 0, 0, 0]
  if (!points?.length) return out
  for (const p of points) {
    if (p.hr == null) continue
    const z = zoneFor(p.hr, zones)
    if (z != null) out[z - 1] += 1
  }
  return out
}

/** Vue composable wrapper: reactive zones + time-in-zone derived from user + points. */
export function useHrZones(user: Ref<User | null | undefined>, points: Ref<TrackPoint[] | null | undefined>) {
  const zones = computed(() => computeZones(user.value))
  const maxHr = computed(() => estimateMaxHr(user.value))
  const restingHr = computed(() => user.value?.restingHr ?? null)
  const tiz = computed(() => timeInZone(points.value, zones.value))
  const totalSec = computed(() => tiz.value.reduce((a, b) => a + b, 0))
  return { zones, maxHr, restingHr, tiz, totalSec }
}
