<script setup lang="ts">
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { Splitpanes, Pane } from 'splitpanes'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { getActivities, getSettings, saveSetting, getTrack, getChartTrack, getTileProviders, getActivityMedia, activityMediaUrl, deleteActivityMedia, uploadActivityMedia, discoverPhotos, makePhotoPersonal, isVideoFile, getAllMarkers, deleteMarker, getSections, getActivityHighlights, getGear, type ActivitySummary, type RideMedia, type Marker } from '@/api/client'
import { markerCategory, markerIconSvg } from '@/markerCategories'
import MapRenderer from '@/components/MapRenderer.vue'
import ElevationChart from '@/components/ElevationChart.vue'
import TimeInZone from '@/components/TimeInZone.vue'
import RideStats from '@/components/RideStats.vue'
import RideAttributesPanel from '@/components/RideAttributesPanel.vue'
import { useRideStats } from '@/composables/useRideStats'
import { Layers, Play, Pause, Check, Camera, ChevronDown, ChevronUp, Crosshair, LockOpen, ImagePlus, Globe, UserRound, Gauge, Swords, MapPin, Tags, Trash2, Pencil, X } from 'lucide-vue-next'
import { push } from 'notivue'
import { onClickOutside } from '@vueuse/core'
import { REPLAY_STRATEGIES, type ReplayStrategy } from '@/composables/replayStrategies'
import { useTrackData, activeIndex } from '@/composables/useTrackData'
import { useCurrentUser } from '@/composables/useCurrentUser'
import { distanceM, bboxOf, inBBox } from '@/lib/geo'
import { useHrZones } from '@/composables/useHrZones'
import { useRaceCompare } from '@/composables/useRaceCompare'
import { VIEWER_LAYOUT_SLOT, autoLayoutRef } from '@/composables/useLayoutState'

const props = defineProps<{ activityId: string | null; showNearbyTours?: boolean; showPhotos?: boolean; showHighlights?: boolean }>()

const qc = useQueryClient()
const mapRef = ref<any>(null)
const activityId = computed(() => props.activityId)

// The in-place multi-rider race ("ghost chase") lives in a dedicated composable
// to keep this viewer a thin shell; it's wired up below once the track + replay
// state it depends on exist. See useRaceCompare for the full state machine.

// Markers list (filterable table; click → jump on map, delete inline).
const { data: markers } = useQuery({
  queryKey: ['markers'],
  queryFn: getAllMarkers,
  staleTime: 5 * 60 * 1000,
})
// Curated sections (read-only, from the global section.json.gz) → the elevation
// chart highlights the ones whose start/end match this ride's track.
const { data: sections } = useQuery({ queryKey: ['sections'], queryFn: getSections, staleTime: Infinity })
// Auto-detected highlights (OSM passes crossed + named peaks near the ride),
// computed server-side from cached OSM data → markers on the map.
const { data: highlights } = useQuery({
  queryKey: computed(() => ['highlights', activityId.value]),
  queryFn: () => getActivityHighlights(activityId.value!),
  enabled: computed(() => activityId.value != null),
  staleTime: 5 * 60 * 1000,
})

const markerFilter = ref('')
const markerSort = ref<{ key: 'label' | 'category'; dir: 'asc' | 'desc' }>({ key: 'label', dir: 'asc' })
function setSort(key: 'label' | 'category') {
  if (markerSort.value.key === key) markerSort.value.dir = markerSort.value.dir === 'asc' ? 'desc' : 'asc'
  else markerSort.value = { key, dir: 'asc' }
}
const filteredMarkers = computed(() => {
  // Only markers near THIS ride (track bbox + 5 km) — a Sweden ride shouldn't
  // list a Mallorca marker. Then the text filter.
  const box = bboxOf((withDist.value ?? []).map(p => ({ lat: p.lat, lon: p.lon })), 5)
  let list = (markers.value ?? []).filter(m => inBBox(m.lat, m.lon, box))
  const q = markerFilter.value.trim().toLowerCase()
  if (q) list = list.filter(m =>
    (m.label || '').toLowerCase().includes(q) ||
    m.category.toLowerCase().includes(q) ||
    (m.description || '').toLowerCase().includes(q))
  return list
})
const sortedMarkers = computed(() => {
  const { key, dir } = markerSort.value
  const sign = dir === 'asc' ? 1 : -1
  return [...filteredMarkers.value].sort((a, b) => {
    let av: string | number, bv: string | number
    if (key === 'category') { av = markerCategory(a.category).label; bv = markerCategory(b.category).label }
    else { av = (a.label || markerCategory(a.category).label).toLowerCase(); bv = (b.label || markerCategory(b.category).label).toLowerCase() }
    return av < bv ? -sign : av > bv ? sign : 0
  })
})
function jumpToMarker(m: { lon: number; lat: number }) {
  mapRef.value?.flyToCoords?.(m.lon, m.lat)
}
function editMarker(m: Marker) {
  mapRef.value?.flyToCoords?.(m.lon, m.lat)
  mapRef.value?.openMarkerEditor?.(m)
}
async function removeMarker(id: string) {
  try {
    await deleteMarker(id)
    await qc.invalidateQueries({ queryKey: ['markers'] })
  } catch { push.error('Could not delete marker') }
}
const markerCat = markerCategory

const { data: rawPoints } = useQuery({
  queryKey: computed(() => ['track', activityId.value]),
  queryFn: () => getTrack(activityId.value!),
  enabled: computed(() => activityId.value != null),
})
const { data: chartRawPoints } = useQuery({
  queryKey: computed(() => ['track-chart', activityId.value]),
  queryFn: () => getChartTrack(activityId.value!),
  enabled: computed(() => activityId.value != null),
})

// ── Geo-matched photos → fade in during replay when the rider hits the point ──
const { data: media } = useQuery({
  queryKey: computed(() => ['media', activityId.value]),
  queryFn: () => getActivityMedia(activityId.value!),
  enabled: computed(() => activityId.value != null),
  staleTime: 60 * 60 * 1000,
})
const placedMedia = computed<RideMedia[]>(() =>
  (media.value ?? []).filter(m => m.trackIndex != null).sort((a, b) => a.trackIndex! - b.trackIndex!),
)
// Show a photo while the rider is within this many samples of its matched point.
const PHOTO_WINDOW = 60
// The photo to show right now: nearest placed photo within the window of the
// current replay/scrub index (closest wins). Null → overlay fades out.
const activePhoto = computed<RideMedia | null>(() => {
  const idx = activeIndex.value
  if (idx == null || !placedMedia.value.length) return null
  let best: RideMedia | null = null, bestD = PHOTO_WINDOW + 1
  for (const m of placedMedia.value) {
    const d = Math.abs(m.trackIndex! - idx)
    if (d <= PHOTO_WINDOW && d < bestD) { bestD = d; best = m }
  }
  return best
})
const photoUrl = (m: RideMedia) => activityMediaUrl(activityId.value!, m.name)

// "Find photos" — fetch CC photos along the route from Wikimedia Commons; they
// land in the ride's media folder → appear as map pins + replay fades.
//
// Overlay toggles (Photos pins/fades, Highlights markers). When embedded in
// ToursView these live in its top toolbar next to "Tours" and are passed down as
// props (controlled mode). Standalone (ActivityDetailView) we fall back to our
// own persisted refs and render the checkboxes in the playback toolbar.
const overlaysControlled = computed(() => props.showPhotos !== undefined || props.showHighlights !== undefined)
const showPhotosInternal = autoLayoutRef<boolean>(VIEWER_LAYOUT_SLOT, 'showPhotos', true)
const showHighlightsInternal = autoLayoutRef<boolean>(VIEWER_LAYOUT_SLOT, 'showHighlights', true)
const showPhotos = computed(() => props.showPhotos ?? showPhotosInternal.value)
const hasPhotos = computed(() => (media.value?.length ?? 0) > 0)
const showHighlights = computed(() => props.showHighlights ?? showHighlightsInternal.value)
const hasHighlights = computed(() => ((highlights.value?.passes.length ?? 0) + (highlights.value?.peaks.length ?? 0)) > 0)
const discovering = ref(false)

// Bottom pane: switch between the elevation graph and the photo gallery.
const bottomView = autoLayoutRef<'graph' | 'photos' | 'stats' | 'compare' | 'markers' | 'attributes'>(VIEWER_LAYOUT_SLOT, 'bottomView', 'graph')
// Gallery lightbox (native popover, like the map's).
const galleryPhoto = ref<{ url: string; name: string } | null>(null)
const galleryPopoverEl = ref<HTMLElement | null>(null)
function openGalleryPhoto(m: RideMedia) {
  // Jump the map/chart to where the photo was taken, then show it as an overlay.
  if (m.trackIndex != null) activeIndex.value = m.trackIndex
  galleryPhoto.value = { url: photoUrl(m), name: m.name }
  nextTick(() => (galleryPopoverEl.value as unknown as { showPopover?: () => void })?.showPopover?.())
}
function closeGalleryPhoto() {
  (galleryPopoverEl.value as unknown as { hidePopover?: () => void })?.hidePopover?.()
}
function onGalleryToggle(e: Event) {
  if ((e as unknown as { newState?: string }).newState === 'closed') galleryPhoto.value = null
}
const originLabel = (o: string) => (o === 'public' ? 'Public domain' : 'Personal')
// Whether any photo of each origin exists — drives which legend entries show.
const hasPublic = computed(() => (media.value ?? []).some(m => m.origin === 'public'))
const hasPersonal = computed(() => (media.value ?? []).some(m => m.origin !== 'public'))
// Move a discovered public photo into the personal set, then refresh the manifest.
const movingPhoto = ref<string | null>(null)
async function moveToPersonal(m: RideMedia) {
  if (!activityId.value || movingPhoto.value) return
  movingPhoto.value = m.name
  try {
    await makePhotoPersonal(activityId.value, m.name)
    await qc.invalidateQueries({ queryKey: ['media', activityId.value] })
    push.success({ title: 'Moved to personal', message: m.name })
  } catch {
    push.error('Could not move photo')
  } finally {
    movingPhoto.value = null
  }
}
async function removePhoto(m: RideMedia) {
  if (!activityId.value) return
  try {
    await deleteActivityMedia(activityId.value, m.name)
    await qc.invalidateQueries({ queryKey: ['media', activityId.value] })
  } catch {
    push.error('Could not delete photo')
  }
}
// Drag-and-drop / picker upload straight onto the Photos tab (without opening
// the Edit panel). preventDefault on the drop is what stops the browser from
// just navigating to the dropped image file.
const mediaDragOver = ref(false)
const uploadingMedia = ref(false)
async function addMedia(files: FileList | File[] | null) {
  if (!activityId.value || !files) return
  const list = Array.from(files).filter(f => f.type.startsWith('image/') || f.type.startsWith('video/'))
  if (!list.length) return
  uploadingMedia.value = true
  try {
    const added = await uploadActivityMedia(activityId.value, list)
    await qc.invalidateQueries({ queryKey: ['media', activityId.value] })
    push.success({ title: `${added.length || list.length} photo${(added.length || list.length) === 1 ? '' : 's'} added` })
  } catch {
    push.error('Could not upload photos')
  } finally {
    uploadingMedia.value = false
  }
}
async function findPhotos() {
  if (!activityId.value || discovering.value) return
  discovering.value = true
  try {
    const found = await discoverPhotos(activityId.value)
    await qc.invalidateQueries({ queryKey: ['media', activityId.value] })
    push.success({
      title: found.length ? `${found.length} photo${found.length === 1 ? '' : 's'} added` : 'No photos found nearby',
      message: 'Wikimedia Commons (CC) · along the route',
    })
  } catch {
    push.error('Photo lookup failed')
  } finally {
    discovering.value = false
  }
}
const { withDist, chartPoints, activeChartIndex, breaks } = useTrackData(
  () => rawPoints.value,
  () => chartRawPoints.value,
)

// "Skipped break" overlay state — populated when the playback loop crosses
// a detected break span; auto-clears after the dwell + fade so it never
// lingers. We hold the marker at the break-start for `BREAK_DWELL_MS` (a TV
// "pause-and-cut" beat) before jumping to the resume index; that's the
// difference between a cinematic skip and an unexplained teleport.
type SkipNotice = { fromIdx: number; toIdx: number; secSkipped: number }
const skipNotice = ref<SkipNotice | null>(null)
let skipNoticeTimer = 0
const BREAK_DWELL_MS = 2000  // immersive pause while the popup is up
// Set to performance.now() + BREAK_DWELL_MS when we enter a break; while in
// the future, the tick loop holds position and skips its own clock.
let breakDwellUntil = 0
// Tracks the break we're currently dwelling on so the tick loop can jump to
// its toIdx exactly once when the dwell ends.
let activeBreakSpan: { fromIdx: number; toIdx: number } | null = null
function fmtSkipSeconds(sec: number): string {
  if (sec < 60) return `${sec}s`
  const m = Math.floor(sec / 60), s = sec % 60
  return s ? `${m}m ${s}s` : `${m}m`
}

const { data: activities } = useQuery({ queryKey: ['activities'], queryFn: getActivities })
const activity = computed<ActivitySummary | null>(() =>
  activities.value?.find(a => a.id === activityId.value) ?? null,
)

// This ride's gear glyph (if its gear has one) → drives the replay cursor icon.
const { data: gearList } = useQuery({ queryKey: ['gear'], queryFn: () => getGear() })
const rideGear = computed(() =>
  gearList.value?.find(g => g.id === activity.value?.gearId) ?? null,
)
const cursorIcon = computed<string | null>(() => rideGear.value?.icon ?? null)

// "Tours" overlay: every OTHER ride's start point, for the map to pin whichever
// fall in the current viewport (empty when the toggle is off).
const nearbyTours = computed(() =>
  props.showNearbyTours
    ? (activities.value ?? [])
        .filter(a => a.id != null && a.id !== activityId.value && a.startLat != null && a.startLon != null)
        .map(a => ({ id: a.id!, name: a.name ?? '', lat: a.startLat!, lon: a.startLon! }))
    : [])

const { user: currentUser } = useCurrentUser()
const { zones: hrZones, tiz, totalSec: hrTotalSec } = useHrZones(
  currentUser,
  computed(() => rawPoints.value ?? null),
)
// Advanced ride analytics — computed from the full-res track.
const rideStats = useRideStats(() => withDist.value, activity, currentUser, () => rideGear.value?.weightKg)

const colorMode = ref<'none' | 'hr' | 'slope'>('none')
const colorModes = [
  { id: 'none' as const, label: 'Line' },
  { id: 'hr' as const, label: 'HR' },
  { id: 'slope' as const, label: 'Slope' },
]

const { data: settings } = useQuery({ queryKey: ['settings'], queryFn: getSettings })
const tileProvider = computed(() => settings.value?.find(s => s.key === 'map.provider')?.value ?? 'osm')
// Hillshade + 3D as an independent overlay. Works on top of any basemap
// (raster or vector). Persisted so it survives reloads. The legacy 'terrain'
// basemap id also implicitly enables this on the map side for backward-compat.
const showHillshade = computed(() => settings.value?.find(s => s.key === 'map.hillshade')?.value === 'true')
const saveHillshadeMut = useMutation({
  mutationFn: (enabled: boolean) => saveSetting('map.hillshade', enabled ? 'true' : 'false'),
  onSuccess: () => qc.invalidateQueries({ queryKey: ['settings'] }),
})
function toggleHillshade() { saveHillshadeMut.mutate(!showHillshade.value) }
// Dynamic provider catalog from the backend. We filter out elevation-only DEM
// providers (terrain) from the basemap picker — they're overlays, not
// basemaps, and would render unreadably as a flat raster.
const { data: providerCatalog } = useQuery({
  queryKey: ['tile-providers'],
  queryFn: getTileProviders,
  staleTime: 60 * 60 * 1000,
})
const providers = computed(() =>
  (providerCatalog.value ?? []).filter(p => !p.isElevation).map(p => ({
    id: p.id,
    label: p.label,
    description: p.description,
    type: p.type,
  })),
)
const saveProviderMut = useMutation({
  mutationFn: (id: string) => saveSetting('map.provider', id),
  onSuccess: () => qc.invalidateQueries({ queryKey: ['settings'] }),
})

const isPlaying = ref(false)
// Logarithmic replay speed: the slider is linear 0–100 but maps exponentially
// to 1–50×, so the low end (1–5×, where you actually fine-tune) gets most of
// the travel and the fast end is still reachable. `speedPos` is the slider;
// `playbackSpeed` is the derived multiplier (read by the tick + label).
const SPEED_MIN = 1, SPEED_MAX = 50
const speedPos = ref(Math.round(100 * Math.log(3) / Math.log(SPEED_MAX)))   // default 3×
const playbackSpeed = computed(() => {
  const raw = SPEED_MIN * (SPEED_MAX / SPEED_MIN) ** (speedPos.value / 100)
  // Half-step granularity under 5× (so 1.5×, 2.5×, … are reachable), whole
  // numbers above where the fine control no longer matters.
  return Math.max(SPEED_MIN, raw < 5 ? Math.round(raw * 2) / 2 : Math.round(raw))
})
const replayStrategy = ref<ReplayStrategy>('helicopter')

// In-place multi-rider race — wired here now that the track + replay state it
// reacts to exist. Exposes everything the Compare tab + map overlay bind to.
const {
  similarRides, selectedCompareIds, isComparing, compareRows,
  toggleCompare, clearCompare, openCompareDetails, colorOf,
  racers, compareLines, compareCursors,
  aheadOf, behindOf, barMagPct, gapVsYouText, gapColor, hrColor, hrOf, raceMaxIndex,
} = useRaceCompare({ activityId, rawPoints, activity, isPlaying })

// "yesterday" / "2 months ago" / "one year ago" — a human-friendly age for the
// compare picker (more useful than a raw GPS-overlap %). Picks the coarsest unit
// that fits and lets Intl phrase it ("last year", "today", …).
const REL_TIME = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
function relativeDay(iso?: string | null): string {
  if (!iso) return ''
  const days = Math.round((new Date(iso).getTime() - Date.now()) / 86_400_000)
  if (Math.abs(days) < 7) return REL_TIME.format(days, 'day')
  if (Math.abs(days) < 30) return REL_TIME.format(Math.round(days / 7), 'week')
  if (Math.abs(days) < 365) return REL_TIME.format(Math.round(days / 30), 'month')
  return REL_TIME.format(Math.round(days / 365), 'year')
}
// "Follow cursor" — lifted from ActivityMap so the playback toolbar can host
// the toggle. The map watches this and re-snaps to the rider on a true
// transition; while false the user can pan/zoom freely without the camera
// fighting back on the next replay tick.
const isFollowing = ref(true)
function toggleFollow() { isFollowing.value = !isFollowing.value }

// Re-anchor the start so a mid-play speed change takes effect from "now"
// without a discontinuity. Without this, doubling speed mid-play would also
// retroactively scale the elapsed-time term and the cursor would jump.
watch(playbackSpeed, () => {
  if (!isPlaying.value) return
  playbackStartMs = performance.now()
  playbackStartIdx = activeIndex.value ?? playbackStartIdx
})

// Chart pane fold state — persisted between sessions so users with small
// screens or "I only ever look at the map" workflows don't have to re-collapse
// it on every visit.
const chartCollapsed = autoLayoutRef<boolean>(VIEWER_LAYOUT_SLOT, 'chartCollapsed', false)
const chartSize = autoLayoutRef<number>(VIEWER_LAYOUT_SLOT, 'chartSize', 32)
// "Expand stats" gives the bottom panel a balanced 50/50 split rather than the
// whole height — the stats are compact, so a full-height panel just leaves dead
// space, and you keep the map in view. Folding back returns to the user's saved
// split (the starting position), not a collapsed state. Mutually exclusive with
// the map-full panel fold (chartCollapsed).
const statsExpanded = autoLayoutRef<boolean>(VIEWER_LAYOUT_SLOT, 'statsExpanded', false)
// Visible Splitpanes pane sizes. Folding the panel down shrinks it to a thin
// handle (2.4 %, matching the min-size below); expanding stats is a 50/50 split.
const HANDLE_PCT = 2.4
const STATS_HALF_PCT = 50
const effectiveChartSize = computed(() => {
  if (!activityId.value) return 0
  if (chartCollapsed.value) return HANDLE_PCT        // panel folded → map full
  if (statsExpanded.value) return STATS_HALF_PCT     // balanced 50/50
  return chartSize.value                             // user's saved split (start)
})
const effectiveMapSize = computed(() => activityId.value ? 100 - effectiveChartSize.value : 100)
// splitpanes v4 emits { panes: [...] }; older builds emit the array directly.
type ResizePayload = { panes?: Array<{ size: number }> }
function onPaneResize(e: ResizePayload | Array<{ size: number }>) {
  // panes[0] = map (top), panes[1] = chart (bottom). Only persist a genuine
  // user drag — i.e. when neither preset (panel-fold / 50-50) is active.
  const panes = Array.isArray(e) ? e : (e?.panes ?? [])
  if (!chartCollapsed.value && !statsExpanded.value && panes[1]?.size != null) {
    chartSize.value = panes[1].size
  }
}
function toggleChart() { chartCollapsed.value = !chartCollapsed.value; if (chartCollapsed.value) statsExpanded.value = false }
function toggleStats() { statsExpanded.value = !statsExpanded.value; if (statsExpanded.value) chartCollapsed.value = false }

// rAF-driven smooth playback. The integer activeIndex still drives the chart
// cursor + segment highlight (those work in point-space), but we also expose
// a sub-pixel-precise interpolated [lon, lat] to the map so the marker glides
// instead of hopping. ~16 ms instead of 50 ms ticks too — true 60 fps.
let playbackRaf = 0
let playbackStartMs = 0
let playbackStartIdx = 0
const hoverCoords = ref<[number, number] | null>(null)
// Exact fractional track index of the playhead (not the integer activeIndex),
// streamed to the map at 60 fps so the replay camera samples its precomputed
// path BETWEEN points and glides continuously. Null when not playing.
const playFrac = ref<number | null>(null)

// CENTRIPETAL Catmull-Rom spline (α = 0.5) through 4 surrounding points —
// gives a true curve through bends instead of the zig-zag a linear lerp makes
// between FIT samples, AND (unlike the uniform variant) never overshoots or
// loops back on itself on hairpins / unevenly-spaced points. That overshoot was
// the source of the marker occasionally appearing to step ~1px backwards.
// Evaluated with the Barry-Goldman pyramid; knots are spaced by inter-point
// distance^α (plain lon/lat Euclidean — only the *relative* spacing matters).
type LL = { lat: number; lon: number }
function crAxis(v0: number, v1: number, v2: number, v3: number,
                t0: number, t1: number, t2: number, t3: number, tt: number): number {
  const I = (a: number, b: number, ta: number, tb: number) => ((tb - tt) * a + (tt - ta) * b) / (tb - ta)
  const a1 = I(v0, v1, t0, t1), a2 = I(v1, v2, t1, t2), a3 = I(v2, v3, t2, t3)
  const b1 = I(a1, a2, t0, t2), b2 = I(a2, a3, t1, t3)
  return I(b1, b2, t1, t2)
}
function centripetal(p0: LL, p1: LL, p2: LL, p3: LL, t: number): [number, number] {
  const knot = (ti: number, a: LL, b: LL) => ti + Math.sqrt(Math.hypot(b.lon - a.lon, b.lat - a.lat))
  const t0 = 0, t1 = knot(t0, p0, p1), t2 = knot(t1, p1, p2), t3 = knot(t2, p2, p3)
  // Coincident points → zero-length knot interval; fall back to a straight lerp
  // between p1 and p2 to avoid divide-by-zero.
  if (t1 === t0 || t2 === t1 || t3 === t2) {
    return [p1.lon + (p2.lon - p1.lon) * t, p1.lat + (p2.lat - p1.lat) * t]
  }
  const tt = t1 + (t2 - t1) * t
  return [
    crAxis(p0.lon, p1.lon, p2.lon, p3.lon, t0, t1, t2, t3, tt),
    crAxis(p0.lat, p1.lat, p2.lat, p3.lat, t0, t1, t2, t3, tt),
  ]
}
function interpAt(pts: LL[], fracIdx: number): [number, number] {
  const n = pts.length
  if (n === 0) return [0, 0]
  if (n === 1 || fracIdx <= 0) return [pts[0].lon, pts[0].lat]
  if (fracIdx >= n - 1) return [pts[n - 1].lon, pts[n - 1].lat]
  const i1 = Math.floor(fracIdx)
  const t = fracIdx - i1
  const i0 = Math.max(0, i1 - 1)
  const i2 = Math.min(n - 1, i1 + 1)
  const i3 = Math.min(n - 1, i1 + 2)
  return centripetal(pts[i0], pts[i1], pts[i2], pts[i3], t)
}

// Precomputed ONCE per track (cached by Vue until the track changes): a lightly
// moving-averaged cursor path (5-tap). Only the replay MARKER follows this — the
// drawn track line + stats stay on the raw GPS — so the little left-right-left
// GPS jitter is ironed out, with no per-frame smoothing cost (the source of the
// micro-stutter). interpAt still Catmull-Roms between these for sub-point glide.
const smoothCoords = computed<{ lat: number; lon: number }[]>(() => {
  const pts = withDist.value
  const n = pts.length
  if (n < 7) return pts.map(p => ({ lat: p.lat, lon: p.lon }))
  const W = 3   // 7-tap: look 3 ahead + 3 behind so the marker (and the camera
                // that follows it) glide through GPS wobble instead of twitching.
  const out = new Array<{ lat: number; lon: number }>(n)
  for (let i = 0; i < n; i++) {
    let lat = 0, lon = 0, c = 0
    for (let j = Math.max(0, i - W); j <= Math.min(n - 1, i + W); j++) { lat += pts[j].lat; lon += pts[j].lon; c++ }
    out[i] = { lat: lat / c, lon: lon / c }
  }
  return out
})

function startPlayback() {
  if (!withDist.value.length) return
  // If the previous replay ran to the end, the cursor is parked on the last
  // sample — restart from the top rather than instantly re-hitting the
  // end-of-track stop (which looked like "Play does nothing").
  const lastIdx = withDist.value.length - 1
  if (activeIndex.value == null || activeIndex.value >= lastIdx) activeIndex.value = 0
  isPlaying.value = true
  playbackStartMs = performance.now()
  playbackStartIdx = activeIndex.value
  // Treat playbackSpeed as "points per 50ms" to keep the existing 1×/3×/10×/25×
  // labelling honest. So speed * 20 = points per second.
  const tick = () => {
    if (!isPlaying.value) return
    const pts = withDist.value
    const primaryLast = pts.length - 1
    // While racing, keep the clock running until the LAST (longest) runner
    // finishes — not just until "You" do. The primary cursor parks at its own
    // finish meanwhile; the ghost markers (capped per-track downstream) keep
    // advancing to their ends.
    const endIdx = isComparing.value ? Math.max(primaryLast, raceMaxIndex.value) : primaryLast
    const elapsedSec = (performance.now() - playbackStartMs) / 1000
    const frac = playbackStartIdx + playbackSpeed.value * 20 * elapsedSec
    if (frac >= endIdx) {
      activeIndex.value = endIdx
      hoverCoords.value = [pts[primaryLast].lon, pts[primaryLast].lat]
      playFrac.value = primaryLast
      stopPlayback()
      return
    }
    const curIdx = Math.floor(frac)

    // ── Break dwell: hold the marker at the break-start while the popup is
    // up, then jump to the resume index. Two-phase:
    //
    //   phase 1 (dwell)  : freeze cursor at activeBreakSpan.fromIdx until
    //                      breakDwellUntil. This is the "the rider stopped
    //                      for 4 minutes" beat that makes the skip feel like
    //                      a directed cut rather than a teleport.
    //   phase 2 (resume) : jump activeIndex to toIdx + re-anchor playback
    //                      clock so the post-break frac math is clean.
    //
    // The popup itself stays visible for the full dwell + 600 ms leave fade.
    if (activeBreakSpan) {
      const now = performance.now()
      if (now < breakDwellUntil) {
        // Still dwelling — pin marker at the break-start point.
        activeIndex.value = activeBreakSpan.fromIdx
        hoverCoords.value = [pts[activeBreakSpan.fromIdx].lon, pts[activeBreakSpan.fromIdx].lat]
        playFrac.value = activeBreakSpan.fromIdx
        playbackRaf = requestAnimationFrame(tick)
        return
      }
      // Dwell finished — jump to resume point + re-anchor the rAF clock.
      const toIdx = activeBreakSpan.toIdx
      activeIndex.value = toIdx
      hoverCoords.value = [pts[toIdx].lon, pts[toIdx].lat]
      playFrac.value = toIdx
      playbackStartMs = performance.now()
      playbackStartIdx = toIdx
      activeBreakSpan = null
      playbackRaf = requestAnimationFrame(tick)
      return
    }

    // Phase 1 trigger: cursor just entered a break span. Pin the marker,
    // start the dwell timer, show the popup. ~1 Hz FIT means span point
    // count ≈ seconds skipped.
    const br = breaks.value.find(b => curIdx >= b.fromIdx && curIdx < b.toIdx)
    if (br) {
      activeBreakSpan = { fromIdx: br.fromIdx, toIdx: br.toIdx }
      breakDwellUntil = performance.now() + BREAK_DWELL_MS
      activeIndex.value = br.fromIdx
      hoverCoords.value = [pts[br.fromIdx].lon, pts[br.fromIdx].lat]
      playFrac.value = br.fromIdx
      skipNotice.value = { fromIdx: br.fromIdx, toIdx: br.toIdx, secSkipped: br.points }
      if (skipNoticeTimer) clearTimeout(skipNoticeTimer)
      // Dismiss popup as soon as dwell ends so it dissolves out just as
      // playback resumes. The 600 ms leave fade handled by CSS overlaps the
      // first ~half-second of post-break playback for a smooth handoff.
      skipNoticeTimer = window.setTimeout(() => { skipNotice.value = null }, BREAK_DWELL_MS)
      playbackRaf = requestAnimationFrame(tick)
      return
    }

    activeIndex.value = curIdx
    // Follow the smoothed path (ironed-out jitter); clamp to the primary's own
    // finish once "You" are done but the clock runs on for a longer ghost.
    hoverCoords.value = interpAt(smoothCoords.value, Math.min(frac, primaryLast))
    playFrac.value = Math.min(frac, primaryLast)
    playbackRaf = requestAnimationFrame(tick)
  }
  playbackRaf = requestAnimationFrame(tick)
}

function stopPlayback() {
  isPlaying.value = false
  if (skipNoticeTimer) { clearTimeout(skipNoticeTimer); skipNoticeTimer = 0 }
  skipNotice.value = null
  activeBreakSpan = null
  breakDwellUntil = 0
  if (playbackRaf) cancelAnimationFrame(playbackRaf)
  playbackRaf = 0
  playFrac.value = null   // stop driving the replay camera
}

function togglePlayback() { if (isPlaying.value) stopPlayback(); else startPlayback() }
function onChartJump(rawIdx: number) {
  stopPlayback()
  // Update activeIndex first — the watcher below fills hoverCoords, which
  // ActivityMap's hoverCoords watcher uses to placeCursor at the clicked
  // sample. Without this, animateToPoint would slide the camera there but
  // leave the marker on the rider's previous position → "where is it?".
  activeIndex.value = rawIdx
  mapRef.value?.animateToPoint?.(rawIdx)
}

watch(activityId, () => { stopPlayback(); activeIndex.value = null; hoverCoords.value = null })

// HARD STOP on unmount. Without this, navigating away from /tours mid-play
// leaves the rAF tick running inside the unmounted component — it keeps
// mutating activeIndex/hoverCoords (which trigger watchers in ActivityMap),
// causing the cascade of "Cannot read properties of null (reading '0')"
// (MapLibre's transform matrix is null after the map is torn down) and
// "Attempting to run(), but is already running" (reentrant render).
// stopPlayback handles cancelAnimationFrame + clears skipNotice timers.
onBeforeUnmount(() => { stopPlayback() })
// When the user scrubs on the chart while paused, the integer activeIndex jumps
// — also snap the smooth coords to that point so the map marker tracks.
watch(activeIndex, (idx) => {
  if (isPlaying.value) return
  const pts = withDist.value
  if (idx == null || !pts.length) { hoverCoords.value = null; return }
  hoverCoords.value = [pts[idx].lon, pts[idx].lat]
})

// ── Layers menu (single dropdown for provider + color mode) ─────────────────
const layersOpen = ref(false)
const layersMenuRef = ref<HTMLDivElement | null>(null)
onClickOutside(layersMenuRef, () => { layersOpen.value = false })

const activeProviderLabel = computed(() =>
  providers.value.find(p => p.id === tileProvider.value)?.label ?? 'Standard',
)
const activeColorLabel = computed(() =>
  colorModes.find(m => m.id === colorMode.value)?.label ?? 'Line',
)
</script>

<template>
  <Splitpanes :horizontal="true" class="h-full w-full" @resize="onPaneResize">
    <Pane :size="effectiveMapSize" :min-size="2.0" class="relative overflow-hidden">
      <div class="h-full w-full bg-background flex items-center justify-center relative">
        <MapRenderer
          v-if="activityId"
          ref="mapRef"
          :activity-id="activityId"
          :cursor-icon="cursorIcon"
          :tile-provider="tileProvider"
          :hover-index="activeIndex"
          :hover-coords="hoverCoords"
          :play-frac="playFrac"
          :color-mode="colorMode"
          :hr-zones="hrZones"
          :is-playing="isPlaying"
          :replay-strategy="replayStrategy"
          :is-following="isFollowing"
          :show-hillshade="showHillshade"
          :highlights="showHighlights ? (highlights ?? null) : null"
          :nearby-tours="nearbyTours"
          :show-photos="showPhotos || bottomView === 'photos'"
          :compare-lines="compareLines.length ? compareLines : null"
          :compare-cursors="compareCursors.length ? compareCursors : null"
          @user-interacted="isFollowing = false"
          @photo-jump="activeIndex = $event"
          class="absolute inset-0"
        />
        <div v-else class="text-sm text-muted-fg opacity-60 select-none">Pick a tour on the left</div>

        <!-- Race banner — ghost rides overlaid on this map, moving with Play.
             "Details" opens the full side-by-side page for the first one. -->
        <div v-if="isComparing" class="absolute top-2 left-1/2 -translate-x-1/2 z-[1000] flex items-center gap-2 bg-background/95 backdrop-blur-sm border border-primary/50 rounded-lg shadow px-2.5 py-1 text-[11px]">
          <Swords :size="12" class="text-primary shrink-0" />
          <span class="shrink-0">Racing {{ selectedCompareIds.length + 1 }} rides — press Play</span>
          <button class="text-muted-fg hover:text-foreground underline shrink-0" @click="openCompareDetails">Details</button>
          <button class="text-muted-fg hover:text-foreground shrink-0" title="Stop racing" @click="clearCompare"><X :size="12" /></button>
        </div>

        <!-- Skipped-break overlay. Fades in/out so it never interrupts the
             cinematic feel. Plays the role of a TV broadcast lower-third. -->
        <transition name="skip-fade">
          <div v-if="skipNotice"
            class="absolute top-6 left-1/2 -translate-x-1/2 z-[1500] pointer-events-none
                   inline-flex items-center gap-2 px-4 py-2 rounded-full
                   bg-background/85 backdrop-blur-md border border-border shadow-lg
                   text-xs font-medium text-foreground">
            <span class="inline-block w-2 h-2 rounded-full bg-amber-500 animate-pulse" />
            Break detected · skipped {{ fmtSkipSeconds(skipNotice.secSkipped) }}
          </div>
        </transition>

        <!-- Geo-matched photo — fades in as the rider reaches the spot it was
             taken, fades out as they pass. A polaroid in the corner. -->
        <!-- Replay photo — a small picture-in-picture that gently fades in/out as
             the rider passes the spot. Bottom-left, clear of the map controls,
             so it flows with the replay without covering the map. -->
        <transition name="photo-fade">
          <figure v-if="activePhoto && showPhotos" :key="activePhoto.name"
            class="absolute bottom-4 left-4 z-[1400] pointer-events-none m-0
                   w-28 sm:w-36 rounded-md overflow-hidden shadow-xl ring-1 ring-white/25">
            <video v-if="isVideoFile(activePhoto.name)" :src="photoUrl(activePhoto)"
              class="block w-full h-20 sm:h-24 object-cover" muted autoplay loop playsinline />
            <img v-else :src="photoUrl(activePhoto)" :alt="activePhoto.name"
              class="block w-full h-20 sm:h-24 object-cover" loading="lazy" />
          </figure>
        </transition>

        <!-- Photo marker legend — explains the public vs personal pins on the map.
             Bottom-right, clear of the replay PiP (bottom-left) and layers menu. -->
        <div v-if="activityId && (showPhotos || bottomView === 'photos') && hasPhotos"
          class="absolute bottom-3 right-3 z-[1000] flex flex-col gap-1
                 bg-background/85 backdrop-blur-sm border border-border rounded-lg px-2.5 py-1.5 shadow-sm text-[10px] text-foreground">
          <span v-if="hasPublic" class="inline-flex items-center gap-1.5">
            <span class="photo-legend-dot photo-legend-dot--public"><Globe :size="9" /></span>Public domain
          </span>
          <span v-if="hasPersonal" class="inline-flex items-center gap-1.5">
            <span class="photo-legend-dot photo-legend-dot--personal"><UserRound :size="9" /></span>Personal
          </span>
        </div>

        <div v-if="activityId" ref="layersMenuRef" class="absolute top-3 left-3 z-[1000]">
          <button
            class="inline-flex items-center gap-1.5 bg-background backdrop-blur-sm border border-border rounded-lg px-2.5 py-1.5 shadow-sm text-[11px] font-medium text-foreground hover:bg-muted/40 transition-colors"
            :class="layersOpen ? 'border-primary text-primary' : ''"
            @click="layersOpen = !layersOpen"
            :title="`${activeProviderLabel} · ${activeColorLabel}`"
          >
            <Layers :size="13" />
            <span>{{ activeProviderLabel }} · {{ activeColorLabel }}</span>
          </button>

          <div v-if="layersOpen"
            class="absolute top-full left-0 mt-1 w-44 rounded-lg border border-border bg-background shadow-lg overflow-hidden text-[11px]">
            <div class="px-3 pt-2 pb-1 text-[9px] uppercase tracking-wide text-muted-fg font-semibold">Background</div>
            <button v-for="p in providers" :key="p.id"
              class="w-full flex items-center gap-2 px-3 py-1.5 text-left hover:bg-muted/40"
              :class="tileProvider === p.id ? 'text-primary font-semibold' : 'text-foreground'"
              @click="saveProviderMut.mutate(p.id); layersOpen = false">
              <Check :size="11" :class="tileProvider === p.id ? 'opacity-100' : 'opacity-0'" />
              {{ p.label }}
            </button>

            <div class="border-t border-border" />
            <div class="px-3 pt-2 pb-1 text-[9px] uppercase tracking-wide text-muted-fg font-semibold">Overlays</div>
            <button
              class="w-full flex items-center gap-2 px-3 py-1.5 text-left hover:bg-muted/40"
              :class="showHillshade ? 'text-primary font-semibold' : 'text-foreground'"
              title="Drape terrain hillshade (relief shading) over the basemap. Works on raster and vector."
              @click="toggleHillshade()">
              <Check :size="11" :class="showHillshade ? 'opacity-100' : 'opacity-0'" />
              Hillshade
            </button>

            <div class="border-t border-border" />
            <div class="px-3 pt-2 pb-1 text-[9px] uppercase tracking-wide text-muted-fg font-semibold">Color</div>
            <button v-for="m in colorModes" :key="m.id"
              class="w-full flex items-center gap-2 px-3 py-1.5 text-left hover:bg-muted/40"
              :class="colorMode === m.id ? 'text-primary font-semibold' : 'text-foreground'"
              @click="colorMode = m.id; layersOpen = false">
              <Check :size="11" :class="colorMode === m.id ? 'opacity-100' : 'opacity-0'" />
              {{ m.label }}
            </button>
          </div>
        </div>
      </div>
    </Pane>

    <Pane v-if="activityId" :size="effectiveChartSize" :min-size="2.0" style="isolation: isolate; z-index: 1"
      class="flex flex-col bg-background border-t border-border overflow-hidden">

      <!-- Collapsed mode: pane is a single wide "Show diagram" handle —
           thin strip, just enough to register a click target without giving
           up visible map space. -->
      <button v-if="chartCollapsed"
        class="h-full w-full flex items-center justify-center gap-2 px-3 py-0 text-[10px] font-semibold text-muted-fg hover:text-foreground hover:bg-muted/30 transition-colors border-t border-border bg-muted/10"
        title="Show diagram (elevation, HR, time-in-zone)"
        @click="toggleChart">
        <ChevronUp :size="12" />
        <span>Show diagram</span>
      </button>

      <!-- Expanded mode: full playback toolbar + chart + zone bar.

           Layout is grouped into three visual clusters separated by 1 px
           dividers:

             [ Playback  · Play/Pause · Speed ] | [ Camera · Strategy · Follow ]
                                                | [ Activity info / live stats ] | [ View ]

           Each group has its own padding so the dividers read as deliberate
           seams rather than incidental borders. -->
      <div v-else class="flex items-center gap-3 px-3 py-1.5 border-b border-border bg-muted/20 shrink-0">

        <!-- Group 1 · Playback -->
        <div class="flex items-center gap-2 pr-3 border-r border-border">
          <button
            class="flex items-center gap-1 px-2.5 py-1 rounded-md text-[11px] font-semibold transition-colors active:scale-95"
            :class="isPlaying ? 'bg-red-500/10 text-red-500 hover:bg-red-500/30' : 'bg-primary/10 text-primary hover:bg-primary/30'"
            @click="togglePlayback">
            <component :is="isPlaying ? Pause : Play" :size="11" />
            {{ isPlaying ? 'Pause' : 'Play' }}
          </button>
          <label class="flex items-center gap-1.5 text-[11px] text-muted-fg" title="Replay speed (logarithmic 1–50×; most of the travel is the slow 1–5× range). Adjustable while playing.">
            <input v-model.number="speedPos" type="range" min="0" max="100" step="1"
              class="w-24 accent-primary cursor-pointer" />
            <span class="font-mono tabular-nums text-foreground w-10 text-right">{{ playbackSpeed }}×</span>
          </label>
        </div>

        <!-- Group 2 · Camera (cinematic profile + follow toggle) -->
        <div class="flex items-center gap-2 pr-3 border-r border-border">
          <label class="flex items-center gap-1 text-[11px] text-muted-fg" title="Replay camera profile">
            <Camera :size="11" />
            <select v-model="replayStrategy"
              class="text-[11px] bg-background border border-border rounded px-1.5 py-0.5">
              <option v-for="s in REPLAY_STRATEGIES" :key="s.id" :value="s.id" :title="s.description">
                {{ s.label }}
              </option>
            </select>
          </label>
          <button
            class="inline-flex items-center gap-1 px-2 py-1 rounded text-[11px] font-medium transition-colors border"
            :class="isFollowing
              ? 'border-primary text-primary bg-primary/5 hover:bg-primary/10'
              : 'border-border text-muted-fg hover:text-foreground hover:bg-muted/40'"
            :title="isFollowing ? 'Following cursor — click to unlock map' : 'Click to follow cursor'"
            @click="toggleFollow">
            <component :is="isFollowing ? Crosshair : LockOpen" :size="11" />
            <span>{{ isFollowing ? 'Follow' : 'Free' }}</span>
          </button>
          <!-- Uncontrolled (standalone) only — in ToursView these toggles live in
               the top toolbar next to "Tours". -->
          <label v-if="!overlaysControlled && hasPhotos" class="inline-flex items-center gap-1 text-[11px] text-muted-fg cursor-pointer select-none" title="Show photo pins + replay fades">
            <input type="checkbox" v-model="showPhotosInternal" class="accent-primary" />
            <span>Photos</span>
          </label>
          <label v-if="!overlaysControlled && hasHighlights" class="inline-flex items-center gap-1 text-[11px] text-muted-fg cursor-pointer select-none" title="Show auto-detected passes crossed + named peaks">
            <input type="checkbox" v-model="showHighlightsInternal" class="accent-primary" />
            <span>Highlights</span>
          </label>
        </div>

        <!-- Group 3 · Live stats (flex-grows to fill). The tour title is
             intentionally omitted — it's redundant with the selected row in the
             Tours list and just crowds the toolbar. -->
        <div class="flex items-center gap-2 min-w-0 flex-1">
          <template v-if="activeIndex != null && withDist[activeIndex]">
            <span class="text-[11px] font-mono text-muted-fg">{{ withDist[activeIndex].distKm.toFixed(2) }} km</span>
            <span v-if="withDist[activeIndex].altM != null" class="text-[11px] font-mono text-muted-fg">{{ Math.round(withDist[activeIndex].altM!) }} m</span>
            <span v-if="withDist[activeIndex].hr != null" class="text-[11px] font-mono text-red-400">{{ withDist[activeIndex].hr }} bpm</span>
          </template>
        </div>

        <!-- Group 4 · View switch (Graph ↔ Photos) + fold -->
        <div class="flex items-center gap-1 pl-3 border-l border-border">
          <div class="inline-flex rounded-md border border-border overflow-hidden text-[10px] font-medium">
            <button class="px-2 py-0.5 transition-colors"
              :class="bottomView === 'graph' ? 'bg-primary/15 text-primary' : 'text-muted-fg hover:bg-muted/40'"
              @click="bottomView = 'graph'">Graph</button>
            <button class="px-2 py-0.5 border-l border-border transition-colors inline-flex items-center gap-1"
              :class="bottomView === 'photos' ? 'bg-primary/15 text-primary' : 'text-muted-fg hover:bg-muted/40'"
              @click="bottomView = 'photos'">
              <ImagePlus :size="10" /> Photos<span v-if="hasPhotos" class="opacity-60">· {{ media!.length }}</span>
            </button>
            <button class="px-2 py-0.5 border-l border-border transition-colors inline-flex items-center gap-1"
              :class="bottomView === 'stats' ? 'bg-primary/15 text-primary' : 'text-muted-fg hover:bg-muted/40'"
              @click="bottomView = 'stats'">
              <Gauge :size="10" /> Stats
            </button>
            <button class="px-2 py-0.5 border-l border-border transition-colors inline-flex items-center gap-1"
              :class="bottomView === 'compare' ? 'bg-primary/15 text-primary' : 'text-muted-fg hover:bg-muted/40'"
              @click="bottomView = 'compare'">
              <Swords :size="10" /> Compare<span v-if="similarRides && similarRides.length" class="opacity-60">· {{ similarRides.length }}</span>
            </button>
            <button class="px-2 py-0.5 border-l border-border transition-colors inline-flex items-center gap-1"
              :class="bottomView === 'markers' ? 'bg-primary/15 text-primary' : 'text-muted-fg hover:bg-muted/40'"
              @click="bottomView = 'markers'">
              <MapPin :size="10" /> Markers<span v-if="markers && markers.length" class="opacity-60">· {{ markers.length }}</span>
            </button>
            <button class="px-2 py-0.5 border-l border-border transition-colors inline-flex items-center gap-1"
              :class="bottomView === 'attributes' ? 'bg-primary/15 text-primary' : 'text-muted-fg hover:bg-muted/40'"
              @click="bottomView = 'attributes'">
              <Tags :size="10" /> Events
            </button>
          </div>
          <!-- Two fold knobs: ▲ grows the panel to a 50/50 split (toggle back to
               the saved split), ▼ folds the panel so the map is full height. -->
          <div class="inline-flex items-center rounded-md border border-border overflow-hidden">
            <button class="px-1.5 py-0.5 transition-colors"
              :class="statsExpanded ? 'bg-primary/15 text-primary' : 'text-muted-fg hover:bg-muted/40'"
              :title="statsExpanded ? 'Back to saved split' : 'Expand panel — 50/50 split'" @click="toggleStats">
              <ChevronUp :size="13" />
            </button>
            <button class="px-1.5 py-0.5 border-l border-border text-muted-fg hover:bg-muted/40 transition-colors"
              title="Fold panel down — map full height" @click="toggleChart">
              <ChevronDown :size="13" />
            </button>
          </div>
        </div>
      </div>

      <!-- Graph view -->
      <template v-if="!chartCollapsed && bottomView === 'graph'">
        <div class="flex-1 min-h-0 py-1">
          <ElevationChart v-if="chartPoints.length"
            :points="chartPoints" :active-chart-index="activeChartIndex"
            :is-playing="isPlaying" :sections="sections ?? []"
            class="h-full" @jump="onChartJump" />
        </div>
        <TimeInZone :zones="hrZones" :seconds="tiz" :total-sec="hrTotalSec" :breakdown="false" />
      </template>

      <!-- Photos view — responsive gallery that reflows with the window. -->
      <div v-else-if="!chartCollapsed && bottomView === 'photos'"
        class="flex-1 min-h-0 overflow-y-auto p-2 relative transition-colors"
        :class="mediaDragOver ? 'ring-2 ring-inset ring-primary bg-primary/5' : ''"
        @dragover.prevent="mediaDragOver = true"
        @dragenter.prevent="mediaDragOver = true"
        @dragleave.prevent="mediaDragOver = false"
        @drop.prevent="mediaDragOver = false; addMedia($event.dataTransfer?.files ?? null)">
        <!-- Header: legend (public vs personal) + Add / Find-photos actions. -->
        <div class="flex items-center gap-3 px-1 pb-2 text-[10px] text-muted-fg">
          <template v-if="hasPhotos">
            <span class="font-medium uppercase tracking-wide opacity-70">Photos</span>
            <span v-if="hasPublic" class="inline-flex items-center gap-1">
              <span class="photo-legend-dot photo-legend-dot--public"><Globe :size="9" /></span>Public domain
            </span>
            <span v-if="hasPersonal" class="inline-flex items-center gap-1">
              <span class="photo-legend-dot photo-legend-dot--personal"><UserRound :size="9" /></span>Personal
            </span>
          </template>
          <label
            class="ml-auto inline-flex items-center gap-1 px-2 py-1 rounded text-[11px] font-medium border border-border text-muted-fg hover:text-primary hover:border-primary hover:bg-primary/10 transition-colors active:scale-95 cursor-pointer"
            :class="uploadingMedia ? 'opacity-50 pointer-events-none' : ''"
            title="Add photos/videos to this ride (or drag &amp; drop them here)">
            <ImagePlus :size="11" />
            <span>{{ uploadingMedia ? 'Uploading…' : 'Add photos' }}</span>
            <input type="file" accept="image/*,video/*" multiple class="hidden"
              @change="addMedia(($event.target as HTMLInputElement).files); ($event.target as HTMLInputElement).value = ''" />
          </label>
          <button
            class="inline-flex items-center gap-1 px-2 py-1 rounded text-[11px] font-medium border border-border text-muted-fg hover:text-primary hover:border-primary hover:bg-primary/10 transition-colors active:scale-95 disabled:opacity-50"
            title="Find Creative-Commons photos along this route (Wikimedia Commons)"
            :disabled="discovering"
            @click="findPhotos">
            <Globe :size="11" />
            <span>{{ discovering ? 'Finding…' : 'Find photos' }}</span>
          </button>
        </div>
        <div v-if="hasPhotos" class="grid gap-2" style="grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));">
          <figure v-for="m in media" :key="m.name" class="relative m-0 aspect-square rounded-md overflow-hidden border border-border cursor-zoom-in group"
            @click="openGalleryPhoto(m)">
            <template v-if="isVideoFile(m.name)">
              <video :src="photoUrl(m)" class="w-full h-full object-cover" muted preload="metadata" />
              <span class="absolute inset-0 flex items-center justify-center pointer-events-none">
                <Play :size="22" class="text-white/90 drop-shadow-lg" />
              </span>
            </template>
            <img v-else :src="photoUrl(m)" :alt="m.name" class="w-full h-full object-cover" loading="lazy" />
            <!-- Delete this photo (all photos). -->
            <button type="button"
              class="absolute top-1 left-1 z-10 inline-flex items-center justify-center w-5 h-5 rounded-full
                     bg-red-600/90 text-white opacity-0 group-hover:opacity-100 transition-opacity hover:bg-red-700 ring-1 ring-white/50"
              title="Delete photo"
              @click.stop="removePhoto(m)">
              <Trash2 :size="11" />
            </button>
            <!-- Public photos can be moved into the personal set. -->
            <button v-if="m.origin === 'public'" type="button"
              class="absolute top-1 right-1 z-10 inline-flex items-center justify-center w-5 h-5 rounded-full
                     bg-black/55 text-white opacity-0 group-hover:opacity-100 transition-opacity hover:bg-black/80
                     disabled:opacity-40"
              :disabled="movingPhoto === m.name"
              title="Move to personal photos"
              @click.stop="moveToPersonal(m)">
              <UserRound :size="11" />
            </button>
            <figcaption
              class="absolute bottom-0 inset-x-0 px-1 py-0.5 text-[8px] font-medium text-white text-center truncate inline-flex items-center justify-center gap-0.5"
              :class="m.origin === 'public' ? 'bg-emerald-600/80' : 'bg-slate-700/80'">
              <component :is="m.origin === 'public' ? Globe : UserRound" :size="8" />
              {{ originLabel(m.origin) }}
            </figcaption>
          </figure>
        </div>
        <div v-else class="h-full flex flex-col items-center justify-center text-center text-[11px] text-muted-fg gap-1">
          <ImagePlus :size="20" class="opacity-50" />
          <span>No photos yet. Drag &amp; drop photos here, use “Add photos”, or “Find photos”.</span>
        </div>
      </div>

      <!-- Ride stats view — advanced ride analytics, computed from the track. -->
      <RideStats v-else-if="!chartCollapsed && bottomView === 'stats'" :stats="rideStats"
        :zones="hrZones" :tiz="tiz" :total-sec="hrTotalSec" />

      <!-- Compare view — pick a similar ride (same route) and race it as a ghost. -->
      <div v-else-if="!chartCollapsed && bottomView === 'compare'" class="flex-1 min-h-0 overflow-y-auto p-2 custom-scrollbar">
        <template v-if="similarRides && similarRides.length">
          <!-- Tick rides to race; pressing Play collapses this to just the racers. -->
          <div v-if="!isPlaying" class="text-[10px] text-muted-fg px-1 pb-0.5">
            Same route by GPS overlap or shared tag — tick rides to race, then press <b>Play</b>.
          </div>
          <!-- Each row: [✓] [colour dot + match badge] [ body ]. While racing the
               body IS the bar (the ride's title is redundant — the top toolbar
               already names the open ride and the colour dot tags this one); when
               idle the body shows the title + date so you can pick. -->
          <label v-for="s in compareRows" :key="s.id" data-compare-row
            class="flex items-center gap-2 px-2.5 py-2 rounded-lg border bg-muted/10 hover:bg-primary/5 transition-colors cursor-pointer mb-1.5"
            :class="selectedCompareIds.includes(s.id) ? 'border-primary' : 'border-border'">
            <input type="checkbox" class="accent-primary shrink-0"
              :checked="selectedCompareIds.includes(s.id)" @change="toggleCompare(s.id)" />
            <span class="flex items-center gap-1.5 shrink-0">
              <span class="inline-block w-2.5 h-2.5 rounded-full"
                :style="{ background: selectedCompareIds.includes(s.id) ? colorOf(s.id) : 'transparent', border: selectedCompareIds.includes(s.id) ? 'none' : '1px solid hsl(var(--border))' }" />
              <!-- Badge shows the GPS route-overlap % (the match strength), so
                   you can see how close a route this really is; tag-only matches
                   show "tag". Colour encodes match type (emerald gps/both, slate
                   tag). The relative day sits next to it for context. -->
              <span class="text-[10px] font-semibold px-1.5 py-0.5 rounded whitespace-nowrap tabular-nums"
                :class="s.matchType === 'tag' ? 'bg-slate-500/20 text-slate-400' : 'bg-emerald-500/20 text-emerald-500'"
                :title="(s.startTime ? new Date(s.startTime).toLocaleDateString() : '') + (s.matchType === 'tag' ? ' · tag match' : ` · ${Math.round(s.score * 100)}% GPS overlap`)">
                {{ s.matchType === 'tag' ? 'tag' : Math.round(s.score * 100) + '%' }}
              </span>
              <span class="text-[9px] text-muted-fg whitespace-nowrap">{{ relativeDay(s.startTime) }}</span>
            </span>
            <!-- Racing: a bar anchored at the CELL CENTRE (= You). Ahead grows it
                 right, behind grows it left; length scales to the field. Fill is
                 coloured by GAP DISTANCE (near = green … far = red), so colour
                 reads "how close is this race" at a glance. `transition-[width]`
                 makes it glide rather than teleport across replay gaps. -->
            <span v-if="isComparing && selectedCompareIds.includes(s.id)" class="relative flex-1 min-w-0 h-6">
              <span class="absolute left-1/2 top-1 bottom-1 w-px bg-border/60 z-0" title="You" />
              <!-- ahead → expand right -->
              <span v-if="aheadOf(s.id)"
                class="absolute top-0 bottom-0 left-1/2 flex items-center justify-start gap-1.5 pl-2.5 pr-2 rounded-full text-[12px] font-bold text-white whitespace-nowrap shadow z-[1] transition-[width] duration-300 ease-out"
                :style="{ width: barMagPct(s.id) + '%', minWidth: 'fit-content', background: gapColor(s.id) }">
                <span v-if="hrOf(s.id) != null" class="tabular-nums" :style="{ color: hrColor(hrOf(s.id)) }">{{ hrOf(s.id) }}♥</span>
                <span class="tabular-nums">{{ gapVsYouText(s.id) }}</span>
              </span>
              <!-- behind → expand left -->
              <span v-else-if="behindOf(s.id)"
                class="absolute top-0 bottom-0 right-1/2 flex items-center justify-end gap-1.5 pr-2.5 pl-2 rounded-full text-[12px] font-bold text-white whitespace-nowrap shadow z-[1] transition-[width] duration-300 ease-out"
                :style="{ width: barMagPct(s.id) + '%', minWidth: 'fit-content', background: gapColor(s.id) }">
                <span v-if="hrOf(s.id) != null" class="tabular-nums" :style="{ color: hrColor(hrOf(s.id)) }">{{ hrOf(s.id) }}♥</span>
                <span class="tabular-nums">{{ gapVsYouText(s.id) }}</span>
              </span>
              <!-- dead even -->
              <span v-else
                class="absolute top-0 bottom-0 left-1/2 -translate-x-1/2 flex items-center gap-1.5 px-3 rounded-full text-[12px] font-bold text-white shadow z-[1]"
                :style="{ background: gapColor(s.id) }">
                <span v-if="hrOf(s.id) != null" class="tabular-nums" :style="{ color: hrColor(hrOf(s.id)) }">{{ hrOf(s.id) }}♥</span>
                <span>even</span>
              </span>
            </span>
            <!-- Idle: title + date so you can choose what to race. -->
            <span v-else class="flex-1 min-w-0 flex items-baseline justify-between gap-2">
              <span class="min-w-0 flex items-baseline gap-1.5">
                <span class="truncate text-[12px] font-medium text-foreground" :title="s.name">{{ s.name }}</span>
                <!-- Rider chip — only when this ride belongs to someone other than
                     the open ride's rider (compare spans all users). -->
                <span v-if="s.riderName && s.riderName !== activity?.riderName"
                  class="shrink-0 inline-flex items-center gap-0.5 text-[9px] px-1 py-0.5 rounded bg-primary/10 text-primary"
                  :title="`Ridden by ${s.riderName}`">
                  <UserRound :size="9" />{{ s.riderName }}
                </span>
              </span>
              <span class="shrink-0 text-[10px] text-muted-fg">
                {{ s.startTime ? new Date(s.startTime).toLocaleDateString() : '' }}
                <template v-if="s.distanceKm"> · {{ s.distanceKm.toFixed(1) }} km</template>
              </span>
            </span>
          </label>
        </template>
        <div v-else class="h-full flex flex-col items-center justify-center text-center text-[11px] text-muted-fg gap-1">
          <Swords :size="20" class="opacity-50" />
          <span>No similar rides found yet. Ride a route twice, or tag rides to group them.</span>
        </div>
      </div>

      <!-- Markers view — filterable table; click a row to jump, trash to delete. -->
      <div v-else-if="!chartCollapsed && bottomView === 'markers'" class="flex-1 min-h-0 flex flex-col">
        <div class="px-2 pt-2 pb-1">
          <input v-model="markerFilter" type="text" placeholder="Filter markers…"
            class="w-full px-2 py-1 text-[12px] rounded-md border border-border bg-background text-foreground focus:outline-none focus:border-primary" />
        </div>
        <div class="flex-1 min-h-0 overflow-y-auto px-2 pb-2 custom-scrollbar">
          <table v-if="sortedMarkers.length" class="w-full text-[11px] border-collapse">
            <thead class="sticky top-0 bg-background z-10">
              <tr class="text-[10px] uppercase tracking-wide text-muted-fg border-b border-border">
                <th class="py-1 pr-2 text-left font-semibold cursor-pointer select-none" @click="setSort('label')">Name<span v-if="markerSort.key === 'label'">{{ markerSort.dir === 'asc' ? ' ▲' : ' ▼' }}</span></th>
                <th class="py-1 pr-2 text-left font-semibold cursor-pointer select-none w-20" @click="setSort('category')">Type<span v-if="markerSort.key === 'category'">{{ markerSort.dir === 'asc' ? ' ▲' : ' ▼' }}</span></th>
                <th class="py-1 w-14"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in sortedMarkers" :key="m.id"
                class="border-b border-border/60 hover:bg-primary/5 cursor-pointer"
                @click="jumpToMarker(m)">
                <td class="py-1.5 pr-2">
                  <div class="flex items-center gap-1.5 min-w-0">
                    <span class="inline-flex items-center justify-center w-5 h-5 rounded-full text-white shrink-0"
                      :style="{ background: markerCat(m.category).color }" v-html="markerIconSvg(m.category, 12)"></span>
                    <span class="min-w-0">
                      <span class="block font-medium text-foreground truncate">{{ m.label || markerCat(m.category).label }}</span>
                      <span v-if="m.description" class="block text-[10px] text-muted-fg truncate">{{ m.description }}</span>
                    </span>
                  </div>
                </td>
                <td class="py-1.5 pr-2 text-muted-fg">{{ markerCat(m.category).label }}</td>
                <td class="py-1.5 text-right whitespace-nowrap">
                  <button class="btn-icon btn-icon-primary" title="Edit marker" @click.stop="editMarker(m)">
                    <Pencil :size="13" />
                  </button>
                  <button class="btn-icon btn-icon-danger" title="Delete marker" @click.stop="removeMarker(m.id)">
                    <Trash2 :size="13" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="h-full flex flex-col items-center justify-center text-center text-[11px] text-muted-fg gap-1 py-6">
            <MapPin :size="20" class="opacity-50" />
            <span>{{ (markers && markers.length) ? 'No markers match the filter.' : 'No markers yet. Middle-click or right-double-click the map to add one.' }}</span>
          </div>
        </div>
      </div>

      <!-- Attributes view — ride events + free-form key/values. -->
      <div v-else-if="!chartCollapsed && bottomView === 'attributes' && activityId"
        class="flex-1 min-h-0 overflow-y-auto p-3 custom-scrollbar">
        <RideAttributesPanel :activity-id="activityId" @jump="(lon, lat) => mapRef?.flyToCoords?.(lon, lat)" />
      </div>

      <!-- Gallery lightbox (native popover). -->
      <div ref="galleryPopoverEl" popover="auto" class="photo-popover" @toggle="onGalleryToggle">
        <figure v-if="galleryPhoto" class="photo-popover__fig">
          <video v-if="isVideoFile(galleryPhoto.name)" :src="galleryPhoto.url" class="photo-popover__img" controls autoplay playsinline />
          <img v-else :src="galleryPhoto.url" :alt="galleryPhoto.name" class="photo-popover__img" />
          <figcaption class="photo-popover__cap">{{ galleryPhoto.name }}</figcaption>
        </figure>
        <button class="photo-popover__close" title="Close (Esc)" @click="closeGalleryPhoto">×</button>
      </div>
    </Pane>
  </Splitpanes>
</template>

<style scoped>
/* Cinematic fade for the auto-skip overlay — quick in, slow out so the
   notice catches the eye but never lingers enough to be in the way. */
.skip-fade-enter-active {
  transition: opacity 220ms ease-out, transform 220ms ease-out;
}
.skip-fade-leave-active {
  transition: opacity 600ms ease-in, transform 600ms ease-in;
}
.skip-fade-enter-from,
.skip-fade-leave-to {
  opacity: 0;
  transform: translate(-50%, -8px);
}

/* Photo overlay — gentle fade + rise/scale as the rider hits the photo's spot. */
.photo-fade-enter-active {
  transition: opacity 450ms ease-out, transform 450ms ease-out;
}
.photo-fade-leave-active {
  transition: opacity 700ms ease-in, transform 700ms ease-in;
}
.photo-fade-enter-from,
.photo-fade-leave-to {
  opacity: 0;
  transform: translateY(14px) scale(0.94) rotate(1deg);
}
</style>
