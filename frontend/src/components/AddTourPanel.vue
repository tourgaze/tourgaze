<script setup lang="ts">
import { fmtDuration } from '@/lib/format'
import { ref, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { Bike, MapPin, Timer, Ruler, Tag as TagIcon, Save, Trash2, CloudSun, ArrowLeft, Scale, Plus, ChevronDown, ChevronRight, ImagePlus, X as XIcon, Heart, Zap, Gauge, ArrowUpRight } from 'lucide-vue-next'
import {
  importInbox, discardInbox, getUsers, getGear, getInboxTrack, lookupWeather, getWeatherConditions,
  getPrediction, getInboxMedia, uploadInboxMedia, deleteInboxMedia, inboxMediaUrl, isVideoFile, getSports,
  getAllMarkers, createMarker, getEventTypes, setActivityEvents,
  type InboxItem, type WeatherResult, type Prediction, type Marker, type RideEvent,
} from '@/api/client'
import { bboxOf, inBBox } from '@/lib/geo'
import StartLocationMap from '@/components/StartLocationMap.vue'
import MarkerEditor from '@/components/MarkerEditor.vue'
import EventEditor from '@/components/EventEditor.vue'
import TagCombobox from '@/components/TagCombobox.vue'
import LocationAutocomplete from '@/components/LocationAutocomplete.vue'
import { weatherIcon, weatherColor } from '@/composables/weatherIcon'
import { useCurrentUser } from '@/composables/useCurrentUser'
import { INBOX_LAYOUT_SLOT, autoLayoutRef } from '@/composables/useLayoutState'

const props = defineProps<{ item: InboxItem }>()
const emit = defineEmits<{ done: []; cancel: [] }>()

const qc = useQueryClient()
const router = useRouter()
const { data: users } = useQuery({ queryKey: ['users'], queryFn: getUsers })
const { data: gear } = useQuery({ queryKey: ['gear'], queryFn: () => getGear() })
const { data: sports } = useQuery({ queryKey: ['sports'], queryFn: () => getSports(true) })

// Foldable map card: collapsed shows the small start-location preview; expanded
// fetches + draws the whole route. The track is only fetched once expanded.
// Persisted (localStorage) so it stays open as you switch between rides and
// across reloads — the panel is re-created per ride, so a plain ref would reset.
const mapExpanded = autoLayoutRef(INBOX_LAYOUT_SLOT, 'mapExpanded', false)
const { data: previewTrack } = useQuery({
  // Reactive value key (NOT a function — vue-query hashes keys via JSON and
  // drops functions, which would collapse every inbox item onto one entry).
  queryKey: computed(() => ['inbox-track', props.item.filename]),
  queryFn: () => getInboxTrack(props.item.filename!),
  enabled: () => mapExpanded.value && !!props.item.filename && props.item.startLat != null,
  staleTime: 60 * 60 * 1000,
})

// ── Add markers / events on the route at import time (memory pins while it's
// fresh). Click the map → choose Marker (a global place, saved now) or Event (a
// moment on THIS ride, staged and written when the ride is imported). Reuses
// StartLocationMap (placing/rendering) + MarkerEditor + EventEditor.
const addingPins = ref(false)
const addChooser = ref<{ lat: number; lon: number } | null>(null)
const markerDraft = ref<Marker | null>(null)
const eventDraft = ref<RideEvent | null>(null)
const stagedEvents = ref<RideEvent[]>([])

const { data: allMarkers } = useQuery({ queryKey: ['markers'], queryFn: getAllMarkers })
const { data: eventTypes } = useQuery({ queryKey: ['event-types', 'enabled'], queryFn: () => getEventTypes(true) })

// Only the markers near this ride's route (bbox + 5 km), like the ride map does.
const nearbyMarkers = computed(() => {
  const box = bboxOf((previewTrack.value ?? []).map(p => ({ lat: p.lat, lon: p.lon })), 5)
  return (allMarkers.value ?? []).filter(m => inBBox(m.lat, m.lon, box))
})

// A map click (while placing) asks what to drop here.
function onPlace(at: { lat: number; lon: number }) {
  addChooser.value = at
}
function chooseMarker() {
  const c = addChooser.value
  if (!c) return
  markerDraft.value = { id: '', lat: c.lat, lon: c.lon, label: '', description: '', category: 'star' } as Marker
  addChooser.value = null
}
function chooseEvent() {
  const c = addChooser.value
  if (!c) return
  const first = (eventTypes.value ?? [])[0]
  eventDraft.value = { type: first?.key ?? 'WEATHER_RAIN', label: '', lat: c.lat, lon: c.lon }
  addChooser.value = null
}
async function saveMarker() {
  const m = markerDraft.value
  if (!m) return
  try {
    await createMarker({ lat: m.lat, lon: m.lon, label: m.label, description: m.description, category: m.category })
    await qc.invalidateQueries({ queryKey: ['markers'] })
    push.success({ title: 'Marker added' })
  } catch {
    push.error('Could not add marker')
  } finally {
    markerDraft.value = null
  }
}
// Events can't be saved until the ride exists, so stage them now and write them
// after import (see the import mutation's onSuccess).
function saveEvent() {
  if (!eventDraft.value) return
  stagedEvents.value.push({ ...eventDraft.value })
  eventDraft.value = null
  push.success({ title: 'Event added', message: 'Saved with the ride on import' })
}

// ── Photos: drop here now, moved into the ride's media folder on import ──────
const { data: media } = useQuery({
  queryKey: computed(() => ['inbox-media', props.item.filename]),
  queryFn: () => getInboxMedia(props.item.filename!),
  enabled: () => !!props.item.filename,
})
const mediaDragOver = ref(false)
const mediaUploading = ref(false)
async function addMedia(files: FileList | File[] | null) {
  const arr = Array.from(files ?? []).filter(f => f.type.startsWith('image/') || f.type.startsWith('video/'))
  if (!arr.length || !props.item.filename) return
  mediaUploading.value = true
  try {
    await uploadInboxMedia(props.item.filename, arr)
    await qc.invalidateQueries({ queryKey: ['inbox-media', props.item.filename] })
  } catch { push.error('Photo upload failed') }
  finally { mediaUploading.value = false }
}
async function removeMedia(name: string) {
  if (!props.item.filename) return
  try {
    await deleteInboxMedia(props.item.filename, name)
    await qc.invalidateQueries({ queryKey: ['inbox-media', props.item.filename] })
  } catch { push.error('Could not remove photo') }
}
function mediaUrl(name: string) { return inboxMediaUrl(props.item.filename!, name) }
// Active rider from the global state (set by the header's rider switcher).
// We pre-select this in the form so the user doesn't have to pick on every
// import — the common case is "all my rides are mine."
const { user: currentUser } = useCurrentUser()
const { data: conditions } = useQuery({
  queryKey: ['weather-conditions'],
  queryFn: getWeatherConditions,
  staleTime: 60 * 60 * 1000,
})

const name = ref('')
const description = ref('')
const activityType = ref<string>('cycling')   // detected sport, user-overridable
// Editable start time (datetime-local string). Garmin sometimes records a wrong
// start; we only send an override when the user actually changes it (so we don't
// truncate the original's seconds, which the minute-precision input can't show).
const startTime = ref('')
const startTimeOriginal = ref('')
function toLocalInput(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
const userId = ref<string | null>(null)
const gearId = ref<string | null>(null)
const selectedTags = ref<Set<string>>(new Set())
// Place name + 2-letter country code, kept as separate fields so city names
// containing a slash (Brixen/Bressanone, Sankt Margrethen, …) survive.
// Pulled from the prediction on first paint; user can override before save.
const startLocation = ref<string>('')
const startCountry = ref<string>('')
const endLocation = ref<string>('')
const endCountry = ref<string>('')

// Always non-null so v-model.number bindings stay valid member expressions.
const weather = ref<WeatherResult>({ tempC: null, humidityPct: null, windKph: null, condition: null, wmoCode: null })
const weatherLoading = ref(false)
const weatherCondition = ref<string>('')
const weatherAutofilled = ref(false)

// Per-activity weight (kg). Pre-fills from the active rider's profile so the
// most common case ("my weight is the same as last week") needs no input.
const weightKg = ref<number | null>(null)

// Backend-predicted suggestions (start/end place names, suggested title,
// suggested tag IDs from similar rides). Fetched on item-pick and used to
// pre-fill the form. Shown to the user so they know the pre-fills aren't
// coming from nowhere.
const prediction = ref<Prediction | null>(null)
const predictionLoading = ref(false)

watch(() => props.item, async (it) => {
  name.value = it.suggestedName ?? ''
  activityType.value = it.activityType ?? 'cycling'
  startTime.value = toLocalInput(it.startTime)
  startTimeOriginal.value = startTime.value
  description.value = ''
  selectedTags.value = new Set()
  // Pre-select active rider; falls back to first user only if none active.
  userId.value = currentUser.value?.id ?? users.value?.[0]?.id ?? null
  // Pre-fill the gear heuristically proposed from past similar rides.
  gearId.value = it.suggestedGearId ?? null
  // NB: deliberately do NOT reset mapExpanded here — the "Start location" route
  // card keeps its open/closed state across rides (persisted via autoLayoutRef).
  // Default body weight from the current rider's profile. User can override
  // for occasional readings (race-day, post-illness, etc).
  weightKg.value = currentUser.value?.weightKg ?? null
  weather.value = { tempC: null, humidityPct: null, windKph: null, condition: null, wmoCode: null }
  weatherCondition.value = ''
  weatherAutofilled.value = false
  prediction.value = null
  startLocation.value = ''
  startCountry.value = ''
  endLocation.value = ''
  endCountry.value = ''

  if (it.startLat != null && it.startLon != null) {
    // Weather + prediction fire in parallel — independent network calls.
    weatherLoading.value = true
    predictionLoading.value = true
    const wxPromise = it.startTime
      ? lookupWeather(it.startLat, it.startLon, it.startTime).catch(() => null)
      : Promise.resolve(null)
    const predPromise = getPrediction(
      it.startLat, it.startLon,
      it.endLat ?? null, it.endLon ?? null,
      it.distanceKm ?? null,
    ).catch(() => null)
    const [w, pred] = await Promise.all([wxPromise, predPromise])
    // The user may have clicked another inbox file while these were in flight —
    // applying the stale results would stamp the wrong weather/name/tags onto
    // the newly selected ride's form.
    if (props.item?.filename !== it.filename) return
    if (w) {
      weather.value = w
      weatherCondition.value = w.condition ?? ''
      weatherAutofilled.value = w.tempC != null
    }
    weatherLoading.value = false
    predictionLoading.value = false
    if (pred) {
      prediction.value = pred
      if (!name.value.trim() && pred.suggestedName) name.value = pred.suggestedName
      if (pred.suggestedTagIds && pred.suggestedTagIds.length) {
        selectedTags.value = new Set(pred.suggestedTagIds)
      }
      // Pre-fill location + country separately. The location string from
      // Nominatim is "place, region"; we take the most-specific first chunk
      // as the city/place. Country comes through verbatim as ISO alpha-2.
      if (!startLocation.value.trim()) startLocation.value = firstChunk(pred.startLocation)
      if (!startCountry.value.trim() && pred.country)  startCountry.value  = pred.country.toUpperCase()
      if (!endLocation.value.trim())   endLocation.value   = firstChunk(pred.endLocation)
      if (!endCountry.value.trim() && pred.country)    endCountry.value    = pred.country.toUpperCase()
    }
  }
}, { immediate: true })

/** Most-specific part of a Nominatim "place, region, country" string. */
function firstChunk(s: string | null | undefined): string {
  if (!s) return ''
  return s.split(',')[0].trim()
}

const importMut = useMutation({
  mutationFn: () => importInbox(props.item.filename!, {
    name: name.value.trim() || undefined,
    description: description.value.trim() || undefined,
    activityType: activityType.value || undefined,
    startTime: (startTime.value && startTime.value !== startTimeOriginal.value)
      ? new Date(startTime.value).toISOString() : undefined,
    gearId: gearId.value || undefined,
    userId: userId.value || undefined,
    tagIds: Array.from(selectedTags.value),
    weatherTempC: weather.value.tempC,
    weatherHumidityPct: weather.value.humidityPct,
    weatherWindKph: weather.value.windKph,
    weatherCondition: weatherCondition.value || null,
    weightKg: weightKg.value,
    startLocation: startLocation.value.trim() || null,
    startCountry: startCountry.value.trim().toUpperCase() || null,
    endLocation: endLocation.value.trim() || null,
    endCountry: endCountry.value.trim().toUpperCase() || null,
  } as any),
  onSuccess: async (res) => {
    // The ride now exists → write any events staged on the route map to it.
    if (stagedEvents.value.length && res?.activityId) {
      try {
        await setActivityEvents(res.activityId, stagedEvents.value)
      } catch {
        push.warning({ title: 'Ride saved, but events failed', message: 'Add them on the ride map.' })
      }
    }
    qc.invalidateQueries({ queryKey: ['activities'] })
    qc.invalidateQueries({ queryKey: ['inbox'] })
    push.success({ title: 'Saved', message: name.value || props.item.filename })
    emit('done')
  },
  onError: () => push.error('Save failed'),
})

// The single user-initiated remove: the file leaves inbox/ for inbox-ignored/
// (bytes kept, drag back to undo) and its hash is parked so a keep-on-device
// source won't re-stage it. NOT a hard delete.
const removeMut = useMutation({
  mutationFn: () => discardInbox(props.item.filename!),
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['inbox'] })
    push.info({ title: 'Removed from inbox' })
    emit('done')
  },
  onError: () => push.error('Could not remove from inbox'),
})
</script>

<template>
  <div class="h-full flex flex-col bg-background overflow-hidden">
    <!-- Header bar -->
    <div class="flex items-center gap-2 px-4 py-2.5 border-b border-border">
      <button class="btn-icon" title="Back to activities" @click="emit('cancel')"><ArrowLeft :size="14" /></button>
      <Bike :size="18" class="text-primary" />
      <h2 class="text-sm font-semibold">Add tour from inbox</h2>
      <span class="ml-auto text-[10px] text-muted-fg font-mono truncate max-w-[260px]" :title="item.filename ?? ''">{{ item.filename }}</span>
    </div>

    <!-- Scrollable body -->
    <div class="flex-1 overflow-y-auto px-4 py-3 space-y-2.5 w-full">
      <!-- Detected metadata -->
      <div class="text-[11px] grid grid-cols-3 sm:grid-cols-5 gap-2 p-2 rounded bg-muted/20 border border-border">
        <div><div class="text-muted-fg">Format</div><div>{{ item.format?.toUpperCase() }}</div></div>
        <div><div class="text-muted-fg">Detected sport</div>
          <select v-model="activityType" class="mt-0.5 w-full bg-transparent border border-border rounded px-1 py-0.5 text-[11px] focus:outline-none focus:border-primary">
            <option v-for="s in sports ?? []" :key="s.key" :value="s.key">{{ s.name }}</option>
            <option v-if="activityType && !(sports ?? []).some(s => s.key === activityType)" :value="activityType">{{ activityType }}</option>
          </select>
        </div>
        <div><div class="text-muted-fg flex items-center gap-1"><Timer :size="10" />Duration</div><div>{{ fmtDuration(item.durationS) }}</div></div>
        <div><div class="text-muted-fg flex items-center gap-1"><Ruler :size="10" />Distance</div><div>{{ item.distanceKm != null ? item.distanceKm.toFixed(1) + ' km' : '—' }}</div></div>
        <div class="col-span-2 sm:col-span-1"><div class="text-muted-fg flex items-center gap-1"><MapPin :size="10" />Start</div>
          <input type="datetime-local" v-model="startTime"
            class="mt-0.5 w-full bg-transparent border border-border rounded px-1 py-0.5 text-[11px] focus:outline-none focus:border-primary"
            :class="startTime !== startTimeOriginal ? 'border-primary text-primary' : ''" />
        </div>
      </div>

      <!-- Available sensor channels — what extra data this file carries beyond GPS -->
      <div v-if="item.hasHeartRate || item.hasCadence || item.hasPower"
        class="flex flex-wrap items-center gap-1.5 text-[11px]">
        <span class="text-muted-fg">Sensors</span>
        <span v-if="item.hasHeartRate" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded border border-border bg-muted/20" style="color:#ef4444">
          <Heart :size="11" /> Heart rate
        </span>
        <span v-if="item.hasCadence" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded border border-border bg-muted/20" style="color:#06b6d4">
          <Gauge :size="11" /> Cadence
        </span>
        <span v-if="item.hasPower" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded border border-border bg-muted/20" style="color:#a855f7">
          <Zap :size="11" /> Power
        </span>
      </div>

      <!-- Predicted location label — surfaces the pre-fill source so the user
           sees why the title/tags came out the way they did. -->
      <div v-if="prediction?.startLocation || predictionLoading"
        class="flex items-start gap-2 text-[11px] p-2 rounded border border-border bg-muted/10">
        <MapPin :size="12" class="mt-0.5 text-primary shrink-0" />
        <div class="flex-1">
          <div v-if="predictionLoading" class="text-muted-fg">Looking up location…</div>
          <div v-else>
            <span class="font-medium text-foreground">{{ prediction!.startLocation }}</span>
            <span v-if="prediction!.endLocation && prediction!.endLocation !== prediction!.startLocation"
              class="text-muted-fg"> → {{ prediction!.endLocation }}</span>
            <span v-if="prediction!.country" class="ml-1 text-muted-fg">· {{ prediction!.country }}</span>
            <span v-if="prediction!.suggestedTagIds.length" class="ml-1 text-muted-fg">
              · {{ prediction!.suggestedTagIds.length }} tag{{ prediction!.suggestedTagIds.length === 1 ? '' : 's' }} suggested from nearby rides
            </span>
          </div>
        </div>
      </div>

      <!-- Foldable route card: collapsed = small start preview; expanded = whole trip + track line -->
      <div class="rounded border border-border overflow-hidden">
        <button type="button"
          class="w-full flex items-center gap-1.5 px-2 py-1 text-[11px] font-medium text-muted-fg hover:bg-muted/30 transition-colors"
          @click="mapExpanded = !mapExpanded">
          <component :is="mapExpanded ? ChevronDown : ChevronRight" :size="12" />
          <MapPin :size="11" />
          <span>{{ mapExpanded ? 'Route' : 'Start location' }}</span>
          <span class="ml-auto text-[10px] opacity-70">{{ mapExpanded ? 'whole trip' : 'expand to preview the route' }}</span>
        </button>
        <!-- Only mount the map on demand (no tile loads per inbox item while
             collapsed), and key it to the file so it refreshes when you switch
             rides instead of reusing the previous ride's view. -->
        <div v-if="mapExpanded" class="p-1 space-y-1">
          <div v-if="item.startLat != null" class="flex items-center gap-2 px-1">
            <button type="button"
              class="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded border transition-colors"
              :class="addingPins ? 'border-primary text-primary bg-primary/10' : 'border-border text-muted-fg hover:text-primary hover:border-primary'"
              @click="addingPins = !addingPins; markerDraft = null; eventDraft = null; addChooser = null">
              <MapPin :size="11" />
              {{ addingPins ? 'Click the map to add here' : 'Add marker / event' }}
            </button>
            <span v-if="addingPins" class="text-[10px] text-muted-fg">a place (marker) or a moment on this ride (event)</span>
            <span v-if="stagedEvents.length" class="text-[10px] text-primary ml-auto">{{ stagedEvents.length }} event{{ stagedEvents.length !== 1 ? 's' : '' }} staged</span>
          </div>
          <div class="relative">
            <StartLocationMap
              :key="item.filename ?? 'map'"
              :lat="item.startLat ?? null"
              :lon="item.startLon ?? null"
              :activity-id="item.existingActivityId ?? null"
              :points="previewTrack ?? null"
              :interactive="addingPins"
              :markers="nearbyMarkers"
              :draft="markerDraft"
              :events="stagedEvents"
              :event-draft="eventDraft"
              :event-types="eventTypes ?? []"
              height-class="h-96"
              @place="onPlace"
            />
            <!-- What to drop here: a global Marker or a ride Event. -->
            <div v-if="addChooser"
              class="absolute top-2 left-1/2 -translate-x-1/2 z-[20] flex items-center gap-2
                     bg-background/95 backdrop-blur-sm border border-border rounded-xl shadow-2xl px-3 py-2">
              <span class="text-[11px] text-muted-fg">Add here:</span>
              <button type="button" class="px-2.5 py-1 text-[11px] font-medium rounded border border-border hover:bg-muted/40" @click="chooseMarker">Marker</button>
              <button type="button" class="px-2.5 py-1 text-[11px] font-semibold rounded bg-primary text-white hover:opacity-90" @click="chooseEvent">Event</button>
              <button type="button" class="btn-icon" title="Cancel" @click="addChooser = null">✕</button>
            </div>
            <MarkerEditor v-model="markerDraft" class="absolute top-2 left-1/2 -translate-x-1/2 z-[20] w-64 max-w-[92%]"
              @save="saveMarker" @delete="markerDraft = null" />
            <EventEditor v-model="eventDraft" :event-types="eventTypes ?? []"
              class="absolute top-2 left-1/2 -translate-x-1/2 z-[20] w-64 max-w-[92%]"
              @save="saveEvent" @delete="eventDraft = null" />
          </div>
        </div>
      </div>

      <!-- Same route, different file: importable (keeps both) — just a heads-up,
           not a duplicate to dismiss. Delete is in the footer if you don't want it. -->
      <div v-if="item.duplicateOfName && !item.existingActivityId" class="text-[11px] p-2 rounded border border-border bg-muted/10 text-muted-fg">
        Looks like the same <strong>route</strong> as “{{ item.duplicateOfName }}”. Fill in below and Import to keep both, or delete it from the inbox.
      </div>

      <!-- Name, dense grid for related single-line fields. -->
      <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
        <label class="block text-sm md:col-span-2">
          <span class="text-xs font-medium text-muted-fg">Name</span>
          <input v-model="name" class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
        </label>

        <LocationAutocomplete
          v-model:location="startLocation"
          v-model:country="startCountry"
          label="Start location"
          :placeholder-location="firstChunk(prediction?.startLocation) || 'e.g. Ansbach'"
          :placeholder-country="prediction?.country?.toUpperCase() || 'DE'"
          :predicted="prediction ? { name: firstChunk(prediction.startLocation), country: prediction.country?.toUpperCase() ?? null } : null"
        />
        <LocationAutocomplete
          v-model:location="endLocation"
          v-model:country="endCountry"
          label="End location"
          :placeholder-location="firstChunk(prediction?.endLocation) || '(blank = loop)'"
          :placeholder-country="prediction?.country?.toUpperCase() || ''"
          :predicted="prediction?.endLocation && prediction.endLocation !== prediction.startLocation
            ? { name: firstChunk(prediction.endLocation), country: prediction.country?.toUpperCase() ?? null }
            : null"
        />

        <label class="block text-sm md:col-span-2">
          <span class="text-xs font-medium text-muted-fg">Description</span>
          <textarea v-model="description" rows="2" class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
        </label>
      </div>

      <!-- Weight + Gear share a row; Rider (multi-user only) spans below. -->
      <div class="grid grid-cols-2 gap-3">
        <label class="block text-sm">
          <span class="text-xs font-medium text-muted-fg flex items-center gap-1">
            <Scale :size="11" /> Weight (kg)
            <span v-if="currentUser?.weightKg != null && weightKg === currentUser.weightKg" class="text-[10px] font-normal opacity-60">profile</span>
          </span>
          <input v-model.number="weightKg" type="number" step="0.1" min="30" max="200"
            :placeholder="currentUser?.weightKg != null ? String(currentUser.weightKg) : 'optional'"
            class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
        </label>

        <!-- Gear. When none exists yet (masterdata empty) we can't offer a picker,
             so point the user straight at Settings → Gear to create some. -->
        <div class="block text-sm">
          <span class="text-xs font-medium text-muted-fg flex items-center gap-1"><Bike :size="11" /> Gear</span>
          <select v-if="gear && gear.length" v-model="gearId"
            class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none">
            <option :value="null">— none —</option>
            <option v-for="g in gear" :key="g.id!" :value="g.id">{{ g.name }}<template v-if="g.type"> ({{ g.type }})</template></option>
          </select>
          <button v-else type="button"
            class="mt-1 w-full inline-flex items-center justify-center gap-1.5 rounded-md border border-dashed border-border px-3 py-2 text-sm text-muted-fg hover:text-primary hover:border-primary transition-colors"
            @click="router.push('/settings?cat=gear')">
            <Plus :size="13" /> Add gear in Settings
          </button>
        </div>

        <label v-if="users && users.length > 1" class="block text-sm col-span-2">
          <span class="text-xs font-medium text-muted-fg">Rider</span>
          <select v-model="userId" class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none">
            <option v-for="u in users" :key="u.id!" :value="u.id">{{ u.displayName || u.username }}</option>
          </select>
        </label>
      </div>

      <!-- Weather -->
      <div class="text-sm">
        <div class="flex items-center gap-1.5 text-xs font-medium text-muted-fg mb-1">
          <CloudSun :size="13" />
          <span>Weather</span>
          <span v-if="weatherLoading" class="opacity-60">· looking up…</span>
          <span v-else-if="weatherAutofilled" class="opacity-60">· auto-filled from Open-Meteo</span>
        </div>
        <div class="grid grid-cols-3 gap-2">
          <div><span class="text-[10px] text-muted-fg">Temp °C</span>
            <input v-model.number="weather.tempC" type="number" step="0.1"
              class="block w-full mt-0.5 rounded border border-border bg-transparent px-2 py-1 text-xs" :placeholder="weatherLoading ? '…' : '—'" />
          </div>
          <div><span class="text-[10px] text-muted-fg">Humidity %</span>
            <input v-model.number="weather.humidityPct" type="number"
              class="block w-full mt-0.5 rounded border border-border bg-transparent px-2 py-1 text-xs" :placeholder="weatherLoading ? '…' : '—'" />
          </div>
          <div><span class="text-[10px] text-muted-fg">Wind kph</span>
            <input v-model.number="weather.windKph" type="number" step="0.1"
              class="block w-full mt-0.5 rounded border border-border bg-transparent px-2 py-1 text-xs" :placeholder="weatherLoading ? '…' : '—'" />
          </div>
        </div>
        <div class="mt-2 flex items-center gap-2">
          <label class="flex-1 block text-sm">
            <span class="text-[10px] text-muted-fg">Condition</span>
            <select v-model="weatherCondition" class="mt-0.5 block w-full rounded border border-border bg-transparent px-2 py-1 text-xs">
              <option value="">—</option>
              <option v-for="c in conditions" :key="c" :value="c">{{ c }}</option>
            </select>
          </label>
          <component
            v-if="weatherIcon(weatherCondition)"
            :is="weatherIcon(weatherCondition)"
            :size="22"
            :class="['mt-3 flex-shrink-0', weatherColor(weatherCondition)]"
          />
        </div>
      </div>

      <div>
        <span class="text-xs font-medium text-muted-fg flex items-center gap-1 mb-1"><TagIcon :size="11" /> Tags</span>
        <TagCombobox v-model="selectedTags" />
      </div>

      <!-- Photos — dropped here now, moved to the ride's media folder on import -->
      <div>
        <span class="text-xs font-medium text-muted-fg flex items-center gap-1 mb-1"><ImagePlus :size="11" /> Photos</span>
        <div
          class="rounded-md border border-dashed px-3 py-3 text-center transition-colors cursor-pointer"
          :class="mediaDragOver ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50'"
          @click="($refs.mediaInput as HTMLInputElement).click()"
          @dragover.prevent="mediaDragOver = true"
          @dragleave="mediaDragOver = false"
          @drop.prevent="mediaDragOver = false; addMedia($event.dataTransfer?.files ?? null)"
        >
          <input ref="mediaInput" type="file" accept="image/*,video/*" multiple class="hidden"
            @change="addMedia(($event.target as HTMLInputElement).files); ($event.target as HTMLInputElement).value = ''" />
          <div class="text-[11px] text-muted-fg flex items-center justify-center gap-1.5">
            <ImagePlus :size="13" />
            {{ mediaUploading ? 'Uploading…' : 'Drop photos or videos here, or click to choose' }}
          </div>
        </div>
        <div v-if="media && media.length" class="grid grid-cols-4 sm:grid-cols-5 gap-1.5 mt-2">
          <div v-for="name in media" :key="name" class="relative group aspect-square rounded overflow-hidden border border-border">
            <video v-if="isVideoFile(name)" :src="mediaUrl(name)" class="w-full h-full object-cover" muted preload="metadata" />
            <img v-else :src="mediaUrl(name)" :alt="name" class="w-full h-full object-cover" loading="lazy" />
            <button type="button"
              class="absolute top-1 right-1 w-5 h-5 inline-flex items-center justify-center rounded-full bg-red-600 text-white shadow ring-1 ring-white/70 hover:bg-red-700 transition-colors"
              title="Delete photo" @click.stop="removeMedia(name)">
              <XIcon :size="12" :stroke-width="3" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Footer -->
    <div class="flex items-center justify-between gap-2 px-4 py-3 border-t border-border bg-muted/10">
      <div class="flex items-center gap-2">
        <button class="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded border border-amber-300 text-amber-600 hover:bg-amber-50 dark:hover:bg-amber-900/20 disabled:opacity-50"
          title="Remove from inbox — moves to ~/.tourgaze/inbox-ignored/ (bytes kept, drag back to undo), won't re-stage from your device. Does NOT delete the ride from your library."
          :disabled="removeMut.isPending.value" @click="removeMut.mutate()">
          <Trash2 :size="12" /> {{ removeMut.isPending.value ? 'Removing…' : 'Remove from inbox' }}
        </button>
        <span v-if="item.existingActivityId" class="inline-flex items-center gap-1 text-[11px] text-amber-600 dark:text-amber-400">
          Already in your library —
          <span class="font-mono select-all" :title="item.existingActivityId">{{ item.existingActivityId }}</span>
          <button
            type="button"
            class="p-0.5 rounded hover:bg-amber-500/15 transition-colors"
            title="Show this ride in the track list"
            @click="router.push(`/tour/${item.existingActivityId}`)"
          >
            <ArrowUpRight :size="13" />
          </button>
        </span>
      </div>
      <div class="flex gap-2">
        <button class="px-3 py-1.5 text-xs font-medium rounded border border-border text-muted-fg hover:text-foreground" @click="emit('cancel')">{{ item.existingActivityId ? 'Close' : 'Cancel' }}</button>
        <!-- No Save for an already-imported file: importing again would duplicate /
             override the existing ride. Delete-from-inbox (left) is the only action. -->
        <button v-if="!item.existingActivityId"
          class="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded bg-primary text-primary-fg hover:bg-primary/90 disabled:opacity-50"
          :disabled="importMut.isPending.value" @click="importMut.mutate()">
          <Save :size="12" /> {{ importMut.isPending.value ? 'Saving…' : 'Save' }}
        </button>
      </div>
    </div>
  </div>
</template>
