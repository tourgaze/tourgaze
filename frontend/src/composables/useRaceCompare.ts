import { ref, computed, watch, type Ref, type ComputedRef } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { getSimilarRides, getTrack, type ActivitySummary, type TrackPoint } from '@/api/client'
import { activeIndex } from '@/composables/useTrackData'
import { distanceM } from '@/lib/geo'

/**
 * In-place multi-rider race ("ghost chase"), extracted from ActivityViewer so
 * the viewer stays a thin shell. Tick rows in the Compare tab to add rides as
 * coloured ghosts on THIS ride's map + replay (no navigation). Press Play and
 * every marker advances together by sample index (≈ elapsed time at ~1 Hz);
 * the inline bars show who's ahead/behind by how many metres + current HR.
 *
 * Everything is position-by-distance: the frontend has no per-point timestamp,
 * so the replay's sample index is the shared clock and cumulative haversine
 * distance is the yardstick.
 */

export interface RaceCompareDeps {
  /** The ride currently open in the viewer ("You"). */
  activityId: ComputedRef<string | null> | Ref<string | null>
  /** This ride's full track (from the viewer's track query). */
  rawPoints: Ref<TrackPoint[] | undefined>
  /** The open activity, for its display name. */
  activity: ComputedRef<ActivitySummary | null>
  /** Replay play/pause state — collapses the table to just the racers. */
  isPlaying: Ref<boolean>
}

/** A track point enriched with cumulative distance from the start. */
type Aug = { lat: number; lon: number; hr: number | null; distM: number }

function augment(pts: { lat: number; lon: number; hr: number | null }[]): Aug[] {
  const out: Aug[] = []; let d = 0
  for (let i = 0; i < pts.length; i++) {
    if (i > 0) d += distanceM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
    out.push({ lat: pts[i].lat, lon: pts[i].lon, hr: pts[i].hr, distM: d })
  }
  return out
}

// Distinct, high-contrast palette — all clearly different from the primary's
// blue (#3b82f6) and from each other.
const COMPARE_COLORS = ['#f97316', '#a855f7', '#14b8a6', '#eab308', '#ec4899', '#84cc16', '#06b6d4', '#ef4444']
const PRIMARY_COLOR = '#3b82f6'
const RACE_MAX_HALF = 44   // % of half-cell a maxed-out inline bar fills

export type Racer = {
  id: string; name: string; color: string; you: boolean
  distM: number; hr: number | null; lat: number; lon: number
  gapM: number; rank: number
}

export function useRaceCompare(deps: RaceCompareDeps) {
  const { activityId, rawPoints, activity, isPlaying } = deps
  const router = useRouter()

  // Similar rides (same route by GPS overlap or shared tag).
  const { data: similarRides } = useQuery({
    queryKey: computed(() => ['similar', activityId.value]),
    queryFn: () => getSimilarRides(activityId.value!),
    enabled: computed(() => activityId.value != null),
    staleTime: 5 * 60 * 1000,
  })

  const selectedCompareIds = ref<string[]>([])
  const compareData = ref<Map<string, Aug[]>>(new Map())   // id → cumulative-distance track
  // Colour is assigned once when a ride is picked and kept until it's dropped, so
  // each track's marker, line and inline bar stay the same distinctive hue.
  const colorByRide = ref<Map<string, string>>(new Map())
  const primaryAug = computed(() => augment(rawPoints.value ?? []))
  const isComparing = computed(() => selectedCompareIds.value.length > 0)

  function colorOf(id: string): string { return colorByRide.value.get(id) ?? '#9ca3af' }
  function nextFreeColor(): string {
    const used = new Set(colorByRide.value.values())
    return COMPARE_COLORS.find(c => !used.has(c)) ?? COMPARE_COLORS[colorByRide.value.size % COMPARE_COLORS.length]
  }
  function toggleCompare(id: string) {
    const next = new Map(colorByRide.value)
    const i = selectedCompareIds.value.indexOf(id)
    if (i >= 0) { selectedCompareIds.value.splice(i, 1); next.delete(id) }
    else { selectedCompareIds.value.push(id); next.set(id, nextFreeColor()) }
    colorByRide.value = next
  }
  function clearCompare() { selectedCompareIds.value = []; colorByRide.value = new Map() }
  function openCompareDetails() {
    const other = selectedCompareIds.value[0]
    if (activityId.value && other) router.push(`/compare/${activityId.value}/${other}`)
  }

  // Lazily load (+ cache) each selected ride's track; prune deselected ones.
  watch(selectedCompareIds, async (ids) => {
    const pruned = new Map(compareData.value)
    for (const id of [...pruned.keys()]) if (!ids.includes(id)) pruned.delete(id)
    compareData.value = pruned
    for (const id of ids) {
      if (compareData.value.has(id)) continue
      try {
        const t = await getTrack(id)
        const m = new Map(compareData.value); m.set(id, augment(t)); compareData.value = m
      } catch { /* skip rides whose track fails to load */ }
    }
  }, { deep: true })
  // Switching rides clears the race.
  watch(activityId, () => { selectedCompareIds.value = []; colorByRide.value = new Map() })

  // Live leaderboard: every racer's position at the current replay index.
  const racers = computed<Racer[]>(() => {
    const i = activeIndex.value ?? 0
    const rows: Omit<Racer, 'gapM' | 'rank'>[] = []
    const pa = primaryAug.value
    if (pa.length) {
      const p = pa[Math.min(i, pa.length - 1)]
      rows.push({ id: activityId.value ?? 'you', name: activity.value?.name ?? 'This ride', color: PRIMARY_COLOR, you: true, distM: p.distM, hr: p.hr, lat: p.lat, lon: p.lon })
    }
    for (const id of selectedCompareIds.value) {
      const g = compareData.value.get(id)
      if (!g || !g.length) continue
      const gp = g[Math.min(i, g.length - 1)]
      rows.push({ id, name: similarRides.value?.find(s => s.id === id)?.name ?? 'ride', color: colorOf(id), you: false, distM: gp.distM, hr: gp.hr, lat: gp.lat, lon: gp.lon })
    }
    if (!rows.length) return []
    const leadDist = Math.max(...rows.map(r => r.distM))
    return [...rows].sort((a, b) => b.distM - a.distM)
      .map((r, idx) => ({ ...r, gapM: r.distM - leadDist, rank: idx + 1 }))
  })

  // Highest sample index across the selected ghost tracks — so replay can run
  // until the LAST (longest) runner finishes, not just until "You" do.
  const raceMaxIndex = computed(() => {
    let m = 0
    for (const id of selectedCompareIds.value) {
      const g = compareData.value.get(id)
      if (g) m = Math.max(m, g.length - 1)
    }
    return m
  })

  const compareLines = computed(() =>
    selectedCompareIds.value
      .map(id => { const g = compareData.value.get(id); return g ? { id, color: colorOf(id), points: g.map(p => ({ lat: p.lat, lon: p.lon })) } : null })
      .filter((x): x is { id: string; color: string; points: { lat: number; lon: number }[] } => x != null))
  const compareCursors = computed(() =>
    racers.value.filter(r => !r.you).map(r => ({ id: r.id, color: r.color, lat: r.lat, lon: r.lon })))
  // While actually replaying, collapse the table to just the racers; when paused
  // or idle show the full list so you can pick rides.
  const compareRows = computed(() =>
    (isPlaying.value && isComparing.value)
      ? (similarRides.value ?? []).filter(s => selectedCompareIds.value.includes(s.id))
      : (similarRides.value ?? []))

  // ── Inline race bar: everything relative to YOU (the cell's centre line).
  // Ahead grows the bar right, behind grows it left; length scales to the field.
  const youDistM = computed(() => racers.value.find(r => r.you)?.distM ?? 0)
  function racerOf(id: string) { return racers.value.find(r => r.id === id) }
  function gapToYou(id: string): number { const r = racerOf(id); return r ? r.distM - youDistM.value : 0 }
  function hrOf(id: string): number | null { return racerOf(id)?.hr ?? null }
  const raceSpread = computed(() => Math.max(1, ...selectedCompareIds.value.map(id => Math.abs(gapToYou(id)))))
  function aheadOf(id: string): boolean { return gapToYou(id) > 0.5 }
  function behindOf(id: string): boolean { return gapToYou(id) < -0.5 }
  function barMagPct(id: string): number {
    return Math.min(RACE_MAX_HALF, (Math.abs(gapToYou(id)) / raceSpread.value) * RACE_MAX_HALF)
  }
  function gapVsYouText(id: string): string {
    const g = gapToYou(id)
    if (Math.abs(g) < 0.5) return 'even'
    const a = Math.abs(g), s = g > 0 ? '+' : '−'
    return a >= 1000 ? `${s}${(a / 1000).toFixed(1)} km` : `${s}${Math.round(a)} m`
  }
  // Gap distance → bar colour, traffic-light style: green (together) → amber
  // (pulling apart) → red (blown apart). Three clearly distinct hues — the old
  // ramp had an orange step that was hard to tell from the red.
  function gapColor(id: string): string {
    const a = Math.abs(gapToYou(id))
    if (a < 30) return 'rgb(16 185 129)'    // emerald — neck-and-neck
    if (a < 150) return 'rgb(234 179 8)'    // amber — pulling apart
    return 'rgb(239 68 68)'                 // red — dropped / way ahead
  }
  // Heart rate → colour (rough zones; keeps the rider's own colour for identity).
  function hrColor(hr: number | null): string {
    if (hr == null) return 'rgb(148 163 184)'      // slate — no HR
    if (hr < 120) return 'rgb(16 185 129)'         // emerald — easy
    if (hr < 150) return 'rgb(234 179 8)'          // amber — tempo
    if (hr < 170) return 'rgb(249 115 22)'         // orange — threshold
    return 'rgb(239 68 68)'                        // red — max
  }

  return {
    similarRides, selectedCompareIds, isComparing, compareRows,
    toggleCompare, clearCompare, openCompareDetails, colorOf,
    racers, compareLines, compareCursors,
    aheadOf, behindOf, barMagPct, gapVsYouText, gapColor, hrColor, hrOf,
    raceMaxIndex,
  }
}
