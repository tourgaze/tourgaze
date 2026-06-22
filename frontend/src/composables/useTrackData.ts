import { distanceKm, distanceM } from '@/lib/geo'
import { computed, ref } from 'vue'
import type { TrackPoint } from '@/api/client'

// ── Shared active index (chart hover ↔ map cursor) ──────────────────────────
export const activeIndex = ref<number | null>(null)

// ── Break detection ─────────────────────────────────────────────────────────
/**
 * One contiguous span the rider was effectively stopped — coffee break,
 * summit photo, regroup at a junction. Replay should skip over these
 * because watching a stationary marker for 4 minutes is the opposite of
 * cinematic.
 *
 * `fromIdx` is the first slow point; `toIdx` is the first point AFTER the
 * break (i.e. where the rider resumed moving). `points` is the count so the
 * UI can show "~3 min skipped" at 1 Hz FIT recording.
 */
export type BreakSpan = {
  fromIdx: number
  toIdx: number
  points: number
}

const BREAK_SPEED_THRESHOLD_MS = 0.6   // ≈ 2 km/h — distinguishes stops from coast/granny climb
// Only spans ≥ 5 minutes count as breaks. Anything shorter (stoplights,
// summit photo, regroup at a junction, even a short coffee top-up) reads as
// part of the ride and replay just plays through it. At 1 Hz FIT recording
// 300 points ≈ 5 min wall clock; sparser sample rates would over-count but
// that's a rounding error we accept.
const MIN_BREAK_POINTS = 300
// A real stop (coffee, summit, regroup) is rarely dead-still on the GPS: the
// rider shuffles, the bike is repositioned, the unit jitters — so the speed
// trace flickers above the threshold for a sample or two every so often. Without
// debounce each flicker splits one long pause into many short runs, none of
// which reaches MIN_BREAK_POINTS, and the break is never detected (the km 35-36
// "stopped for 12 min but never skipped" bug). So the break only ENDS once the
// rider has been moving for this many CONSECUTIVE points — a sustained
// resumption, not a blip. ~12 s at 1 Hz FIT.
const RESUME_HOLD_POINTS = 12

export function detectBreaks(points: TrackPoint[]): BreakSpan[] {
  const breaks: BreakSpan[] = []
  if (!points.length) return breaks

  const isSlow = (i: number): boolean => {
    const speedAvailable = points[i].speedMs != null
    return speedAvailable
      ? (points[i].speedMs as number) < BREAK_SPEED_THRESHOLD_MS
      // Fallback when the FIT didn't record speed (older devices): use
      // sub-metre haversine to the previous point as a proxy.
      : i > 0 && distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon) < 1.0
  }

  let inBreak = false
  let breakStart = 0
  let movingRun = 0   // consecutive moving points while inside a break
  const close = (endIdx: number) => {
    const span = endIdx - breakStart
    if (span >= MIN_BREAK_POINTS) breaks.push({ fromIdx: breakStart, toIdx: endIdx, points: span })
    inBreak = false
    movingRun = 0
  }

  for (let i = 0; i < points.length; i++) {
    const slow = isSlow(i)
    if (!inBreak) {
      if (slow) { inBreak = true; breakStart = i; movingRun = 0 }
      continue
    }
    // Inside a break: a single slow sample resets the resume counter, so brief
    // movement blips are absorbed. Only a sustained moving run ends the break —
    // and it ends at the FIRST point of that run (where the rider really left).
    if (slow) { movingRun = 0 }
    else if (++movingRun >= RESUME_HOLD_POINTS) { close(i - movingRun + 1) }
  }
  // A break that runs to the very end of the recording is usually the rider
  // forgetting to stop the recording in the parking lot — surface it too.
  if (inBreak) close(points.length - 1)
  return breaks
}

// ── Main composable ──────────────────────────────────────────────────────────
export function useTrackData(
  rawPoints: () => TrackPoint[] | undefined,
  chartReducedPoints?: () => TrackPoint[] | undefined,
) {
  // Augment raw points with cumulative distance
  const withDist = computed(() => {
    const pts = rawPoints()
    if (!pts?.length) return []
    let dist = 0
    return pts.map((p, i) => {
      if (i > 0) dist += distanceKm(pts[i - 1].lat, pts[i - 1].lon, p.lat, p.lon)
      return { ...p, distKm: +dist.toFixed(4) }
    })
  })

  // Prefer server-reduced chart points (with rawIdx); fall back to full track.
  const chartPoints = computed(() => {
    const full = withDist.value
    if (!full.length) return []

    const reduced = chartReducedPoints?.()
    if (!reduced?.length) {
      return full.map((p, i) => ({ ...p, _rawIdx: i }))
    }

    return reduced.map((p, i) => {
      const byRaw = p.rawIdx != null ? full[p.rawIdx] : undefined
      const fallbackRaw = Math.round((i * (full.length - 1)) / Math.max(1, reduced.length - 1))
      const rawIdx = byRaw ? (p.rawIdx as number) : fallbackRaw
      const distKm = byRaw ? byRaw.distKm : (full[rawIdx]?.distKm ?? 0)
      return {
        ...p,
        distKm,
        _rawIdx: rawIdx,
      }
    })
  })

  // Map full index → chart index (nearest kept index)
  const fullToChart = computed(() => {
    const map = new Map<number, number>()
    chartPoints.value.forEach((p, ci) => map.set(p._rawIdx, ci))
    return map
  })

  // Active point in chart-index space
  const activeChartIndex = computed(() => {
    if (activeIndex.value == null) return null
    return fullToChart.value.get(activeIndex.value) ?? null
  })

  // Per-track break list, computed once when the points load.
  const breaks = computed<BreakSpan[]>(() => {
    const pts = rawPoints()
    return pts?.length ? detectBreaks(pts) : []
  })

  return { withDist, chartPoints, activeIndex, activeChartIndex, breaks }
}
