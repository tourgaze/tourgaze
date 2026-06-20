import createClient from 'openapi-fetch'
import type { paths, components } from '../types/schema'

// All fields come straight from the regenerated OpenAPI schema. Run
// `npm run gen:api` against a live backend to refresh the types after any
// DTO change.
export type ActivitySummary = components['schemas']['ActivitySummaryDto']
export type TrackPoint = {
  lat: number
  lon: number
  altM: number | null
  hr: number | null
  speedMs: number | null
  rawIdx?: number | null
}
export type Setting = components['schemas']['SettingDto']
export type ActivityUpdate = components['schemas']['ActivityUpdateDto']
export type User = components['schemas']['UserDto']
export type Tag = components['schemas']['TagDto']
export type FilterPreset = components['schemas']['FilterPresetDto']
export type InboxItem = components['schemas']['InboxItemDto']
/** Sport / activity type union, generated from the backend ActivityType enum. */
export type ActivityType = components['schemas']['ActivityType']
/** Importable ride-file format union, generated from the backend SourceFormat enum. */
export type SourceFormat = components['schemas']['SourceFormat']
/** Every format the backend accepts for import — the single source of truth for
 *  the inbox accept-list and drag-drop validation. */
export const SOURCE_FORMATS: SourceFormat[] = ['fit', 'gpx', 'tcx', 'kmz', 'kml']
/** Recovery/export metadata for one ride (matches the store/ sidecar JSON). */
export type RideMetadata = components['schemas']['RideMetadata']
export type InboxImportRequest = components['schemas']['InboxImportRequest'] & {
  // Backend ActivityUpdateDto fields the modal also wants to send through import.
  weatherTempC?: number | null
  weatherHumidityPct?: number | null
  weatherWindKph?: number | null
  weatherCondition?: string | null
}

export const apiClient = createClient<paths>()
let chartEndpointAvailable: boolean | null = null
let adminEndpointAvailable: boolean | null = null

export async function getActivities(): Promise<ActivitySummary[]> {
  const { data, error } = await apiClient.GET('/api/activities')
  if (error) throw new Error(JSON.stringify(error))
  return data as ActivitySummary[]
}

export async function getTrack(id: string): Promise<TrackPoint[]> {
  const r = await fetch(`/api/activities/${id}/track`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

export async function getChartTrack(id: string): Promise<TrackPoint[]> {
  if (chartEndpointAvailable === false) {
    return getTrack(id)
  }
  const r = await fetch(`/api/activities/${id}/track/chart`)
  if (r.status === 404) {
    chartEndpointAvailable = false
    return getTrack(id)
  }
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  chartEndpointAvailable = true
  return r.json()
}

export async function updateActivity(id: string, dto: ActivityUpdate): Promise<ActivitySummary> {
  const { data, error } = await apiClient.PATCH('/api/activities/{id}', {
    params: { path: { id } },
    body: dto,
  })
  if (error) throw new Error(JSON.stringify(error))
  return data as ActivitySummary
}

/**
 * Drag-drop helper used by ToursView when a tag chip lands on an activity row.
 * Backend treats `tagIds` non-null as "replace the full set", so we union the
 * incoming tag id with the existing list and PATCH the union.
 */
export async function addTagToActivity(
  activityId: string,
  tagId: string,
  currentTagIds: string[],
): Promise<ActivitySummary> {
  if (currentTagIds.includes(tagId)) return updateActivity(activityId, { tagIds: currentTagIds } as any)
  return updateActivity(activityId, { tagIds: [...currentTagIds, tagId] } as any)
}

export async function uploadFit(file: File): Promise<{ status: string; inboxName: string }> {
  const fd = new FormData()
  fd.append('file', file)
  const r = await fetch('/api/inbox', { method: 'POST', body: fd })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

export async function getInbox(): Promise<InboxItem[]> {
  const { data, error } = await apiClient.GET('/api/inbox')
  if (error) throw new Error(JSON.stringify(error))
  return data as InboxItem[]
}

export async function importInbox(filename: string, req: Partial<InboxImportRequest>): Promise<{ activityId: string }> {
  const r = await fetch(`/api/inbox/${encodeURIComponent(filename)}/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

/**
 * Backend-enriched suggestions for a soon-to-be-imported ride — start/end
 * place names, suggested title, tag IDs derived from rides with a similar
 * start. Server uses the configured `app.language` setting for localisation.
 * Local type rather than `components['schemas']`-derived because the OpenAPI
 * spec gets regenerated separately.
 */
export type Prediction = {
  startLocation: string | null
  endLocation: string | null
  suggestedName: string | null
  suggestedTagIds: string[]
  country: string | null
  region: string | null
  language: string | null
}

/**
 * Map basemap provider catalog entry from `/api/tile-providers`.
 * Local type rather than schema-derived because the OpenAPI spec gets
 * regenerated separately.
 */
export type TileProvider = {
  id: string
  label: string
  description: string
  /** "raster" or "vector". Drives which MapLibre source spec to build. */
  type: 'raster' | 'vector'
  /** Frontend renderer component this provider needs. Today: "maplibre". */
  renderer: string
  /** For raster: URL template the proxy serves. For vector: null. */
  urlTemplate: string | null
  /** For vector: upstream MapLibre style JSON URL. For raster: null. */
  styleUrl: string | null
  maxZoom: number
  attribution: string
  /** True for DEM/elevation sources used by hillshade, not as a basemap. */
  isElevation: boolean
  isDark: boolean
}

/**
 * One forward-geocode autocomplete hit from `GET /api/inbox/search-place`.
 * Used by the AddTour / EditTour location pickers to surface suggestions as
 * the user types.
 */
export type PlaceProposal = {
  name: string
  country: string | null
  region: string | null
  displayLabel: string | null
  lat: number | null
  lon: number | null
  /** Place extent [south, north, west, east] from Nominatim — used by `near:`. */
  south: number | null
  north: number | null
  west: number | null
  east: number | null
}

export async function searchPlaces(q: string): Promise<PlaceProposal[]> {
  if (q.trim().length < 2) return []
  const r = await fetch(`/api/inbox/search-place?q=${encodeURIComponent(q.trim())}`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

// ── Inbox media (photos staged against a ride before import) ─────────────────
export function inboxMediaUrl(filename: string, name: string): string {
  return `/api/inbox/${encodeURIComponent(filename)}/media/${encodeURIComponent(name)}`
}
export async function getInboxMedia(filename: string): Promise<string[]> {
  const r = await fetch(`/api/inbox/${encodeURIComponent(filename)}/media`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
export async function uploadInboxMedia(filename: string, files: File[]): Promise<string[]> {
  const fd = new FormData()
  for (const f of files) fd.append('files', f)
  const r = await fetch(`/api/inbox/${encodeURIComponent(filename)}/media`, { method: 'POST', body: fd })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
export async function deleteInboxMedia(filename: string, name: string): Promise<void> {
  const r = await fetch(`/api/inbox/${encodeURIComponent(filename)}/media/${encodeURIComponent(name)}`, { method: 'DELETE' })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
}

// ── A ride's photos, geo-matched to the track ───────────────────────────────
export type RideMedia = {
  name: string
  lat: number | null
  lon: number | null
  takenAt: string | null
  /** Nearest track-point index this photo was matched to (null if unmatched). */
  trackIndex: number | null
  /** "private" = user-dropped; "public" = discovered (Wikimedia Commons). */
  origin: 'private' | 'public'
  /** Who it's by — the rider's name for personal uploads, "Wikimedia Commons" for discovered. */
  author?: string | null
}
export function activityMediaUrl(id: string, name: string): string {
  return `/api/activities/${id}/media/${encodeURIComponent(name)}`
}
const VIDEO_EXT = ['mp4', 'mov', 'm4v', 'webm']
/** True if a media filename is a video (vs an image) — drives <video> vs <img> rendering. */
export function isVideoFile(name: string): boolean {
  const dot = name.lastIndexOf('.')
  return dot >= 0 && VIDEO_EXT.includes(name.slice(dot + 1).toLowerCase())
}
export async function getActivityMedia(id: string): Promise<RideMedia[]> {
  const r = await fetch(`/api/activities/${id}/media`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
/** Upload personal photos onto an existing ride (EditTour drop area). */
export async function uploadActivityMedia(id: string, files: File[]): Promise<string[]> {
  const fd = new FormData()
  for (const f of files) fd.append('files', f)
  const r = await fetch(`/api/activities/${id}/media`, { method: 'POST', body: fd })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
export async function deleteActivityMedia(id: string, name: string): Promise<void> {
  const r = await fetch(`/api/activities/${id}/media/${encodeURIComponent(name)}`, { method: 'DELETE' })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
}
/** Move a discovered public photo into the user's personal set. Returns the updated manifest. */
export async function makePhotoPersonal(id: string, name: string): Promise<RideMedia[]> {
  const r = await fetch(`/api/activities/${id}/media/${encodeURIComponent(name)}/personal`, { method: 'POST' })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

// ── Map markers (user-placed points of interest) ────────────────────────────
export type Marker = {
  id: string
  /** Ride this marker belongs to, or null for a general (always-shown) marker. */
  activityId: string | null
  lat: number
  lon: number
  label: string
  description: string | null
  category: string
  createdAt?: string
}
export type MarkerCreate = {
  activityId?: string | null
  lat: number
  lon: number
  label?: string
  description?: string | null
  category: string
}

/**
 * A named section (climb / segment), e.g. "Jaufenpass". Read-only, curated
 * global dataset served from /section.json — no DB, no per-user CRUD. The
 * elevation chart highlights the ones a ride passes. (Served as plain JSON;
 * the dev/prod servers gzip it on the wire, so no at-rest .gz to mis-serve.)
 */
export type Section = {
  name: string
  startLat: number
  startLon: number
  endLat: number
  endLon: number
}
let _sectionsCache: Section[] | null = null
export async function getSections(): Promise<Section[]> {
  if (_sectionsCache) return _sectionsCache
  try {
    const r = await fetch('/section.json')
    if (!r.ok) return []
    _sectionsCache = (await r.json()) as Section[]
    return _sectionsCache
  } catch {
    return []
  }
}
/** A ride's own markers + all general markers. */
export async function getMarkersForActivity(activityId: string): Promise<Marker[]> {
  const r = await fetch(`/api/markers/activity/${activityId}`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

// ── Auto-detected ride highlights (OSM peaks/passes) ────────────────────────
/** A mountain pass crossed or a named peak near the ride; name is localized. */
export type Highlight = {
  osmId: number
  type: 'PASS' | 'PEAK'
  name: string | null
  eleM: number | null
  lat: number
  lon: number
  /** Closest distance from the track to this feature, metres. */
  distM: number
  /** Distance along the ride where it's closest, km — for the elevation chart. */
  trackDistKm: number
  /** Peak the ride actually summited (≤ ~120 m). */
  summited: boolean
  wikidata: string | null
}
export type Highlights = { passes: Highlight[]; peaks: Highlight[] }

export async function getActivityHighlights(activityId: string): Promise<Highlights> {
  const r = await fetch(`/api/activities/${activityId}/highlights`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

// ── Build + DB-schema version (About page / bug reports) ────────────────────
export type VersionInfo = {
  app: string
  schemaVersion: string
  schemaDescription: string | null
  schemaInstalledOn: string | null
  database: string
}
export async function getVersion(): Promise<VersionInfo> {
  const r = await fetch('/api/version')
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
/** General markers only (not tied to any ride). */
export async function getGeneralMarkers(): Promise<Marker[]> {
  const r = await fetch('/api/markers/general')
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
/** Every marker (for the global overview/management view). */
export async function getAllMarkers(): Promise<Marker[]> {
  const r = await fetch('/api/markers')
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
export async function createMarker(m: MarkerCreate): Promise<Marker> {
  const r = await fetch('/api/markers', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(m),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
export async function updateMarker(id: string, patch: Partial<Marker>): Promise<Marker> {
  const r = await fetch(`/api/markers/${id}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(patch),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
export async function deleteMarker(id: string): Promise<void> {
  const r = await fetch(`/api/markers/${id}`, { method: 'DELETE' })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
}

// ── Similar rides (ghost-chase compare picker) ──────────────────────────────
export type SimilarRide = {
  id: string
  name: string
  activityType: string | null
  startTime: string | null
  distanceKm: number | null
  durationS: number | null
  startLocation: string | null
  /** GPS route overlap (Jaccard 0–1); tag-only matches get a small base score. */
  score: number
  matchType: 'gps' | 'tag' | 'both'
}
/** Rides that look like the same route as this one (GPS overlap or shared tag). */
export async function getSimilarRides(id: string): Promise<SimilarRide[]> {
  const r = await fetch(`/api/activities/${id}/similar`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

/** Find CC photos along the route via Wikimedia Commons and add them to the ride. */
export async function discoverPhotos(id: string): Promise<{ name: string; lat: number; lon: number; attribution: string }[]> {
  const r = await fetch(`/api/activities/${id}/photos/discover`, { method: 'POST' })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

/** Downsampled route preview for a still-staged inbox file (pre-import). */
export async function getInboxTrack(filename: string): Promise<{ lat: number; lon: number }[]> {
  const r = await fetch(`/api/inbox/${encodeURIComponent(filename)}/track`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

export async function getTileProviders(): Promise<TileProvider[]> {
  const r = await fetch('/api/tile-providers')
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

// ── Custom map providers (user-defined, managed in Settings) ─────────────────
export type MapProvider = {
  id?: string
  name: string
  description?: string | null
  type: 'raster' | 'vector'
  urlTemplate?: string | null
  styleUrl?: string | null
  maxZoom?: number | null
  attribution?: string | null
  dark?: boolean
}

export async function getMapProviders(): Promise<MapProvider[]> {
  const r = await fetch('/api/map-providers')
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
export async function createMapProvider(dto: MapProvider): Promise<MapProvider> {
  const r = await fetch('/api/map-providers', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dto),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
export async function updateMapProvider(id: string, dto: MapProvider): Promise<MapProvider> {
  const r = await fetch(`/api/map-providers/${id}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dto),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
export async function deleteMapProvider(id: string): Promise<void> {
  const r = await fetch(`/api/map-providers/${id}`, { method: 'DELETE' })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
}

export async function getPrediction(
  startLat: number, startLon: number,
  endLat?: number | null, endLon?: number | null,
  distanceKm?: number | null,
): Promise<Prediction> {
  const params = new URLSearchParams({ startLat: String(startLat), startLon: String(startLon) })
  if (endLat != null && endLon != null) {
    params.set('endLat', String(endLat))
    params.set('endLon', String(endLon))
  }
  if (distanceKm != null) params.set('distanceKm', String(distanceKm))
  const r = await fetch(`/api/inbox/predict?${params.toString()}`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

export async function discardInbox(filename: string): Promise<void> {
  const r = await fetch(`/api/inbox/${encodeURIComponent(filename)}`, { method: 'DELETE' })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
}

/** Archive a staged inbox file to ~/.tourgaze/inbox-processed/ without importing it. */
export async function moveInboxToProcessed(filename: string): Promise<void> {
  const r = await fetch(`/api/inbox/${encodeURIComponent(filename)}/processed`, { method: 'POST' })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
}

// ── Weather ───────────────────────────────────────────────────────────────────

export type WeatherResult = {
  tempC: number | null
  humidityPct: number | null
  windKph: number | null
  condition: string | null
  wmoCode: number | null
}

export async function lookupWeather(lat: number, lon: number, timeIso: string): Promise<WeatherResult> {
  const url = `/api/weather?lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}&time=${encodeURIComponent(timeIso)}`
  const r = await fetch(url)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

export async function getWeatherConditions(): Promise<string[]> {
  const r = await fetch('/api/weather/conditions')
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

export async function deleteActivity(id: string): Promise<void> {
  const { error } = await apiClient.DELETE('/api/activities/{id}', {
    params: { path: { id } },
  })
  if (error) throw new Error(JSON.stringify(error))
}

export async function getSettings(): Promise<Setting[]> {
  const { data, error } = await apiClient.GET('/api/settings')
  if (error) throw new Error(JSON.stringify(error))
  return data as Setting[]
}

export async function saveSetting(key: string, value: string): Promise<Setting> {
  const { data, error } = await apiClient.PUT('/api/settings/{key}', {
    params: { path: { key } },
    body: { key, value },
  })
  if (error) throw new Error(JSON.stringify(error))
  return data as Setting
}

export async function getUsers(): Promise<User[]> {
  const { data, error } = await apiClient.GET('/api/users')
  if (error) throw new Error(JSON.stringify(error))
  return data as User[]
}

export async function createUser(dto: User): Promise<User> {
  const { data, error } = await apiClient.POST('/api/users', { body: dto })
  if (error) throw new Error(JSON.stringify(error))
  return data as User
}

export async function updateUser(id: string, dto: User): Promise<User> {
  const { data, error } = await apiClient.PUT('/api/users/{id}', { params: { path: { id } }, body: dto })
  if (error) throw new Error(JSON.stringify(error))
  return data as User
}

export async function deleteUser(id: string): Promise<void> {
  const { error } = await apiClient.DELETE('/api/users/{id}', { params: { path: { id } as any } })
  if (error) throw new Error(JSON.stringify(error))
}

// ── Gear ────────────────────────────────────────────────────────────────────
// Hand-written to match server GearDto (no OpenAPI path generated yet). Run
// `npm run gen:api` after a live backend exposes /api/gear to swap these to
// typed apiClient calls + components['schemas']['GearDto'].
export type Gear = {
  id?: string
  userId?: string | null
  name: string
  type?: string | null
  description?: string | null
  createdAt?: string | null
  retiredAt?: string | null
}

export async function getGear(userId?: string): Promise<Gear[]> {
  const qs = userId ? `?userId=${encodeURIComponent(userId)}` : ''
  const r = await fetch(`/api/gear${qs}`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

export async function createGear(dto: Gear): Promise<Gear> {
  const r = await fetch('/api/gear', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

export async function updateGear(id: string, dto: Gear): Promise<Gear> {
  const r = await fetch(`/api/gear/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

export async function deleteGear(id: string): Promise<void> {
  const r = await fetch(`/api/gear/${id}`, { method: 'DELETE' })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
}

/**
 * Bulk-assign (or clear) gear on many rides at once — backfill helper. Pass
 * gearId = '' (or null) to clear gear on every listed activity. Returns the
 * number of rides updated.
 */
export async function setBulkGear(ids: string[], gearId: string | null): Promise<number> {
  const r = await fetch('/api/activities/bulk-gear', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids, gearId: gearId ?? '' }),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  const data = await r.json()
  return data.updated ?? 0
}

// ── Tags ──────────────────────────────────────────────────────────────────────

export async function getTags(): Promise<Tag[]> {
  const { data, error } = await apiClient.GET('/api/tags')
  if (error) throw new Error(JSON.stringify(error))
  return data as Tag[]
}

export async function createTag(dto: Partial<Tag>): Promise<Tag> {
  const { data, error } = await apiClient.POST('/api/tags', { body: dto as any })
  if (error) throw new Error(JSON.stringify(error))
  return data as Tag
}

export async function updateTag(id: string, dto: Partial<Tag>): Promise<Tag> {
  const { data, error } = await apiClient.PUT('/api/tags/{id}', {
    params: { path: { id } },
    body: dto as any,
  })
  if (error) throw new Error(JSON.stringify(error))
  return data as Tag
}

export async function deleteTag(id: string): Promise<void> {
  const { error } = await apiClient.DELETE('/api/tags/{id}', { params: { path: { id } as any } })
  if (error) throw new Error(JSON.stringify(error))
}

/** What deleting a tag would take down — authoritative, server-side over the subtree. */
export interface TagImpact {
  tagId: string
  tagName: string
  descendantTags: number
  descendantNames: string[]
  activities: number
  presets: number
}

export async function getTagImpact(id: string): Promise<TagImpact> {
  const { data, error } = await apiClient.GET('/api/tags/{id}/impact' as any, {
    params: { path: { id } },
  } as any)
  if (error) throw new Error(JSON.stringify(error))
  return data as TagImpact
}

// ── Filter presets (named Tours filter/grouping perspectives) ───────────────

export async function getFilterPresets(): Promise<FilterPreset[]> {
  const { data, error } = await apiClient.GET('/api/filter-presets')
  if (error) throw new Error(JSON.stringify(error))
  return data as FilterPreset[]
}

export async function createFilterPreset(dto: Partial<FilterPreset>): Promise<FilterPreset> {
  const { data, error } = await apiClient.POST('/api/filter-presets', { body: dto as any })
  if (error) throw new Error(JSON.stringify(error))
  return data as FilterPreset
}

export async function updateFilterPreset(id: string, dto: Partial<FilterPreset>): Promise<FilterPreset> {
  const { data, error } = await apiClient.PUT('/api/filter-presets/{id}', {
    params: { path: { id } },
    body: dto as any,
  })
  if (error) throw new Error(JSON.stringify(error))
  return data as FilterPreset
}

export async function deleteFilterPreset(id: string): Promise<void> {
  const { error } = await apiClient.DELETE('/api/filter-presets/{id}', { params: { path: { id } as any } })
  if (error) throw new Error(JSON.stringify(error))
}

export async function purgeCache(): Promise<{ deleted: number; errors: number }> {
  if (adminEndpointAvailable === false) {
    throw new Error('ADMIN_ENDPOINT_UNSUPPORTED')
  }
  const r = await fetch('/api/admin/cache', { method: 'DELETE' })
  if (r.status === 404) {
    adminEndpointAvailable = false
    throw new Error('ADMIN_ENDPOINT_UNSUPPORTED')
  }
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  adminEndpointAvailable = true
  return r.json()
}

/** Open the repository folder (store/ + db-backup/) in the OS file manager.
 *  Returns the resolved path; throws with the server message when headless. */
export async function openRepositoryFolder(): Promise<{ path: string }> {
  const r = await fetch('/api/admin/open-folder', { method: 'POST' })
  const body = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(body?.error ?? `HTTP ${r.status}`)
  return body as { path: string }
}

export async function getDiskUsage(): Promise<{ storeBytes: number; cacheBytes: number; tilesBytes: number; totalBytes: number }> {
  if (adminEndpointAvailable === false) {
    return { storeBytes: 0, cacheBytes: 0, tilesBytes: 0, totalBytes: 0 }
  }
  const r = await fetch('/api/admin/disk')
  if (r.status === 404) {
    adminEndpointAvailable = false
    return { storeBytes: 0, cacheBytes: 0, tilesBytes: 0, totalBytes: 0 }
  }
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  adminEndpointAvailable = true
  return r.json()
}
