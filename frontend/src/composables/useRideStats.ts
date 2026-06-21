import { computed, type Ref } from 'vue'
import type { ActivitySummary, User } from '@/api/client'
import { estimateMaxHr } from '@/composables/useHrZones'

/**
 * Ride analytics computed client-side from the full-res track. FIT is
 * ~1 Hz, so we treat one point ≈ one second (same assumption the rest of the
 * app makes). Everything is best-effort and degrades gracefully when a channel
 * (HR, speed, altitude) is missing.
 */

export type StatPoint = { lat: number; lon: number; altM: number | null; hr: number | null; speedMs: number | null; distKm: number }

export type BestEffort = { label: string; seconds: number; speedKmh: number | null }
export type GradeBucket = { label: string; color: string; meters: number; pct: number }

export type RideStats = {
  hasData: boolean
  isCycling: boolean
  distanceKm: number
  movingTimeS: number
  totalTimeS: number
  avgSpeedKmh: number | null
  maxSpeedKmh: number | null
  ascentM: number
  descentM: number
  maxGradePct: number | null
  vamMh: number | null            // climbing rate, m/h, over climbing time
  avgHr: number | null
  maxHr: number | null
  trimp: number | null            // Banister training-impulse training load
  decouplingPct: number | null    // aerobic decoupling (HR:pace drift)
  avgCadence: number | null       // measured (cadence sensor)
  maxCadence: number | null
  measuredAvgPowerW: number | null // measured (power meter) — distinct from the estimate
  measuredMaxPowerW: number | null
  estAvgPowerW: number | null     // cycling only
  estNpW: number | null           // normalized power
  variabilityIndex: number | null // NP / avg
  workKj: number | null
  calories: number | null
  bestEfforts: BestEffort[]
  gradeBuckets: GradeBucket[]
}

const ASCENT_NOISE_M = 1.0
const MOVING_SPEED_MS = 0.6        // below this ≈ stopped
// Cycling power model constants (road bike, hoods). Rough but consistent.
const CRR = 0.005, RHO = 1.225, CDA = 0.32, G = 9.81, DRIVE_EFF = 0.97

const GRADE_BANDS: { label: string; color: string; lo: number; hi: number }[] = [
  { label: '↓ steep',  color: '#1d4ed8', lo: -Infinity, hi: -6 },
  { label: '↓',        color: '#3b82f6', lo: -6, hi: -2 },
  { label: 'flat',     color: '#9ca3af', lo: -2, hi: 2 },
  { label: '↑',        color: '#10b981', lo: 2, hi: 6 },
  { label: '↑↑',       color: '#f59e0b', lo: 6, hi: 10 },
  { label: '↑ steep',  color: '#ef4444', lo: 10, hi: Infinity },
]

/** Device-measured cadence/power straight off the activity (no derivation). */
function measuredChannels(a: ActivitySummary | null) {
  return {
    avgCadence: a?.avgCadence ?? null,
    maxCadence: a?.maxCadence ?? null,
    measuredAvgPowerW: a?.avgPowerW ?? null,
    measuredMaxPowerW: a?.maxPowerW ?? null,
  }
}

export function useRideStats(
  points: () => StatPoint[],
  activity: Ref<ActivitySummary | null>,
  user: Ref<User | null | undefined>,
) {
  return computed<RideStats>(() => {
    const pts = points()
    const empty: RideStats = {
      hasData: false, isCycling: false, distanceKm: 0, movingTimeS: 0, totalTimeS: 0,
      avgSpeedKmh: null, maxSpeedKmh: null, ascentM: 0, descentM: 0, maxGradePct: null, vamMh: null,
      avgHr: null, maxHr: null, trimp: null, decouplingPct: null,
      avgCadence: null, maxCadence: null, measuredAvgPowerW: null, measuredMaxPowerW: null,
      estAvgPowerW: null, estNpW: null, variabilityIndex: null, workKj: null, calories: null,
      bestEfforts: [], gradeBuckets: [],
    }
    if (!pts || pts.length < 2) return { ...empty, ...measuredChannels(activity.value) }

    const n = pts.length
    const isCycling = (activity.value?.activityType ?? '') === 'cycling'
    const mass = activity.value?.weightKg ?? user.value?.weightKg ?? 80   // rider+bike fallback

    // Per-second derived channels.
    const dt = 1                                   // 1 Hz assumption
    let ascent = 0, descent = 0, maxGrade = -Infinity, climbSeconds = 0, climbAscent = 0
    let movingSeconds = 0, maxSpeedMs = 0
    let hrSum = 0, hrCount = 0, hrMax = 0
    let trimp = 0
    let workJ = 0
    const power = new Float64Array(n)
    // Decoupling accumulators (sum HR, sum speed over each moving half).
    let hr1 = 0, sp1 = 0, c1 = 0, hr2 = 0, sp2 = 0, c2 = 0
    const half = Math.floor(n / 2)

    const maxHrEst = estimateMaxHr(user.value)
    const restHr = user.value?.restingHr ?? 60
    const female = user.value?.gender === 'female'

    for (let i = 1; i < n; i++) {
      const p = pts[i], prev = pts[i - 1]
      const horizM = (p.distKm - prev.distKm) * 1000
      const v = p.speedMs != null ? p.speedMs : horizM / dt   // m/s
      if (v > maxSpeedMs) maxSpeedMs = v
      const moving = v >= MOVING_SPEED_MS
      if (moving) movingSeconds += dt

      // Elevation + gradient.
      if (p.altM != null && prev.altM != null) {
        const climb = p.altM - prev.altM
        if (climb > ASCENT_NOISE_M) { ascent += climb; climbSeconds += dt; climbAscent += climb }
        else if (climb < -ASCENT_NOISE_M) descent += -climb
        if (horizM > 1) {
          const grade = (climb / horizM) * 100
          if (Number.isFinite(grade) && grade > maxGrade && grade < 35) maxGrade = grade
        }
      }

      // Estimated cycling power.
      const grade = horizM > 0.3 && p.altM != null && prev.altM != null ? (p.altM - prev.altM) / horizM : 0
      const pw = (CRR * mass * G * v) + (0.5 * RHO * CDA * v * v * v) + (mass * G * grade * v)
      const pClamped = Math.max(0, pw) / DRIVE_EFF
      power[i] = pClamped
      if (moving) workJ += pClamped * dt

      // Heart-rate training load (Banister TRIMP) + decoupling halves.
      if (p.hr != null) {
        hrSum += p.hr; hrCount++; if (p.hr > hrMax) hrMax = p.hr
        const hrr = Math.min(1, Math.max(0, (p.hr - restHr) / Math.max(1, maxHrEst - restHr)))
        const k = female ? 1.67 : 1.92
        const weight = female ? 0.86 : 0.64
        trimp += (dt / 60) * hrr * weight * Math.exp(k * hrr)
        if (moving && v > 0) {
          if (i < half) { hr1 += p.hr; sp1 += v; c1++ } else { hr2 += p.hr; sp2 += v; c2++ }
        }
      }
    }

    const distanceKm = pts[n - 1].distKm
    const avgSpeedKmh = movingSeconds > 0 ? (distanceKm / (movingSeconds / 3600)) : null

    // Best efforts: max distance covered in any window of T seconds → speed.
    const windows: { label: string; s: number }[] = [
      { label: '1 min', s: 60 }, { label: '5 min', s: 300 },
      { label: '20 min', s: 1200 }, { label: '60 min', s: 3600 },
    ]
    const bestEfforts: BestEffort[] = windows
      .filter(w => w.s < n)
      .map(w => {
        let best = 0
        for (let i = w.s; i < n; i++) {
          const d = pts[i].distKm - pts[i - w.s].distKm
          if (d > best) best = d
        }
        return { label: w.label, seconds: w.s, speedKmh: best > 0 ? best / (w.s / 3600) : null }
      })

    // Normalized power: 30s rolling mean, then 4th-power mean.
    let estAvgPowerW: number | null = null, estNpW: number | null = null, vi: number | null = null
    if (isCycling && movingSeconds > 0) {
      estAvgPowerW = workJ / movingSeconds
      const win = 30
      let rollSum = 0, fourthSum = 0, cnt = 0
      for (let i = 0; i < n; i++) {
        rollSum += power[i]
        if (i >= win) rollSum -= power[i - win]
        if (i >= win - 1) { const avg = rollSum / win; fourthSum += avg ** 4; cnt++ }
      }
      estNpW = cnt > 0 ? Math.round((fourthSum / cnt) ** 0.25) : null
      estAvgPowerW = Math.round(estAvgPowerW)
      vi = estNpW && estAvgPowerW ? +(estNpW / estAvgPowerW).toFixed(2) : null
    }

    // Aerobic decoupling: (HR/pace) second half vs first half.
    let decouplingPct: number | null = null
    if (c1 > 30 && c2 > 30) {
      const r1 = (hr1 / c1) / (sp1 / c1)
      const r2 = (hr2 / c2) / (sp2 / c2)
      if (r1 > 0) decouplingPct = +(((r2 - r1) / r1) * 100).toFixed(1)
    }

    // Gradient distribution by distance.
    const bandMeters = GRADE_BANDS.map(() => 0)
    for (let i = 1; i < n; i++) {
      const p = pts[i], prev = pts[i - 1]
      const horizM = (p.distKm - prev.distKm) * 1000
      if (horizM <= 0.3 || p.altM == null || prev.altM == null) continue
      const grade = ((p.altM - prev.altM) / horizM) * 100
      if (!Number.isFinite(grade)) continue
      const bi = GRADE_BANDS.findIndex(b => grade >= b.lo && grade < b.hi)
      if (bi >= 0) bandMeters[bi] += horizM
    }
    const totalBandM = bandMeters.reduce((a, b) => a + b, 0) || 1
    const gradeBuckets: GradeBucket[] = GRADE_BANDS.map((b, i) => ({
      label: b.label, color: b.color, meters: bandMeters[i], pct: (bandMeters[i] / totalBandM) * 100,
    }))

    const workKj = isCycling ? Math.round(workJ / 1000) : null

    return {
      hasData: true,
      isCycling,
      distanceKm: +distanceKm.toFixed(2),
      movingTimeS: movingSeconds,
      totalTimeS: n,
      avgSpeedKmh: avgSpeedKmh != null ? +avgSpeedKmh.toFixed(1) : null,
      maxSpeedKmh: maxSpeedMs > 0 ? +(maxSpeedMs * 3.6).toFixed(1) : null,
      ascentM: Math.round(ascent),
      descentM: Math.round(descent),
      maxGradePct: maxGrade > -Infinity ? +maxGrade.toFixed(1) : null,
      vamMh: climbSeconds > 60 ? Math.round(climbAscent / (climbSeconds / 3600)) : null,
      avgHr: hrCount > 0 ? Math.round(hrSum / hrCount) : null,
      maxHr: hrMax > 0 ? hrMax : (activity.value?.maxHr ?? null),
      trimp: trimp > 0 ? Math.round(trimp) : null,
      decouplingPct,
      ...measuredChannels(activity.value),
      estAvgPowerW,
      estNpW,
      variabilityIndex: vi,
      workKj,
      calories: workKj != null ? workKj : null,   // ~1 kcal per kJ (human efficiency cancels)
      bestEfforts,
      gradeBuckets,
    }
  })
}
