<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { getTrack, getActivityMedia, activityMediaUrl, isVideoFile,
  getAllMarkers, createMarker, updateMarker, deleteMarker,
  getActivityEvents, setActivityEvents, getEventTypes, type RideEvent, type EventType,
  type Marker, type Highlight } from '@/api/client'
import { markerCategory, markerIconSvg } from '@/markerCategories'
import MarkerEditor from '@/components/MarkerEditor.vue'
import { gearIconSvg } from '@/gearIcons'
import { onKeyStroke } from '@vueuse/core'
import { tileUrl } from '@/lib/mapStyle'
import { bboxOf, inBBox, distanceM } from '@/lib/geo'
import type { HrZone } from '@/composables/useHrZones'
import {
  precomputeSmoothedBearings,
  type ReplayStrategy, type SmoothedBearings,
} from '@/composables/replayStrategies'
import { lerpAngleDeg } from '@/composables/replayCameras'
import {
  simulateReplayPath, samplePath,
  type ReplayPose,
} from '@/composables/replaySimulator'
import type { TileProvider } from '@/api/client'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'

const props = defineProps<{
  activityId: string
  tileProvider?: string
  hoverIndex?: number | null
  colorMode?: 'none' | 'hr' | 'slope'
  hrZones?: HrZone[]
  isPlaying?: boolean
  /** Cinematic camera profile during replay. Picked from ActivityViewer. */
  replayStrategy?: ReplayStrategy
  /**
   * Sub-pixel-precise marker position. When set, drives both the moving dot
   * and the camera follow target so playback glides instead of hopping point
   * to point. ActivityViewer fills this in via rAF + linear interp.
   */
  hoverCoords?: [number, number] | null
  /**
   * Exact fractional track index of the playhead (ActivityViewer's playback
   * clock). Drives the replay camera: sampling the precomputed path at this
   * fractional index lets the camera glide continuously between points rather
   * than stepping per integer index. Null when not playing.
   */
  playFrac?: number | null
  /**
   * Catalog entry for the current basemap. When set, lets us pick raster vs
   * vector style switching paths without hardcoding ids here. Falls back to
   * raster behaviour by default if absent (older callers).
   */
  activeProvider?: TileProvider | null
  /**
   * Whether the camera should track the playback cursor. Now owned by
   * ActivityViewer so the playback toolbar can host the toggle; ActivityMap
   * just reacts to the prop and runs the actual camera work below.
   */
  isFollowing?: boolean
  /**
   * Hillshade + 3D terrain overlay toggle. Independent of the basemap so it
   * works on top of raster OR vector providers (we add the terrain DEM source
   * + hillshade layer ourselves after every style load).
   */
  showHillshade?: boolean
  /** Show geo-matched photo pins on the map (toggled in the playback toolbar). */
  showPhotos?: boolean
  /**
   * Ghost rides to overlay for in-place compare/race: each is a coloured track
   * line. Static (selection-driven) so playback doesn't rebuild them per frame.
   */
  compareLines?: { id: string; color: string; points: { lat: number; lon: number }[] }[] | null
  /**
   * Per-frame marker positions for the ghost rides, keyed by id — the viewer
   * recomputes these from the replay clock (elapsed-time race sync).
   */
  compareCursors?: { id: string; color: string; lat: number; lon: number }[] | null
  /** Auto-detected highlights (OSM passes crossed + named peaks) → map markers. */
  highlights?: { passes: Highlight[]; peaks: Highlight[] } | null
  /** "Tours" overlay: other rides' start points; pin those in the viewport. */
  nearbyTours?: { id: string; name: string; lat: number; lon: number }[]
  /** Gear glyph key for this ride's gear — used as the replay cursor when set. */
  cursorIcon?: string | null
}>()

/**
 * Bubbled up when the user starts an interactive gesture (drag-pan, scroll-
 * zoom, drag-rotate, drag-pitch). ActivityViewer flips `isFollowing` to
 * `false` in response — so once the user grabs the map, the camera stops
 * fighting back. They can re-engage Follow from the toolbar.
 */
const emit = defineEmits<{
  (e: 'userInteracted'): void
  /** A photo pin was clicked — jump the replay cursor to its track index. */
  (e: 'photoJump', index: number): void
}>()

// Local mirror of the parent-owned follow flag. The map watches this for
// transitions and re-centers when the user re-enables follow after manually
// panning. defaults to "follow" so the first paint snaps to the rider.
const isFollowing = computed(() => props.isFollowing ?? true)

const mapEl = ref<HTMLDivElement>()
let map: maplibregl.Map | null = null

// Shared "rider" glyph (a little person) — used by the replay cursor, the
// compare ghost cursors and the personal-photo pin. One source so the three
// can't drift apart.
function riderGlyph(stroke: string, width = 2.4): string {
  return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></svg>`
}
let mapLoaded = false
let cursorMarker: maplibregl.Marker | null = null
let resizeObserver: ResizeObserver | null = null
let trackBounds: maplibregl.LngLatBounds | null = null
let smoothedBearing = 0
// Brief window after play-start / strategy-change where we let an easeTo
// finish before resuming the per-frame jumpTo loop. Stops the camera from
// snapping when the user hits Play from a hovered position somewhere off the
// track or switches cinematic profile mid-play.
let cameraSettlingUntil = 0

// Which kind of basemap is currently loaded in MapLibre. Raster basemaps share
// one `osm-raster` source whose tile URL we swap via `setTiles`; vector
// basemaps require a full `setStyle` to the upstream style JSON, then we
// re-add our overlays after `style.load`. Tracking the mode lets us pick the
// right transition path.
let currentStyleMode: 'raster' | 'vector' = 'raster'
// True while a style transition is in flight — used to gate the style.load
// listener so it only rebuilds overlays for OUR transitions, not for upstream
// vector style internal reloads.
let pendingOverlayRebuild = false
// Camera state captured just before a setStyle so we can restore it after
// the new style loads. Otherwise upstream vector style JSONs (which carry
// their own default center/zoom — typically [0,0] zoom 1, world view) would
// teleport the camera away from the rider mid-replay.
let savedView: { center: [number, number]; zoom: number; bearing: number; pitch: number } | null = null

// Pre-baked bearings + hold hints. Re-computed once whenever the track
// changes; per-point reads in followPoint are O(1).
let bearingData: SmoothedBearings | null = null

// The whole replay camera path, precomputed once per (strategy, track, viewport,
// 3D) and then merely SAMPLED during playback — no per-frame camera state. This
// is the same headless model the simulator/tests assert on, so what plays is what
// is tested. Rebuilt by {@link rebuildReplayPath}; sampled in {@link followPoint}.
let replayPath: ReplayPose[] | null = null

/**
 * Single gate for every map-mutating operation (setData, setPaintProperty,
 * jumpTo, easeTo, etc). Beyond `mapLoaded`, it also checks the canvas has
 * non-zero dimensions — Splitpanes measures asynchronously, so MapLibre's
 * `'load'` event can fire before the renderer transform has a valid
 * projection matrix. Without this, an early setData() triggers MapLibre's
 * internal `_dataHandler → update → getCameraFrustum → fromInvProjectionMatrix`
 * which crashes with "can't access property 0 of null".
 */
function isMapReady(): boolean {
  if (!map || !mapLoaded) return false
  // map.remove() nulls internal methods; getStyle is a cheap "is this map
  // still alive?" check.
  if (typeof map.getStyle !== 'function') return false
  // Container without layout yet → transform.projectionMatrix is null.
  const canvas = map.getCanvas()
  return canvas.clientWidth > 0 && canvas.clientHeight > 0
}

// Off-main-thread segment builder (haversine + slope per pair).
let segmentsWorker: Worker | null = null
let segmentsJobId = 0
// Per-segment features (each a 2-point LineString carrying { hr, slope }), as
// produced by the worker. Cached so the replay trail can be sliced from it and
// thus inherit HR/slope colouring — not just the full-track segments layer.
let segFeatures: GeoJSON.Feature[] = []

// ── Public methods ──────────────────────────────────────────────────────────
function centerTour() {
  if (!map || !trackBounds) return
  flyToTrack(trackBounds, props.tileProvider ?? 'osm')
}

function reset2D() {
  map?.easeTo({ pitch: 0, bearing: 0, duration: 600 })
}

// When the parent flips follow back ON, snap the camera back to the rider.
// Mirrors the old toggleFollow() side effect (which was triggered by clicking
// the magnet button — now hosted in the ActivityViewer playback toolbar).
watch(() => props.isFollowing, (now) => {
  if (!now) return
  if (trackBounds) centerTour()
  const idx = props.hoverIndex ?? 0
  followPoint(idx)
})

function animateToPoint(idx: number) {
  if (!map || !mapLoaded || !points.value?.length) return
  const pt = points.value[idx]
  if (!pt) return
  const lngLat: maplibregl.LngLatLike = [pt.lon, pt.lat]
  const is3D = (props.tileProvider ?? 'osm') === 'terrain'
  const bearing = bearingData?.raw[idx] ?? 0
  // Min zoom 14.5 instead of 13 so the clicked spot reads as "this exact
  // place on the trail" rather than "somewhere over here on the overview
  // map". Cancels any in-flight play camera (cameraSettlingUntil) so this
  // jump always wins.
  cameraSettlingUntil = 0
  map.easeTo({
    center: lngLat,
    zoom: Math.max(map.getZoom(), 14.5),
    pitch: is3D ? 50 : 0,
    bearing: is3D ? bearing : 0,
    duration: 900,
    easing: (t: number) => 1 - Math.pow(1 - t, 3),
  })
}

/** Fly the camera to an arbitrary coordinate (e.g. a marker from the list). */
function flyToCoords(lon: number, lat: number) {
  if (!map || !mapLoaded) return
  cameraSettlingUntil = 0
  map.easeTo({ center: [lon, lat], zoom: Math.max(map.getZoom(), 15), duration: 800, easing: (t: number) => 1 - Math.pow(1 - t, 3) })
}

/** Open the marker editor panel for a given marker (from the Markers list tab). */
function openMarkerEditor(m: Marker) { editingMarker.value = { ...m } }

defineExpose({ centerTour, animateToPoint, flyToCoords, openMarkerEditor })

const { data: points, isPending } = useQuery({
  queryKey: computed(() => ['track', props.activityId]),
  queryFn: () => getTrack(props.activityId),
})

// Geo-matched photos → camera pins on the map (click opens the image).
const { data: mediaItems } = useQuery({
  queryKey: computed(() => ['media', props.activityId]),
  queryFn: () => getActivityMedia(props.activityId),
  staleTime: 60 * 60 * 1000,
})
let photoMarkers: maplibregl.Marker[] = []
// In-app photo viewer — a native top-layer popover (Popover API). Clicking a
// pin shows the photo here (NOT a new tab); ::backdrop click + Esc dismiss.
const lightbox = ref<{ url: string; name: string } | null>(null)
const photoPopoverEl = ref<HTMLElement | null>(null)
function openPhoto(url: string, name: string) {
  lightbox.value = { url, name }
  nextTick(() => (photoPopoverEl.value as unknown as { showPopover?: () => void })?.showPopover?.())
}
function closePhoto() {
  (photoPopoverEl.value as unknown as { hidePopover?: () => void })?.hidePopover?.()
}
function onPhotoToggle(e: Event) {
  if ((e as unknown as { newState?: string }).newState === 'closed') lightbox.value = null
}
function renderPhotoMarkers() {
  if (!isMapReady()) return
  photoMarkers.forEach(m => m.remove())
  photoMarkers = []
  if (props.showPhotos === false) return   // toggle off → no pins
  for (const m of mediaItems.value ?? []) {
    if (m.lat == null || m.lon == null) continue
    const el = document.createElement('div')
    // Distinct marker per origin: public (discovered) vs personal (yours).
    const isPublic = m.origin === 'public'
    el.className = isPublic ? 'map-photo-pin map-photo-pin--public' : 'map-photo-pin map-photo-pin--personal'
    el.title = `${isPublic ? 'Public domain' : 'Personal'} · ${m.name}`
    el.innerHTML = isPublic
      ? '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M2 12h20"/><path d="M12 2a15.3 15.3 0 0 1 0 20a15.3 15.3 0 0 1 0-20"/></svg>'
      : riderGlyph('currentColor', 2.2)
    el.addEventListener('click', (e) => {
      e.stopPropagation()
      if (m.trackIndex != null) emit('photoJump', m.trackIndex)
      openPhoto(activityMediaUrl(props.activityId, m.name), m.name)
    })
    photoMarkers.push(new maplibregl.Marker({ element: el, anchor: 'bottom' })
      .setLngLat([m.lon, m.lat]).addTo(map!))
  }
}

// ── User-placed markers (POIs) ──────────────────────────────────────────────
const qc = useQueryClient()
const { data: markers } = useQuery({
  queryKey: ['markers'],
  queryFn: getAllMarkers,
  staleTime: 5 * 60 * 1000,
})
let markerEls: maplibregl.Marker[] = []
// The marker currently open in the editor panel (null = closed). A local
// editable copy so typing doesn't mutate the query cache until saved.
const editingMarker = ref<Marker | null>(null)
// Esc closes the marker editor (discards an unsaved draft — nothing persisted
// until Save), matching the ✕ button.
onKeyStroke('Escape', (e) => {
  if (editingMarker.value) { e.preventDefault(); editingMarker.value = null }
})

// ── Ride events (typed annotations pinned on THIS ride) ─────────────────────
const { data: rideEvents } = useQuery({
  queryKey: computed(() => ['events', props.activityId]),
  queryFn: () => getActivityEvents(props.activityId),
  staleTime: 60 * 1000,
})
const { data: eventTypes } = useQuery({ queryKey: ['event-types', 'enabled'], queryFn: () => getEventTypes(true) })
const eventTypeByKey = computed(() => {
  const m = new Map<string, EventType>()
  for (const t of eventTypes.value ?? []) if (t.key) m.set(t.key, t)
  return m
})

// ── Rain effect — a little fun: animated downpour while replay passes near a
// WEATHER_RAIN event. ───────────────────────────────────────────────────────
const RAIN_RADIUS_M = 400
const raining = ref(false)
const rainEvents = computed(() =>
  (rideEvents.value ?? []).filter(e => e.type === 'WEATHER_RAIN' && e.lat != null && e.lon != null))
function updateRain(coords: [number, number] | null | undefined) {
  if (!props.isPlaying || !coords || !rainEvents.value.length) { raining.value = false; return }
  const [lon, lat] = coords
  raining.value = rainEvents.value.some(e => distanceM(lat, lon, e.lat!, e.lon!) < RAIN_RADIUS_M)
}
watch(() => props.hoverCoords, c => updateRain(c))
watch(() => props.isPlaying, p => { if (!p) raining.value = false })
let eventEls: maplibregl.Marker[] = []
// Editor for one event. _idx = position in the list (-1 = new draft).
const editingEvent = ref<(RideEvent & { _idx: number }) | null>(null)
// "Add here" chooser (marker vs event) shown at a clicked point.
const addChooser = ref<{ lng: number; lat: number } | null>(null)
onKeyStroke('Escape', () => { editingEvent.value = null; addChooser.value = null })

function renderEvents() {
  if (!isMapReady()) return
  eventEls.forEach(m => m.remove())
  eventEls = []
  const list = rideEvents.value ?? []
  for (let i = 0; i < list.length; i++) {
    const ev = list[i]
    if (ev.lat == null || ev.lon == null) continue   // non-spatial annotation
    const t = eventTypeByKey.value.get(ev.type ?? '')
    const el = document.createElement('div')
    el.className = 'map-event-pin'
    el.style.background = t?.color || '#3b82f6'
    el.title = ev.label || t?.name || ev.type || 'Event'
    el.innerHTML = gearIconSvg(t?.icon || 'MapPin', 13, '#fff')
    el.addEventListener('click', (e) => { e.stopPropagation(); editingEvent.value = { ...ev, _idx: i } })
    eventEls.push(new maplibregl.Marker({ element: el, anchor: 'center' }).setLngLat([ev.lon, ev.lat]).addTo(map!))
  }
}

// Open the marker/event chooser at a clicked point (middle / right-double).
function openAddChooser(lngLat: maplibregl.LngLat) {
  addChooser.value = { lng: lngLat.lng, lat: lngLat.lat }
}
// Chooser → new event draft at that point (label/type from the first enabled type).
function placeEventAt() {
  const c = addChooser.value
  if (!c) return
  const first = (eventTypes.value ?? [])[0]
  editingEvent.value = {
    _idx: -1, type: first?.key ?? 'WEATHER_RAIN', label: '',
    lat: c.lat, lon: c.lng, time: undefined,
  }
  addChooser.value = null
}
function chooseMarker() {
  const c = addChooser.value
  if (!c) return
  placeMarkerAt({ lng: c.lng, lat: c.lat } as maplibregl.LngLat)
  addChooser.value = null
}

async function persistEvents(list: RideEvent[]) {
  await setActivityEvents(props.activityId, list)
  await qc.invalidateQueries({ queryKey: ['events', props.activityId] })
  await qc.invalidateQueries({ queryKey: ['activities'] })
}
async function saveEditingEvent() {
  const e = editingEvent.value
  if (!e) return
  const { _idx, ...ev } = e
  const list = (rideEvents.value ?? []).map(x => ({ ...x }))
  if (_idx >= 0 && _idx < list.length) list[_idx] = ev
  else list.push(ev)
  try { await persistEvents(list) } finally { editingEvent.value = null }
}
async function deleteEditingEvent() {
  const e = editingEvent.value
  if (!e) return
  if (e._idx < 0) { editingEvent.value = null; return }   // unsaved draft
  const list = (rideEvents.value ?? []).map(x => ({ ...x }))
  list.splice(e._idx, 1)
  try { await persistEvents(list) } finally { editingEvent.value = null }
}

function renderMarkers() {
  if (!isMapReady()) return
  markerEls.forEach(m => m.remove())
  markerEls = []
  // Only global markers near this ride (track bbox + 5 km) — don't litter a
  // Sweden ride's map with a Mallorca POI.
  const box = bboxOf((points.value ?? []).map(p => ({ lat: p.lat, lon: p.lon })), 5)
  for (const mk of (markers.value ?? []).filter(mk => inBBox(mk.lat, mk.lon, box))) {
    const cat = markerCategory(mk.category)
    const el = document.createElement('div')
    el.className = 'map-marker-pin'
    el.style.background = cat.color
    el.title = mk.label || cat.label
    el.innerHTML = markerIconSvg(cat)
    el.addEventListener('click', (e) => {
      e.stopPropagation()
      editingMarker.value = { ...mk }
    })
    markerEls.push(new maplibregl.Marker({ element: el, anchor: 'bottom' })
      .setLngLat([mk.lon, mk.lat]).addTo(map!))
  }
}

// Drop a marker at a point and open its editor. Triggered by middle-click or
// right-double-click (single right-click stays free — it just opens nothing).
// Opens a DRAFT (no id) — nothing is persisted until the user clicks Save, so
// cancelling leaves no stray marker behind.
function placeMarkerAt(lngLat: maplibregl.LngLat) {
  editingMarker.value = {
    id: '',
    lat: lngLat.lat, lon: lngLat.lng,
    label: '', description: '', category: 'star',
  } as Marker
}
// Middle mouse button → open the marker/event chooser at that point.
function onMapMouseDown(e: maplibregl.MapMouseEvent) {
  if (e.originalEvent.button === 1) {
    e.preventDefault()                 // stop map pan / browser autoscroll
    e.originalEvent.preventDefault()
    openAddChooser(e.lngLat)
  }
}
// Right double-click → open the chooser. We debounce two contextmenu events; the
// single right-click only suppresses the browser menu.
let lastRightClick = 0
function onMapContextMenu(e: maplibregl.MapMouseEvent) {
  e.preventDefault()                   // suppress the browser context menu
  const now = performance.now()
  if (now - lastRightClick < 450) {
    lastRightClick = 0
    openAddChooser(e.lngLat)
  } else {
    lastRightClick = now
  }
}

async function saveEditingMarker() {
  const m = editingMarker.value
  if (!m) return
  try {
    if (m.id) {
      await updateMarker(m.id, {
        label: m.label, description: m.description, category: m.category,
      })
    } else {
      // Draft → first persistence happens here, on Save.
      await createMarker({
        lat: m.lat, lon: m.lon,
        label: m.label, description: m.description, category: m.category,
      })
    }
    await qc.invalidateQueries({ queryKey: ['markers'] })
  } finally {
    editingMarker.value = null
  }
}
async function deleteEditingMarker() {
  const m = editingMarker.value
  if (!m) return
  // Unsaved draft → just discard, nothing to delete server-side.
  if (!m.id) { editingMarker.value = null; return }
  try {
    await deleteMarker(m.id)
    await qc.invalidateQueries({ queryKey: ['markers'] })
  } finally {
    editingMarker.value = null
  }
}

// ── Tile / style setup ──────────────────────────────────────────────────────

/**
 * Warm the browser tile cache for the whole route corridor (+ a 1-tile margin)
 * BEFORE replay, so the 3D camera never stalls fetching a tile mid-fly. Covers
 * the raster basemap and — in 3D / hillshade — the terrain DEM tiles, across the
 * zoom band the replay cameras use. These are localhost /api/tiles hits (already
 * server-warmed at import); this pulls them into the browser cache + lets
 * MapLibre keep them resident via the large maxTileCacheSize.
 */
let prefetchedFor: string | null = null
function prefetchRouteTiles() {
  const pts = points.value
  if (!pts?.length) return
  // Re-prefetch only when the ride or the relevant layers actually change.
  const wants3D = (props.tileProvider ?? 'osm') === 'terrain' || props.showHillshade === true
  const isVector = props.activeProvider?.type === 'vector'
  const rasterId = (props.tileProvider ?? 'osm') === 'terrain' ? 'osm' : (props.tileProvider ?? 'osm')
  const key = `${props.activityId}|${rasterId}|${wants3D}|${isVector}`
  if (prefetchedFor === key) return
  prefetchedFor = key

  const zooms = [13, 14, 15]
  const tiles = new Set<string>()
  const step = Math.max(1, Math.floor(pts.length / 400))   // sample — adjacent points share tiles
  for (const z of zooms) {
    const n = 2 ** z
    for (let i = 0; i < pts.length; i += step) {
      const p = pts[i]
      if (typeof p.lon !== 'number' || typeof p.lat !== 'number') continue
      const x = Math.floor(((p.lon + 180) / 360) * n)
      const latR = (p.lat * Math.PI) / 180
      const y = Math.floor(((1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2) * n)
      for (let dx = -1; dx <= 1; dx++) {
        for (let dy = -1; dy <= 1; dy++) {
          const tx = x + dx, ty = y + dy
          if (tx < 0 || ty < 0 || tx >= n || ty >= n) continue
          tiles.add(`${z}/${tx}/${ty}`)
        }
      }
    }
  }
  const base = window.location.origin
  let n = 0
  for (const t of tiles) {
    if (n++ > 1500) break    // safety cap for very long routes
    const [z, x, y] = t.split('/')
    if (!isVector) void fetch(`${base}/api/tiles/${z}/${x}/${y}.png?providerid=${rasterId}`, { cache: 'force-cache' }).catch(() => {})
    if (wants3D) void fetch(`${base}/api/tiles/${z}/${x}/${y}.png?providerid=terrain`, { cache: 'force-cache' }).catch(() => {})
  }
}

// (the previous single-provider buildStyle() was replaced by buildRasterStyle()
// below — it now takes a providerId so we can switch basemaps via setTiles or
// setStyle without re-creating the whole map.)

/**
 * Snapshot the live camera right before a setStyle so we can restore it once
 * the new style finishes loading. Upstream vector style JSONs (OpenFreeMap,
 * MapTiler, etc.) carry their own default center / zoom / pitch / bearing —
 * typically `[0,0]` zoom 1, i.e. a world view — and MapLibre applies those
 * fields unconditionally on setStyle. Without this snapshot, switching
 * basemaps mid-replay would teleport the camera off the rider.
 */
function captureView() {
  if (!map) { savedView = null; return }
  const c = map.getCenter()
  savedView = {
    center: [c.lng, c.lat],
    zoom: map.getZoom(),
    bearing: map.getBearing(),
    pitch: map.getPitch(),
  }
}

function applyProvider(provider: string) {
  if (!map || !mapLoaded) return

  // Vector providers carry a styleUrl; we fall back to id-based routing when
  // the catalog hasn't loaded yet so the initial paint still works.
  const isVector = props.activeProvider?.type === 'vector'
  // Legacy: the 'terrain' basemap id is treated as "OSM raster + hillshade on"
  // for backward-compat with users who saved that as their basemap setting.
  // Going forward, hillshade is independently controlled via the showHillshade
  // prop and works on top of any basemap.
  const isTerrainLegacy = provider === 'terrain'

  if (isVector && props.activeProvider?.styleUrl) {
    captureView()
    pendingOverlayRebuild = true
    currentStyleMode = 'vector'
    // diff: false skips MapLibre's "try to patch old style → new style"
    // path. That path keeps emitting "Cannot read properties of undefined
    // (reading '_checkLoaded')" warnings when our overlay sources are
    // mid-teardown. Full rebuild is what MapLibre falls back to anyway.
    map.setStyle(props.activeProvider.styleUrl, { diff: false })
    return
  }

  if (currentStyleMode === 'vector') {
    captureView()
    pendingOverlayRebuild = true
    currentStyleMode = 'raster'
    map.setStyle(buildRasterStyle(isTerrainLegacy ? 'osm' : provider), { diff: false })
    return
  }

  // Raster → raster: just swap the basemap source's tile URL.
  const basemapId = isTerrainLegacy ? 'osm' : provider
  const src = map.getSource('osm-raster') as maplibregl.RasterTileSource | undefined
  if (src && (src as any).setTiles) {
    (src as any).setTiles([tileUrl(basemapId)])
  }

  // Hillshade is now driven independently — applyHillshade is called from a
  // watcher on showHillshade. The legacy 'terrain' provider also forces it on
  // via that same watcher (see below).
  applyHillshade(props.showHillshade === true || isTerrainLegacy)
}

/**
 * Make sure the terrain DEM sources + hillshade layer exist on the current
 * style, regardless of whether the basemap is raster or vector. Idempotent:
 * skips anything already present, so it's safe to call from both the initial
 * `map.on('load')` and after every `style.load`. Without this, vector
 * basemaps (OpenFreeMap etc.) would have no DEM source to drive hillshade
 * or 3D-extruded terrain.
 */
function addTerrainOverlays() {
  if (!map) return
  if (!map.getSource('terrain-dem-hillshade')) {
    map.addSource('terrain-dem-hillshade', {
      type: 'raster-dem', tiles: [tileUrl('terrain')], tileSize: 256,
      encoding: 'terrarium', maxzoom: 15,
      attribution: 'Terrain © Mapzen',
    })
  }
  if (!map.getSource('terrain-dem-3d')) {
    map.addSource('terrain-dem-3d', {
      type: 'raster-dem', tiles: [tileUrl('terrain')], tileSize: 256,
      encoding: 'terrarium', maxzoom: 15,
    })
  }
  if (!map.getLayer('hillshade')) {
    map.addLayer({
      id: 'hillshade', type: 'hillshade', source: 'terrain-dem-hillshade',
      layout: { visibility: 'none' },
      paint: {
        'hillshade-exaggeration': 0.0,
        'hillshade-illumination-direction': 335,
        'hillshade-shadow-color': '#3D2B1F',
        'hillshade-highlight-color': '#FAFAF8',
        'hillshade-accent-color': '#5C4033',
      },
    })
  }
}

/**
 * Toggle hillshade + 3D-extruded terrain on top of whatever basemap is loaded.
 * Ensures the DEM sources exist first (no-op if they do) so this works for
 * both raster and vector basemaps.
 */
function applyHillshade(enabled: boolean) {
  if (!isMapReady()) return
  const m = map!
  addTerrainOverlays()
  if (m.getLayer('hillshade')) {
    m.setLayoutProperty('hillshade', 'visibility', enabled ? 'visible' : 'none')
    // Higher hillshade exaggeration deepens the shadow contrast — alpine
    // ridgelines + valley walls read more dramatically. 0.45 was too timid
    // on the OSM/Carto basemaps where the terrain barely poked through;
    // 0.65 gives proper relief while still looking like a map, not a render.
    m.setPaintProperty('hillshade', 'hillshade-exaggeration', enabled ? 0.65 : 0.0)
  }
  // NOTE: deliberately NO 3D `setTerrain` mesh here. The extruded DEM at high
  // exaggeration blacked out the map on some GPUs and let the pitched replay
  // camera clip under the terrain. The 2D hillshade layer gives the relief
  // payoff reliably; always clear any terrain so a stale 3D mesh can't linger.
  m.setTerrain(null as unknown as maplibregl.TerrainSpecification)
}

/**
 * Builds the minimal raster style used when a raster basemap is selected.
 * Single OSM-raster source pointed at the provider's tile URL, plus the
 * terrain DEM sources used by the hillshade overlay when terrain is active.
 * Pulled out so applyProvider can rebuild this whenever we transition back
 * from a vector basemap.
 */
function buildRasterStyle(providerId: string): maplibregl.StyleSpecification {
  return {
    version: 8,
    glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
    sources: {
      'osm-raster': {
        type: 'raster',
        tiles: [tileUrl(providerId)],
        tileSize: 256,
        attribution: props.activeProvider?.attribution ?? '© OpenStreetMap contributors',
        maxzoom: 19,
      },
      'terrain-dem-hillshade': {
        type: 'raster-dem', tiles: [tileUrl('terrain')], tileSize: 256,
        encoding: 'terrarium', maxzoom: 15,
        attribution: 'Terrain © Mapzen',
      },
      'terrain-dem-3d': {
        type: 'raster-dem', tiles: [tileUrl('terrain')], tileSize: 256,
        encoding: 'terrarium', maxzoom: 15,
      },
    },
    layers: [
      // Background under the tiles: fast panning / per-frame replay moves can
      // leave 1px sub-pixel seams between raster tiles. With no background the
      // gap is the white canvas → flickering white lines. A land-toned (or dark,
      // per basemap) background turns those seams into an invisible match.
      { id: 'bg', type: 'background', paint: { 'background-color': props.activeProvider?.isDark ? '#15171a' : '#e8e6e1' } },
      // raster-fade-duration 0 kills the cross-tile fade that flashes during
      // fast movement (the white-flicker the user saw isn't tile load — it's the fade).
      { id: 'osm-raster-layer', type: 'raster', source: 'osm-raster', paint: { 'raster-fade-duration': 0 } },
      {
        id: 'hillshade', type: 'hillshade', source: 'terrain-dem-hillshade',
        layout: { visibility: 'none' },
        paint: {
          'hillshade-exaggeration': 0.0,
          'hillshade-illumination-direction': 335,
          'hillshade-shadow-color': '#3D2B1F',
          'hillshade-highlight-color': '#FAFAF8',
          'hillshade-accent-color': '#5C4033',
        },
      },
    ],
  }
}

function initMap(provider: string) {
  if (!mapEl.value) return
  if (map) { map.remove(); mapLoaded = false }

  // Start in raster mode regardless of the requested provider — if the user's
  // saved choice is a vector basemap, applyProvider() below will swap to
  // setStyle right after mapLoaded fires. This keeps the initial paint fast
  // (no waiting on an external style JSON fetch) and lets the rest of the
  // pipeline assume our raster-overlay setup is the default.
  currentStyleMode = 'raster'
  map = new maplibregl.Map({
    container: mapEl.value,
    style: buildRasterStyle(provider === 'terrain' ? 'osm' : provider),
    center: [10.0, 51.0],
    zoom: 5,
    pitch: 0,
    bearing: 0,
    // No tile cross-fade — during fast pan / replay the fade renders as white
    // flicker over already-cached tiles.
    fadeDuration: 0,
    // Keep a large tile cache so tiles loaded along the route aren't evicted as
    // the replay camera flies past — combined with prefetchRouteTiles() this
    // keeps the corridor resident for smooth 3D replay.
    maxTileCacheSize: 2000,
    refreshExpiredTiles: false,
    // Default MapLibre cap is 60°. Helicopter strategy goes up to 78° for the
    // side-chase angle, so we raise the cap. Chase still uses 58°, so this is
    // only relevant when the helicopter scene is active.
    maxPitch: 85,
  })

  map.addControl(new maplibregl.NavigationControl(), 'top-right')

  map.on('load', () => {
    mapLoaded = true
    applyProvider(provider)
    addTrackOverlays()
    // Seed the initial replay path here — by now the MapLibre transform
    // matrices exist and project()/unproject() are safe. The strategy watcher
    // (non-immediate) handles subsequent changes.
    rebuildReplayPath()
    if (props.isPlaying) nextTick(() => transitionToStrategy(props.hoverIndex ?? 0))
    renderPhotoMarkers()
    renderMarkers()
    renderEvents()
    renderHighlightMarkers()
    renderNearbyTours()
    renderGhostLines()
    // Keep the off-screen ghost arrows pinned to the edge as the camera moves.
    map!.on('move', updateGhostEdges)
    // Re-pin the "Tours" overlay to whatever rides are now in view.
    map!.on('moveend', renderNearbyTours)
    map!.on('mousedown', onMapMouseDown)
    map!.on('contextmenu', onMapContextMenu)
  })

  // Only DELIBERATE panning disengages Follow — zoom + rotate + pitch are
  // viewing adjustments, not navigation intent. Drag = "I want the camera
  // somewhere else"; scroll-zoom = "I want a closer / wider look at the
  // current spot". `dragstart` fires on left-button drag-pan only.
  map.on('dragstart', (e: any) => {
    if (e && e.originalEvent) emit('userInteracted')
  })

  // Re-add our track overlays after any style swap (raster ↔ vector). MapLibre
  // wipes all non-style sources/layers on setStyle; we restore ours here.
  // We also restore the camera — upstream vector styles include their own
  // default center/zoom and would otherwise zoom out to a world view mid-replay.
  map.on('style.load', () => {
    if (!pendingOverlayRebuild) return
    pendingOverlayRebuild = false
    if (savedView) {
      map!.jumpTo(savedView)
      savedView = null
    }
    addTrackOverlays()
    // Re-add the DEM sources + hillshade layer too — the freshly-loaded
    // upstream vector style won't have them. Then re-apply current hillshade
    // visibility / 3D state so the user sees the same overlays they had
    // before the basemap switch.
    addTerrainOverlays()
    applyHillshade(props.showHillshade === true || props.tileProvider === 'terrain')
    if (points.value?.length) renderTrack()
    applyColorMode(props.colorMode ?? 'none')
    renderGhostLines()
  })
}

/**
 * Adds the per-track sources (line, segments) and the layers stacked on top
 * of the basemap (casing, line, trail glow, trail fill, start/end markers,
 * HR/slope coloured segments). Idempotent-ish: relies on the caller invoking
 * only after a style load when MapLibre has discarded the previous overlays.
 */
function addTrackOverlays() {
  if (!map) return
  // ── Idempotency guards ───────────────────────────────────────────────────
  // This function is called from both the initial map.on('load') AND from
  // style.load after every setStyle. MapLibre's setStyle does style-diffing
  // and may preserve sources across the swap; if we addSource a second time
  // it throws "already exists" and the rest of the function (the layers)
  // never runs — that's why the track was invisible on vector basemaps when
  // the user's saved provider was already vector at app start. Guard each
  // add with a getSource / getLayer check.
  if (!map.getSource('track')) {
    map.addSource('track', {
      type: 'geojson',
      tolerance: 1.5,
      maxzoom: 16,
      buffer: 32,
      // lineMetrics drives per-vertex line-progress so we can trim the trail
      // layers by fractional distance below — without this, line-trim-offset
      // silently no-ops. Cost is ~2× per-vertex memory; negligible for one
      // line feature.
      lineMetrics: true,
      data: { type: 'FeatureCollection', features: [] },
    })
  }
  if (!map.getSource('track-segments')) {
    map.addSource('track-segments', {
      type: 'geojson',
      tolerance: 0.5,
      maxzoom: 16,
      buffer: 32,
      data: { type: 'FeatureCollection', features: [] },
    })
  }
  // Trail (already-traveled portion). MapLibre 5.24 doesn't ship the
  // `line-trim-offset` paint property — calls to setPaintProperty for it
  // crash inside PaintProperties.getValue with "undefined.value". So we use
  // a separate source whose data is the [0..progress] slice of the track,
  // and the layers render whatever's currently in the source. Works on every
  // MapLibre version including the 4.x branch.
  if (!map.getSource('track-trail')) {
    map.addSource('track-trail', {
      type: 'geojson',
      tolerance: 1.5,
      maxzoom: 16,
      buffer: 32,
      data: { type: 'FeatureCollection', features: [] },
    })
  }

  if (!map.getLayer('track-casing')) {
    map.addLayer({
      id: 'track-casing', type: 'line', source: 'track',
      filter: ['==', '$type', 'LineString'],
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': '#ffffff', 'line-width': 6, 'line-opacity': 0.85 },
    })
  }
  if (!map.getLayer('track-line')) {
    map.addLayer({
      id: 'track-line', type: 'line', source: 'track',
      filter: ['==', '$type', 'LineString'],
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': '#3b82f6', 'line-width': 4, 'line-opacity': 1 },
    })
  }
    // ── Trail layers (already-traveled portion during replay) ───────────────
    // Two stacked layers — a wide blurred halo for the glow, then a bold solid
    // line on top. Both render only the [0, progress] portion via
    // line-trim-offset, driven by hoverIndex below. Start fully trimmed so the
    // trail is invisible until playback actually progresses.
  // Trail layers — read from the dedicated `track-trail` source. The source
  // starts empty; `setTrailProgress(p)` updates it with the [0..p] slice of
  // the live track. Layers render whatever's there, no trim magic needed.
  if (!map.getLayer('track-trail-glow')) {
    map.addLayer({
      id: 'track-trail-glow', type: 'line', source: 'track-trail',
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: {
        // Subtle emphasis on the ridden part — a soft halo, not a spotlight.
        'line-color': '#fbbf24',
        'line-width': 9,
        'line-blur': 4,
        'line-opacity': 0.32,
      },
    })
  }
  if (!map.getLayer('track-trail-fill')) {
    map.addLayer({
      id: 'track-trail-fill', type: 'line', source: 'track-trail',
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: {
        // Just a touch thicker than the base line (4px) — bold but restrained.
        'line-color': '#f59e0b',
        'line-width': 5,
        'line-opacity': 1,
      },
    })
  }
  if (!map.getLayer('track-start')) {
    map.addLayer({
      id: 'track-start', type: 'circle', source: 'track',
      filter: ['==', ['get', 'point'], 'start'],
      paint: { 'circle-radius': 7, 'circle-color': '#22c55e', 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 2 },
    })
  }
  if (!map.getLayer('track-end')) {
    map.addLayer({
      id: 'track-end', type: 'circle', source: 'track',
      filter: ['==', ['get', 'point'], 'end'],
      paint: { 'circle-radius': 7, 'circle-color': '#ef4444', 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 2 },
    })
  }
  if (!map.getLayer('track-segments-casing')) {
    map.addLayer({
      id: 'track-segments-casing', type: 'line', source: 'track-segments',
      layout: { 'line-join': 'round', 'line-cap': 'round', 'visibility': 'none' },
      paint: { 'line-color': '#ffffff', 'line-width': 5, 'line-opacity': 0.7 },
    })
  }
  if (!map.getLayer('track-segments-fill')) {
    map.addLayer({
      id: 'track-segments-fill', type: 'line', source: 'track-segments',
      layout: { 'line-join': 'round', 'line-cap': 'round', 'visibility': 'none' },
      paint: { 'line-color': '#3b82f6', 'line-width': 3, 'line-opacity': 0.95 },
    })
  }

  // Force our overlay layers above any basemap labels / building extrusions.
  // moveLayer(id) with no `beforeId` puts it at the very top — drawn last,
  // visible above everything else. Order of these calls matters: each push
  // ends up above the previous, so put trail glow first (deepest) and the
  // start/end circles last (highest priority). This is essential on vector
  // basemaps where there are 60+ upstream layers including road casings
  // that would otherwise hide our track underneath.
  for (const id of [
    'track-trail-glow',
    'track-casing',
    'track-line',
    'track-segments-casing',
    'track-segments-fill',
    'track-trail-fill',
    'track-start',
    'track-end',
  ]) {
    if (map.getLayer(id)) map.moveLayer(id)
  }

  if (points.value?.length) renderTrack()
  applyColorMode(props.colorMode ?? 'none')
}

function applyColorMode(mode: string) {
  if (!isMapReady()) return
  const m = map!
  const showColored = mode !== 'none'
  m.setLayoutProperty('track-casing',           'visibility', showColored ? 'none' : 'visible')
  m.setLayoutProperty('track-line',             'visibility', showColored ? 'none' : 'visible')
  m.setLayoutProperty('track-segments-casing',  'visibility', showColored ? 'visible' : 'none')
  m.setLayoutProperty('track-segments-fill',    'visibility', showColored ? 'visible' : 'none')
  if (showColored) {
    // `coalesce(get, 0)` keeps `step` from ever seeing null — the trail's
    // transient fallback LineString (built before the worker's per-segment
    // features arrive) has no hr/slope property, and step on a null input
    // throws "Expected value to be of type number" inside the GL worker.
    const hrVal: maplibregl.ExpressionSpecification = ['coalesce', ['get', 'hr'], 0]
    const slopeVal: maplibregl.ExpressionSpecification = ['coalesce', ['get', 'slope'], 0]
    let colorExpr: maplibregl.ExpressionSpecification
    if (mode === 'hr') {
      const z = props.hrZones && props.hrZones.length === 5 ? props.hrZones : null
      if (z) {
        colorExpr = [
          'step', hrVal,
          '#9ca3af',
          z[0].lo, z[0].color,
          z[1].lo, z[1].color,
          z[2].lo, z[2].color,
          z[3].lo, z[3].color,
          z[4].lo, z[4].color,
        ]
      } else {
        colorExpr = ['step', hrVal, '#9ca3af', 1, '#3b82f6', 120, '#10b981', 140, '#f59e0b', 160, '#ef4444']
      }
    } else {
      colorExpr = ['step', slopeVal, '#1d4ed8', -3, '#3b82f6', 1, '#10b981', 6, '#f59e0b', 12, '#ef4444']
    }
    m.setPaintProperty('track-segments-fill', 'line-color', colorExpr)
    // The bold ridden trail must share the colouring — its sliced segment
    // features carry the same { hr, slope } props. Glow + fill both follow so
    // the ridden portion reads as HR/slope-coloured, not amber.
    m.setPaintProperty('track-trail-fill', 'line-color', colorExpr)
    m.setPaintProperty('track-trail-glow', 'line-color', colorExpr)
  } else {
    // Plain mode: restore the warm amber "trail" highlight.
    m.setPaintProperty('track-trail-fill', 'line-color', '#f59e0b')
    m.setPaintProperty('track-trail-glow', 'line-color', '#fbbf24')
  }
}

// ── Angle helpers ───────────────────────────────────────────────────────────
// (Angle helpers moved to composables/replayCameras.ts — exported lerpAngleDeg
//  is reused here for the hover/scrub mode below.)

// ── The camera (precompute + sample) ───────────────────────────────────────
//
// Everything strategy-specific lives in the cameras/simulator composables. The
// whole camera trajectory is computed up front by `simulateReplayPath` (the same
// model the unit tests assert on), and ActivityMap is the thin layer that
// rebuilds that path on change, samples it per tick during playback, and bakes
// the screen offset into MapLibre. No per-frame camera state lives here.

/**
 * Precompute the whole camera path for the active strategy. Cheap (a few trig
 * ops per point) and idempotent — call it whenever an input changes (track,
 * strategy, viewport, 3D). Sampling it in followPoint is then O(1) per tick.
 */
function rebuildReplayPath(): void {
  if (!isMapReady() || !points.value?.length || !bearingData) { replayPath = null; return }
  const m = map!
  replayPath = simulateReplayPath(props.replayStrategy ?? 'helicopter', points.value, {
    bearings: bearingData,
    viewportPxW: m.getCanvas().clientWidth,
    viewportPxH: m.getCanvas().clientHeight,
    is3D: (props.tileProvider ?? 'osm') === 'terrain',
  })
}

/**
 * Apply one precomputed pose. The only place we touch the renderer. `ease`
 * (used for strategy-switch / play-start transitions) animates over durationMs
 * and locks the per-frame loop meanwhile; otherwise it's an instant jump.
 */
function applyPose(pose: ReplayPose, ease = false, durationMs = 0): void {
  if (!map) return
  // jumpTo/easeTo take the screen CENTER; bake the rider-below-centre pixel
  // offset into the lngLat via project / shift / unproject.
  let center: maplibregl.LngLatLike = [pose.lng, pose.lat]
  if (pose.offsetY !== 0) {
    const cPx = map.project(center)
    const ch = map.getCanvas().clientHeight
    // Skip near the horizon, where project() blows up and unprojecting a shifted
    // pixel would fling the centre thousands of px away (rider off-screen).
    if (Number.isFinite(cPx.y) && cPx.y > 0 && cPx.y < ch) {
      cPx.y -= pose.offsetY
      const u = map.unproject(cPx)
      center = [u.lng, u.lat]
    }
  }
  if (ease) {
    map.easeTo({
      center, zoom: pose.zoom, pitch: pose.pitch, bearing: pose.bearing,
      duration: durationMs,
      easing: (t: number) => t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2,
    })
    cameraSettlingUntil = performance.now() + durationMs
  } else {
    map.jumpTo({ center, zoom: pose.zoom, pitch: pose.pitch, bearing: pose.bearing })
  }
}

/** Sample the precomputed path at `fracIdx` and apply the pose (instant). */
function sampleCameraAt(fracIdx: number): void {
  if (!replayPath) rebuildReplayPath()
  const pose = replayPath ? samplePath(replayPath, fracIdx) : null
  if (pose) applyPose(pose)
}

/** Duration of the eased transition when the strategy changes / playback starts. */
const TRANSITION_MS = 600

/**
 * Ease into the active strategy's pose at `idx` — the entry transition on
 * play-start and the cross-fade when the strategy changes mid-replay. Rebuilds
 * the path first so the target reflects the new mode.
 */
function transitionToStrategy(idx: number): void {
  rebuildReplayPath()
  const pose = replayPath ? samplePath(replayPath, idx) : null
  if (pose) applyPose(pose, true, TRANSITION_MS)
}

function followPoint(idx: number): void {
  if (!isMapReady() || !points.value?.length) return
  const pt = points.value[idx]
  if (!pt) return

  if (props.isPlaying) {
    // During playback the camera is driven by the 60 fps playFrac watcher
    // (smooth, fractional sampling). Only fall back to integer-index sampling
    // here if no fractional playhead is being streamed.
    if (props.playFrac != null) return
    if (performance.now() < cameraSettlingUntil) return
    sampleCameraAt(idx)
    return
  }

  // Hover / scrub mode is independent of the playback camera strategy —
  // gentle "only move when the marker leaves a safe central viewport zone"
  // ease so scrubbing along the chart doesn't constantly fight the user.
  const m = map!
  const aheadIdx = Math.min(points.value.length - 1, idx + 0)
  const aheadPt = points.value[aheadIdx] ?? pt
  const targetCenter: maplibregl.LngLatLike = [
    pt.lon * 0.35 + aheadPt.lon * 0.65,
    pt.lat * 0.35 + aheadPt.lat * 0.65,
  ]
  const is3D = (props.tileProvider ?? 'osm') === 'terrain'
  const rawAhead = bearingData?.raw[aheadIdx] ?? 0
  smoothedBearing = lerpAngleDeg(smoothedBearing || m.getBearing(), is3D ? rawAhead : 0, 0.35)

  const canvas = m.getCanvas()
  const markerPx = m.project([pt.lon, pt.lat])
  const marginX = canvas.clientWidth * 0.15
  const marginY = canvas.clientHeight * 0.15
  const inView = markerPx.x > marginX && markerPx.x < canvas.clientWidth - marginX
    && markerPx.y > marginY && markerPx.y < canvas.clientHeight - marginY
  if (inView) return

  const targetZoom = m.getZoom() < 11 ? 13 : m.getZoom()
  m.easeTo({
    center: targetCenter,
    bearing: is3D ? smoothedBearing : m.getBearing(),
    zoom: targetZoom,
    pitch: is3D ? 50 : m.getPitch(),
    duration: 300,
    easing: (t: number) => 1 - Math.pow(1 - t, 3),
  })
}

// ── Mount/unmount ───────────────────────────────────────────────────────────
onMounted(() => {
  initMap(props.tileProvider ?? 'osm')
  resizeObserver = new ResizeObserver(() => {
    map?.resize()
    // If the canvas just became non-zero (Splitpanes finished its layout
    // pass AFTER MapLibre's load event), the initial renderTrack call was
    // skipped by isMapReady. Re-run it now that the transform is valid.
    if (points.value?.length) renderTrack()
    // Viewport changed → the path's deadzone / heli scene extents depend on it,
    // so recompute.
    rebuildReplayPath()
  })
  if (mapEl.value) resizeObserver.observe(mapEl.value)

  segmentsWorker = new Worker(
    new URL('../workers/trackSegments.worker.ts', import.meta.url),
    { type: 'module' },
  )
  segmentsWorker.onmessage = (e: MessageEvent<{ id: number; features: GeoJSON.Feature[] }>) => {
    if (e.data.id !== segmentsJobId) return
    if (!isMapReady()) return
    segFeatures = e.data.features
    const segSrc = map!.getSource('track-segments') as maplibregl.GeoJSONSource | undefined
    segSrc?.setData({ type: 'FeatureCollection', features: e.data.features })
    // A replay may already be mid-way (segments arrived after playback started);
    // rebuild the trail now so the ridden portion picks up the coloured segments.
    if (props.hoverIndex != null) setTrailProgress(props.hoverIndex / Math.max(1, (points.value?.length ?? 1) - 1))
  }
})

onUnmounted(() => {
  // Flip mapLoaded FIRST so any in-flight watcher callback (parent's rAF
  // tick still mutating activeIndex/hoverCoords during this teardown frame)
  // early-returns before touching map.
  mapLoaded = false
  replayPath = null
  photoMarkers.forEach(m => m.remove())
  photoMarkers = []
  clearGhosts()
  clearHighlights()
  clearNearbyTours()
  markerEls.forEach(m => m.remove())
  markerEls = []
  eventEls.forEach(m => m.remove())
  eventEls = []
  resizeObserver?.disconnect()
  cursorMarker?.remove()
  map?.remove()
  map = null
  segmentsWorker?.terminate()
  segmentsWorker = null
})

// ── Watchers ────────────────────────────────────────────────────────────────
watch(() => props.tileProvider, (p) => applyProvider(p ?? 'osm'))
watch(() => props.colorMode, (m) => applyColorMode(m ?? 'none'))
watch(() => props.hrZones, () => applyColorMode(props.colorMode ?? 'none'), { deep: true })
// Hillshade is independent of basemap — applies on top of raster or vector.
// The legacy 'terrain' provider still forces it on for backwards-compat with
// users who saved that as their basemap.
watch(() => [props.showHillshade, props.tileProvider], () => {
  applyHillshade(props.showHillshade === true || props.tileProvider === 'terrain')
  // 3D/basemap change → warm the corridor for the new layers (DEM tiles, etc.).
  prefetchRouteTiles()
})

// ── Camera lifecycle ───────────────────────────────────────────────────────
// The path is precomputed per strategy and SAMPLED during playback. On a
// strategy change (and on play-start) we rebuild the path and ease a transition
// into the new mode's pose — so switching mid-replay cross-fades rather than
// snapping. No strategy-specific code lives here; see the simulator/cameras.

// IMPORTANT: NOT `immediate: true`. The map isn't created until onMounted, and an
// immediate watcher would compute a path against a half-constructed map (null
// transform). The initial path is built in the map.on('load') callback instead.
watch(() => props.replayStrategy, () => {
  if (!mapLoaded) return  // initial setup happens in onMounted; skip until ready
  // Rebuild + ease into the new strategy, whether playing or paused (a paused
  // switch still re-frames zoom/pitch/bearing). nextTick so a play-start that
  // flips strategy + isPlaying together sees the propagated hoverIndex.
  nextTick(() => transitionToStrategy(props.hoverIndex ?? 0))
})

watch(() => props.isPlaying, (playing) => {
  if (!mapLoaded || !playing) return
  // Ease from wherever the map is into the strategy's entry pose. nextTick: on
  // Play, ActivityViewer sets activeIndex then isPlaying in one flush, so the
  // prop-bound hoverIndex hasn't propagated yet when this watcher first runs.
  nextTick(() => transitionToStrategy(props.hoverIndex ?? 0))
})

// Re-pin photos whenever the manifest changes (new activity, discover, save)
// or the show-photos toggle flips.
watch(mediaItems, () => renderPhotoMarkers())
watch(() => props.showPhotos, () => renderPhotoMarkers())
watch([markers, points], () => renderMarkers())
watch([rideEvents, eventTypes], () => renderEvents())

watch(points, (pts) => {
  // Reset every piece of per-track state so nothing leaks across the switch.
  // Without these, the previous activity's track line stays painted during
  // the fetch gap (TanStack flips data to undefined when queryKey changes),
  // and a stale `cameraSettlingUntil` / old path block the new track's camera
  // from re-anchoring → user sees the new activity loaded but the camera sitting
  // on the old activity's last viewpoint until the timer expires.
  bearingData = pts?.length ? precomputeSmoothedBearings(pts) : null
  cameraSettlingUntil = 0
  smoothedBearing = 0
  replayPath = null   // drop the old activity's path; rebuilt from the new track below

  if (!pts?.length) {
    // Wipe the visible track immediately rather than letting the previous
    // one linger on screen during the fetch.
    if (isMapReady()) {
      const src = map!.getSource('track') as maplibregl.GeoJSONSource | undefined
      const segSrc = map!.getSource('track-segments') as maplibregl.GeoJSONSource | undefined
      const trailSrc = map!.getSource('track-trail') as maplibregl.GeoJSONSource | undefined
      src?.setData({ type: 'FeatureCollection', features: [] })
      segSrc?.setData({ type: 'FeatureCollection', features: [] })
      trailSrc?.setData({ type: 'FeatureCollection', features: [] })
      cursorMarker?.remove()
      cursorMarker = null
    }
    trackBounds = null
    return
  }

  renderTrack()
  rebuildReplayPath()
})

// Helper: render the cursor at given [lon,lat], creating the DOM element lazily.
// ── Ghost rides (in-place compare / race) ────────────────────────────────────
// One coloured line + one coloured marker per compared ride. Lines come from
// `compareLines` (static); marker positions from `compareCursors` (per frame).
const ghostMarkers = new Map<string, maplibregl.Marker>()

/** Draw / refresh the compare track lines (one FeatureCollection, colour per ride). */
function renderGhostLines() {
  if (!isMapReady()) return
  const lines = props.compareLines ?? []
  const fc: GeoJSON.FeatureCollection = {
    type: 'FeatureCollection',
    features: lines.filter(l => l.points.length >= 2).map(l => ({
      type: 'Feature',
      properties: { color: l.color },
      geometry: { type: 'LineString', coordinates: l.points.map(p => [p.lon, p.lat]) },
    })),
  }
  const src = map!.getSource('ghost-tracks') as maplibregl.GeoJSONSource | undefined
  if (src) { src.setData(fc) }
  else {
    map!.addSource('ghost-tracks', { type: 'geojson', data: fc })
    map!.addLayer({
      id: 'ghost-lines', type: 'line', source: 'ghost-tracks',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': ['get', 'color'], 'line-width': 3, 'line-opacity': 0.8, 'line-dasharray': [2, 1.5] },
    })
  }
  positionGhostMarkers()
}

/** Reconcile + move the per-ride markers from `compareCursors`. */
function positionGhostMarkers() {
  if (!map) return
  const cursors = props.compareCursors ?? []
  latestGhostCursors = cursors
  const live = new Set(cursors.map(c => c.id))
  // Drop markers whose ride is no longer compared.
  for (const [id, m] of ghostMarkers) {
    if (!live.has(id)) { m.remove(); ghostMarkers.delete(id) }
  }
  for (const c of cursors) {
    let m = ghostMarkers.get(c.id)
    if (!m) {
      const el = document.createElement('div')
      el.className = 'map-ghost-cursor'
      el.style.cssText = `width:22px;height:22px;border-radius:50%;background:${c.color};border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.45);display:flex;align-items:center;justify-content:center;color:#fff`
      el.innerHTML = riderGlyph('#fff', 2.4)
      m = new maplibregl.Marker({ element: el, anchor: 'center' }).setLngLat([c.lon, c.lat]).addTo(map)
      ghostMarkers.set(c.id, m)
    } else {
      m.setLngLat([c.lon, c.lat])
    }
  }
  updateGhostEdges()
}

// ── Mario-Kart-style off-screen indicators ───────────────────────────────────
// A ghost rider that's panned off the map gets a coloured arrow pinned to the
// nearest edge, pointing toward where they actually are. Pixel-positioned (not
// geo-anchored), so it's recomputed on every `move` while the in-view dot stays
// on its geo position.
const ghostEdges = new Map<string, HTMLElement>()
let latestGhostCursors: { id: string; color: string; lat: number; lon: number }[] = []

function createGhostEdgeEl(color: string): HTMLElement {
  const el = document.createElement('div')
  el.className = 'map-ghost-edge'
  el.style.cssText = 'position:absolute;transform:translate(-50%,-50%);z-index:5;pointer-events:none;will-change:left,top'
  const rot = document.createElement('div')
  rot.className = 'rot'
  rot.style.cssText = 'position:relative;width:22px;height:22px'
  rot.innerHTML =
    `<span style="position:absolute;left:17px;top:50%;transform:translateY(-50%);width:0;height:0;border-top:7px solid transparent;border-bottom:7px solid transparent;border-left:11px solid ${color};filter:drop-shadow(0 1px 2px rgba(0,0,0,.5))"></span>` +
    `<span style="position:absolute;inset:0;border-radius:50%;background:${color};border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.45)"></span>`
  el.appendChild(rot)
  return el
}

function updateGhostEdges() {
  if (!map) return
  // Append into MapLibre's OWN canvas container, never the Vue-managed mapEl
  // div — injecting foreign DOM under a Vue-rendered element makes Vue's
  // patch/unmount choke ("Cannot set properties of null (setting '__vnode')").
  // The canvas container shares the map's pixel origin, so project() coords map
  // straight onto it.
  const overlay = map.getCanvasContainer()
  const W = overlay.clientWidth, H = overlay.clientHeight
  const margin = 24
  const live = new Set(latestGhostCursors.map(c => c.id))
  for (const [id, el] of ghostEdges) if (!live.has(id)) { el.remove(); ghostEdges.delete(id) }
  for (const c of latestGhostCursors) {
    const p = map.project([c.lon, c.lat])
    const onScreen = p.x >= 0 && p.x <= W && p.y >= 0 && p.y <= H
    let el = ghostEdges.get(c.id)
    if (onScreen) { if (el) el.style.display = 'none'; continue }
    // Clamp toward the rider along the centre→target ray, inset by `margin`.
    const cx = W / 2, cy = H / 2
    const dx = p.x - cx, dy = p.y - cy
    const scale = Math.min((W / 2 - margin) / Math.max(Math.abs(dx), 1e-3),
                           (H / 2 - margin) / Math.max(Math.abs(dy), 1e-3))
    const ex = cx + dx * scale, ey = cy + dy * scale
    if (!el) { el = createGhostEdgeEl(c.color); overlay.appendChild(el); ghostEdges.set(c.id, el) }
    el.style.display = 'block'
    el.style.left = `${ex}px`
    el.style.top = `${ey}px`
    ;(el.firstElementChild as HTMLElement).style.transform = `rotate(${Math.atan2(dy, dx) * 180 / Math.PI}deg)`
  }
}

function clearGhosts() {
  for (const m of ghostMarkers.values()) m.remove()
  ghostMarkers.clear()
  for (const el of ghostEdges.values()) el.remove()
  ghostEdges.clear()
  latestGhostCursors = []
  if (map?.getLayer('ghost-lines')) map.removeLayer('ghost-lines')
  if (map?.getSource('ghost-tracks')) map.removeSource('ghost-tracks')
}

// ── Auto-detected highlights (OSM passes / named peaks) ──────────────────────
// Passes (crossed) get a labelled amber badge; peaks a slate triangle, labelled
// only when summited so nearby-peak context doesn't clutter the map.
const highlightMarkers = new Map<number, maplibregl.Marker>()

function renderHighlightMarkers() {
  if (!map) return
  const list = [...(props.highlights?.passes ?? []), ...(props.highlights?.peaks ?? [])]
  const live = new Set(list.map(h => h.osmId))
  for (const [id, m] of highlightMarkers) if (!live.has(id)) { m.remove(); highlightMarkers.delete(id) }
  for (const h of list) {
    let m = highlightMarkers.get(h.osmId)
    if (!m) {
      m = new maplibregl.Marker({ element: highlightEl(h), anchor: 'bottom' }).setLngLat([h.lon, h.lat]).addTo(map)
      highlightMarkers.set(h.osmId, m)
    } else {
      m.setLngLat([h.lon, h.lat])
    }
  }
}

function highlightEl(h: Highlight): HTMLElement {
  const isPass = h.type === 'PASS'
  const color = isPass ? '#d97706' : '#475569'   // amber pass / slate peak
  const ele = h.eleM != null ? ` · ${Math.round(h.eleM)} m` : ''
  const el = document.createElement('div')
  el.className = 'map-highlight'
  el.title = `${h.name ?? (isPass ? 'Pass' : 'Peak')}${ele}`
  const icon = document.createElement('span')
  icon.className = 'hl-icon'
  icon.style.background = color
  // pass = saddle between two peaks; peak = single triangle.
  icon.innerHTML = isPass
    ? '<svg viewBox="0 0 24 24" width="12" height="12" fill="#fff"><path d="M2 20h20L15 9l-3 4-3-5z"/></svg>'
    : '<svg viewBox="0 0 24 24" width="12" height="12" fill="#fff"><path d="M12 4 22 20H2z"/></svg>'
  el.appendChild(icon)
  if (isPass || h.summited) {
    const label = document.createElement('span')
    label.className = 'hl-label'
    label.textContent = h.name ?? (isPass ? 'Pass' : 'Peak')
    el.appendChild(label)
  }
  return el
}

function clearHighlights() {
  for (const m of highlightMarkers.values()) m.remove()
  highlightMarkers.clear()
}

// ── "Tours" overlay — pin other rides whose start is in the current viewport ──
// Only the rides currently on screen get a pin (recomputed on every move), so a
// big library doesn't carpet the map. Click a pin → navigate to that tour.
const router = useRouter()
const nearbyMarkers = new Map<string, maplibregl.Marker>()

function nearbyTourEl(t: { id: string; name: string }): HTMLElement {
  const el = document.createElement('div')
  el.className = 'map-nearby-tour'
  el.title = t.name || 'tour'
  el.addEventListener('click', (e) => {
    e.stopPropagation()
    router.push({ path: '/tours', query: { id: t.id } })
  })
  return el
}

function renderNearbyTours() {
  if (!map) return
  const list = props.nearbyTours ?? []
  const bounds = list.length ? map.getBounds() : null
  const live = new Set<string>()
  if (bounds) for (const t of list) if (bounds.contains([t.lon, t.lat])) live.add(t.id)
  for (const [id, m] of nearbyMarkers) if (!live.has(id)) { m.remove(); nearbyMarkers.delete(id) }
  if (!bounds) return
  for (const t of list) {
    if (nearbyMarkers.has(t.id) || !bounds.contains([t.lon, t.lat])) continue
    const m = new maplibregl.Marker({ element: nearbyTourEl(t), anchor: 'bottom' }).setLngLat([t.lon, t.lat]).addTo(map)
    nearbyMarkers.set(t.id, m)
  }
}

function clearNearbyTours() {
  for (const m of nearbyMarkers.values()) m.remove()
  nearbyMarkers.clear()
}

function placeCursor(lngLat: maplibregl.LngLatLike) {
  if (!map) return
  if (!cursorMarker) {
    const el = document.createElement('div')
    el.className = 'map-hover-cursor'
    // A little rider/user icon marks the replay position — or the ride's gear
    // glyph (bike/run/…) when its gear has one assigned.
    el.innerHTML = props.cursorIcon
      ? gearIconSvg(props.cursorIcon, 15, 'currentColor', 2.2)
      : riderGlyph('currentColor', 2.4)
    cursorMarker = new maplibregl.Marker({ element: el, anchor: 'center' }).setLngLat(lngLat).addTo(map)
  } else {
    cursorMarker.setLngLat(lngLat)
  }
}

// During playback we get fresh [lon,lat] every animation frame via hoverCoords;
// during hover/scrub we still drive the marker off hoverIndex (integer).
watch(() => props.hoverCoords, (coords) => {
  if (!map || !mapLoaded) return
  if (coords) placeCursor(coords as maplibregl.LngLatLike)
})

// Drive the playback camera off the exact fractional playhead (60 fps), sampling
// the precomputed path BETWEEN points so the drone glides continuously in the
// travel direction — never stepping/stopping per integer index.
watch(() => props.playFrac, (frac) => {
  if (!map || !mapLoaded || frac == null) return
  if (!props.isPlaying || !isFollowing.value) return
  if (performance.now() < cameraSettlingUntil) return
  sampleCameraAt(frac)
})

// Compare ride added/removed/changed → redraw the ghost overlay.
watch(() => props.compareLines, () => { if (mapLoaded) renderGhostLines() }, { deep: true })
watch(() => props.compareCursors, () => { if (mapLoaded) positionGhostMarkers() })
watch(() => props.highlights, () => { if (mapLoaded) renderHighlightMarkers() }, { deep: true })
watch(() => props.nearbyTours, () => { if (mapLoaded) renderNearbyTours() })

watch(() => props.hoverIndex, (idx) => {
  if (!map || !mapLoaded || !points.value?.length) return
  if (idx == null) {
    cursorMarker?.remove()
    cursorMarker = null
    setTrailProgress(0)
    return
  }
  const pt = points.value[idx]
  if (!pt) return
  // If hoverCoords is providing a smooth position, defer to it for the marker;
  // hoverIndex still feeds the camera follow below.
  if (!props.hoverCoords) placeCursor([pt.lon, pt.lat])

  // Reveal the trail up to the current cursor — bold glow on the part the
  // rider has already covered. Index-based ratio is close enough; we'd only
  // see drift on tracks with very non-uniform point spacing, which FIT files
  // rarely produce.
  setTrailProgress(idx / Math.max(1, points.value.length - 1))

  if (!isFollowing.value) return
  followPoint(idx)
})

// MapLibre's line-trim-offset takes [trim_start, trim_end] in fractional
// distance — the section between them is INVISIBLE. So to show only [0, p],
// we trim [p, 1]. p=0 → fully hidden (start of replay); p=1 → fully visible
// (end). Wrapped in a helper because we call it from both the hover watcher
// and the track-render reset path.
function setTrailProgress(p: number) {
  if (!isMapReady()) return
  const src = map!.getSource('track-trail') as maplibregl.GeoJSONSource | undefined
  if (!src) return
  const pts = points.value
  if (!pts?.length) {
    src.setData({ type: 'FeatureCollection', features: [] })
    return
  }
  const clamped = Math.max(0, Math.min(1, p))
  const lastIdx = Math.floor(clamped * (pts.length - 1))
  if (lastIdx < 1) {
    src.setData({ type: 'FeatureCollection', features: [] })
    return
  }
  // Preferred path: slice the cached per-segment features (point i→i+1 = segment
  // i), so the ridden trail carries { hr, slope } and the trail layers can be
  // coloured by the active mode exactly like the full-track segments. Covering
  // points [0..lastIdx] means segments [0..lastIdx-1].
  if (segFeatures.length) {
    src.setData({
      type: 'FeatureCollection',
      features: segFeatures.slice(0, Math.min(lastIdx, segFeatures.length)),
    })
    return
  }
  // Fallback (segments not built yet): a single uncoloured LineString.
  // Filter out any points without GPS coords — some FIT samples have
  // null lat/lon (e.g. indoor warm-up, tunnel without satellite fix). Pushing
  // those into MapLibre's GeoJSON source triggers "Expected value to be of
  // type number, but found null instead" inside the worker.
  const coords: [number, number][] = []
  for (let i = 0; i <= lastIdx; i++) {
    const p = pts[i]
    if (p && typeof p.lon === 'number' && typeof p.lat === 'number') {
      coords.push([p.lon, p.lat])
    }
  }
  if (coords.length < 2) {
    src.setData({ type: 'FeatureCollection', features: [] })
    return
  }
  src.setData({
    type: 'FeatureCollection',
    features: [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } }],
  })
}

// ── Track render ────────────────────────────────────────────────────────────
function renderTrack() {
  if (!isMapReady() || !points.value?.length) return
  const src = map!.getSource('track') as maplibregl.GeoJSONSource | undefined
  const segSrc = map!.getSource('track-segments') as maplibregl.GeoJSONSource | undefined
  if (!src || !segSrc) return

  // Drop points missing GPS coords — some FIT files have null lat/lon for
  // indoor warm-up / tunnels without satellite. Without this filter, MapLibre
  // throws "Expected value to be of type number, but found null instead" deep
  // in its GeoJSON worker.
  const coords: [number, number][] = []
  for (const p of points.value) {
    if (p && typeof p.lon === 'number' && typeof p.lat === 'number') {
      coords.push([p.lon, p.lat])
    }
  }
  if (coords.length < 2) {
    src.setData({ type: 'FeatureCollection', features: [] })
    return
  }

  src.setData({
    type: 'FeatureCollection',
    features: [
      { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } },
      { type: 'Feature', properties: { point: 'start' }, geometry: { type: 'Point', coordinates: coords[0] } },
      { type: 'Feature', properties: { point: 'end' }, geometry: { type: 'Point', coordinates: coords[coords.length - 1] } },
    ],
  })
  // New track → trail should start hidden, regardless of where the previous
  // one ended.
  setTrailProgress(0)

  // Plain-clone the points before postMessage — structured-clone can't copy
  // Vue's reactive proxies (see feedback memory).
  segSrc.setData({ type: 'FeatureCollection', features: [] })
  segFeatures = []
  segmentsJobId++
  const plainPoints = points.value.map(p => ({
    lat: p.lat, lon: p.lon, altM: p.altM, hr: p.hr, speedMs: p.speedMs,
  }))
  segmentsWorker?.postMessage({ id: segmentsJobId, points: plainPoints })

  const bounds = coords.reduce(
    (b, c) => b.extend(c as maplibregl.LngLatLike),
    new maplibregl.LngLatBounds(coords[0], coords[0]),
  )
  trackBounds = bounds
  flyToTrack(bounds, props.tileProvider ?? 'osm')
  // Warm the corridor tiles so replay doesn't stutter loading them.
  prefetchRouteTiles()
}

function flyToTrack(bounds: maplibregl.LngLatBounds, provider: string) {
  if (!map) return
  if (provider === 'terrain') {
    map.flyTo({
      center: bounds.getCenter(),
      zoom: 12, pitch: 55, bearing: -15,
      duration: 1200,
    })
  } else {
    map.fitBounds(bounds, { padding: 48, pitch: 0, bearing: 0, duration: 800 })
  }
}
</script>

<template>
  <div class="relative h-full w-full">
    <div ref="mapEl" class="h-full w-full" />

    <!-- Rain: a little fun — animated downpour while replay passes a rain event. -->
    <Transition name="rain-fade">
      <div v-if="raining" class="map-rain-overlay" aria-hidden="true">
        <span class="map-rain-badge">🌧️ Rain</span>
      </div>
    </Transition>

    <!-- Loading overlay -->
    <div
      v-if="isPending"
      class="absolute inset-0 flex flex-col items-center justify-center z-[1000]"
      style="background-color: hsl(var(--background) / 0.75); backdrop-filter: blur(2px);"
    >
      <svg class="animate-spin text-primary mb-2" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21 12a9 9 0 1 1-6.219-8.56" />
      </svg>
      <span class="text-xs font-medium text-muted-fg">Loading track…</span>
    </div>

    <!-- Bottom-right floating buttons: 2D reset + center tour. The follow-
         cursor toggle (formerly a magnet button here) lives in the playback
         toolbar in ActivityViewer now, next to Play/Pause where it belongs. -->
    <div class="absolute bottom-8 right-3 z-[1000] flex flex-col gap-1">
      <button
        v-if="(props.tileProvider ?? 'osm') === 'terrain'"
        class="bg-background/90 backdrop-blur-sm border border-border rounded-md px-2.5 py-1 text-[11px] font-medium text-muted-fg hover:text-foreground shadow-sm transition-colors"
        title="Reset to top-down view"
        @click="reset2D()"
      >2D</button>
      <button
        v-if="trackBounds"
        class="bg-background/90 backdrop-blur-sm border border-border rounded-md px-2.5 py-1 text-[11px] font-medium text-muted-fg hover:text-foreground shadow-sm transition-colors"
        title="Center tour"
        @click="centerTour()"
      >⊙</button>
    </div>

    <!-- Marker editor — opens on right-click (new marker) or clicking a pin. -->
    <MarkerEditor v-model="editingMarker" class="absolute top-3 left-1/2 -translate-x-1/2 z-[1600] w-72 max-w-[92%]"
      @save="saveEditingMarker" @delete="deleteEditingMarker" />

    <!-- Add chooser — middle / right-double-click on the map asks what to drop:
         a global Marker (a place) or a ride Event (on this ride's timeline). -->
    <div v-if="addChooser"
      class="absolute top-3 left-1/2 -translate-x-1/2 z-[1600] flex items-center gap-2
             bg-background/95 backdrop-blur-sm border border-border rounded-xl shadow-2xl px-3 py-2">
      <span class="text-[11px] text-muted-fg">Add here:</span>
      <button class="px-2.5 py-1 text-[11px] font-medium rounded border border-border hover:bg-muted/40" @click="chooseMarker">Marker</button>
      <button class="px-2.5 py-1 text-[11px] font-semibold rounded bg-primary text-white hover:opacity-90" @click="placeEventAt">Event</button>
      <button class="btn-icon" title="Cancel" @click="addChooser = null">✕</button>
    </div>

    <!-- Ride-event editor — pin a typed event (rain, drink break, …) on this ride. -->
    <div v-if="editingEvent"
      class="absolute top-3 left-1/2 -translate-x-1/2 z-[1600] w-72 max-w-[92%]
             bg-background/95 backdrop-blur-sm border border-border rounded-xl shadow-2xl p-3 space-y-2.5">
      <div class="flex items-center justify-between">
        <span class="text-[11px] font-semibold uppercase tracking-wide text-muted-fg">Ride event</span>
        <button class="btn-icon" title="Close" @click="editingEvent = null">✕</button>
      </div>
      <select v-model="editingEvent.type"
        class="w-full px-2 py-1.5 text-sm rounded-md border border-border bg-background text-foreground focus:outline-none focus:border-primary">
        <option v-for="t in eventTypes ?? []" :key="t.key" :value="t.key">{{ t.name }}</option>
      </select>
      <input v-model="editingEvent.label" type="text" placeholder="Label (optional)…"
        class="w-full px-2 py-1.5 text-sm rounded-md border border-border bg-background text-foreground focus:outline-none focus:border-primary" />
      <div class="flex items-center justify-between pt-0.5">
        <button class="px-2 py-1 text-[11px] font-medium rounded border border-red-300 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
          @click="deleteEditingEvent">{{ editingEvent._idx >= 0 ? 'Delete' : 'Discard' }}</button>
        <button class="px-3 py-1 text-[11px] font-semibold rounded bg-primary text-white hover:opacity-90 transition-opacity"
          @click="saveEditingEvent">Save</button>
      </div>
    </div>

    <!-- In-app photo viewer — native top-layer popover (Popover API). Click a
         pin to open here (no new window); click ::backdrop or press Esc to close. -->
    <div ref="photoPopoverEl" popover="auto" class="photo-popover" @toggle="onPhotoToggle">
      <figure v-if="lightbox" class="photo-popover__fig">
        <video v-if="isVideoFile(lightbox.name)" :src="lightbox.url" class="photo-popover__img" controls autoplay playsinline />
        <img v-else :src="lightbox.url" :alt="lightbox.name" class="photo-popover__img" />
        <figcaption class="photo-popover__cap">{{ lightbox.name }}</figcaption>
      </figure>
      <button class="photo-popover__close" title="Close (Esc)" @click="closePhoto">×</button>
    </div>
  </div>
</template>
