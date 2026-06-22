<script setup lang="ts">
import { fmtDateTime } from '@/lib/format'
import { ref, watch, computed } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import {
  Bike, Tag as TagIcon, Save, CloudSun, X as XIcon, ImagePlus,
  MapPin, Timer, Ruler, Mountain, Activity as ActivityIcon, Gauge, FileText, Hash, Copy, Scale,
  Globe, UserRound, Zap, RotateCw,
} from 'lucide-vue-next'
import {
  getActivities, getWeatherConditions, getGear, getSports, updateActivity,
  getActivityMedia, uploadActivityMedia, deleteActivityMedia, activityMediaUrl, isVideoFile, makePhotoPersonal,
  type ActivitySummary,
} from '@/api/client'
import { weatherIcon, weatherColor } from '@/composables/weatherIcon'
import TagCombobox from '@/components/TagCombobox.vue'
import StartLocationMap from '@/components/StartLocationMap.vue'
import LocationAutocomplete from '@/components/LocationAutocomplete.vue'

const props = defineProps<{ activityId: string }>()
const emit = defineEmits<{ done: []; cancel: [] }>()

const qc = useQueryClient()
const { data: activities } = useQuery({ queryKey: ['activities'], queryFn: getActivities })
const { data: conditions } = useQuery({
  queryKey: ['weather-conditions'],
  queryFn: getWeatherConditions,
  staleTime: 60 * 60 * 1000,
})
const { data: gear } = useQuery({ queryKey: ['gear'], queryFn: () => getGear() })
const { data: sports } = useQuery({ queryKey: ['sports'], queryFn: () => getSports(true) })

// ── Photos: drop onto an existing ride → store/<ride>_media/, geo-matched ────
// Reactive value key (NOT a function) so it matches value-based invalidations
// from other panels (move-to-personal, find-photos).
const { data: media } = useQuery({
  queryKey: computed(() => ['media', props.activityId]),
  queryFn: () => getActivityMedia(props.activityId),
  enabled: computed(() => !!props.activityId),
})
const mediaDragOver = ref(false)
const mediaUploading = ref(false)
const movingPhoto = ref<string | null>(null)
function refreshMedia() { return qc.invalidateQueries({ queryKey: ['media', props.activityId] }) }
async function addMedia(files: FileList | File[] | null) {
  const arr = Array.from(files ?? []).filter(f => f.type.startsWith('image/') || f.type.startsWith('video/'))
  if (!arr.length) return
  mediaUploading.value = true
  try {
    await uploadActivityMedia(props.activityId, arr)
    await refreshMedia()
  } catch { push.error('Photo upload failed') }
  finally { mediaUploading.value = false }
}
async function removeMedia(name: string) {
  try {
    await deleteActivityMedia(props.activityId, name)
    await refreshMedia()
  } catch { push.error('Could not remove photo') }
}
async function movePhotoToPersonal(name: string) {
  if (movingPhoto.value) return
  movingPhoto.value = name
  try {
    await makePhotoPersonal(props.activityId, name)
    await refreshMedia()
    push.success({ title: 'Moved to personal' })
  } catch { push.error('Could not move photo') }
  finally { movingPhoto.value = null }
}
const originLabel = (o: string) => (o === 'public' ? 'Public domain' : 'Personal')
const mediaUrl = (name: string) => activityMediaUrl(props.activityId, name)

const activity = computed<ActivitySummary | null>(() =>
  activities.value?.find(a => a.id === props.activityId) ?? null,
)

// ── Form state ──────────────────────────────────────────────────────────────
const name = ref('')
const description = ref('')
const activityType = ref<string>('cycling')
const gearId = ref<string>('')
const selectedTags = ref<Set<string>>(new Set())
const tempC = ref<number | null>(null)
const humidityPct = ref<number | null>(null)
const windKph = ref<number | null>(null)
const weatherCondition = ref<string>('')
const weightKg = ref<number | null>(null)
const startLocation = ref<string>('')
const startCountry = ref<string>('')
const endLocation = ref<string>('')
const endCountry = ref<string>('')

// Re-seed whenever the underlying activity (or the id) changes.
watch([activity, () => props.activityId], () => {
  const a = activity.value
  if (!a) return
  name.value = a.name ?? ''
  description.value = a.description ?? ''
  activityType.value = a.activityType ?? 'cycling'
  gearId.value = a.gearId ?? ''
  selectedTags.value = new Set(a.tagIds ?? [])
  tempC.value = a.weatherTempC ?? null
  humidityPct.value = a.weatherHumidityPct ?? null
  windKph.value = a.weatherWindKph ?? null
  weatherCondition.value = a.weatherCondition ?? ''
  weightKg.value = a.weightKg ?? null
  startLocation.value = a.startLocation ?? ''
  startCountry.value = a.startCountry ?? ''
  endLocation.value = a.endLocation ?? ''
  endCountry.value = a.endCountry ?? ''
}, { immediate: true })

const saveMut = useMutation({
  mutationFn: () => updateActivity(props.activityId, {
    name: name.value.trim(),
    description: description.value,
    activityType: activityType.value || undefined,
    // '' clears gear (backend treats blank as explicit clear); an id assigns it.
    gearId: gearId.value,
    weatherTempC: tempC.value,
    weatherHumidityPct: humidityPct.value,
    weatherWindKph: windKph.value,
    weatherCondition: weatherCondition.value || null,
    weightKg: weightKg.value,
    startLocation: startLocation.value.trim() || null,
    startCountry: startCountry.value.trim().toUpperCase() || null,
    endLocation: endLocation.value.trim() || null,
    endCountry: endCountry.value.trim().toUpperCase() || null,
    tagIds: Array.from(selectedTags.value),
    // `as any` because the generated schema picks `string | undefined` for
    // nullable backend fields, but explicit `null` carries the same
    // skip-this-field semantics on the Spring side (`dto.field() != null`).
  } as any),
  onSuccess: async () => {
    // refetchType 'all' so the tours list refreshes even if its query is
    // momentarily inactive (e.g. the edit panel replaced the viewer pane).
    await qc.invalidateQueries({ queryKey: ['activities'], refetchType: 'all' })
    push.success({ title: 'Saved', message: name.value })
    emit('done')
  },
  onError: () => push.error({ title: 'Could not save' }),
})

// ── Read-only display helpers ──────────────────────────────────────────────
function fmtDuration(s: number | null | undefined): string {
  if (s == null) return '—'
  const h = Math.floor(s / 3600); const m = Math.floor((s % 3600) / 60); const sec = s % 60
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${sec}s`
  return `${sec}s`
}
function fmtNumber(n: number | null | undefined, digits = 1, suffix = ''): string {
  if (n == null) return '—'
  return n.toFixed(digits) + suffix
}
function fmtCoord(lat: number | null | undefined, lon: number | null | undefined): string {
  if (lat == null || lon == null) return '—'
  return `${lat.toFixed(5)}, ${lon.toFixed(5)}`
}

// Source file lives at ~/.tourgaze/store/{sourceFilename}. We show the relative
// path because the absolute storage root is install-dependent; the user can
// resolve it from the app's data dir if they ever need to copy the original.
const sourcePath = computed(() => {
  const fn = activity.value?.sourceFilename
  return fn ? `~/.tourgaze/store/${fn}` : null
})

async function copyToClipboard(text: string | null | undefined) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    push.success({ title: 'Copied' })
  } catch {
    push.error('Clipboard blocked')
  }
}
</script>

<template>
  <div class="h-full flex flex-col bg-background overflow-hidden">
    <div class="flex items-center gap-2 px-4 py-2 border-b border-border">
      <Bike :size="16" class="text-primary" />
      <h2 class="text-sm font-semibold">Edit tour</h2>
      <span v-if="activity?.startTime" class="ml-auto text-[10px] text-muted-fg">
        {{ new Date(activity.startTime).toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' }) }}
      </span>
    </div>

    <div class="flex-1 overflow-y-auto px-6 py-3 space-y-3 w-full">
      <!-- ── Ride stats (read-only, parsed from the FIT) ────────────────────── -->
      <div v-if="activity" class="grid grid-cols-2 sm:grid-cols-4 gap-2 text-[11px] p-3 rounded bg-muted/20 border border-border">
        <div>
          <div class="text-muted-fg flex items-center gap-1"><Bike :size="10" />Sport</div>
          <select v-model="activityType" class="mt-0.5 w-full bg-transparent border border-border rounded px-1 py-0.5 text-[11px] font-medium focus:outline-none focus:border-primary">
            <option v-for="s in sports ?? []" :key="s.key" :value="s.key">{{ s.name }}</option>
            <option v-if="activityType && !(sports ?? []).some(s => s.key === activityType)" :value="activityType">{{ activityType }}</option>
          </select>
        </div>
        <div>
          <div class="text-muted-fg flex items-center gap-1"><Ruler :size="10" />Distance</div>
          <div class="font-medium">{{ fmtNumber(activity.distanceKm, 1, ' km') }}</div>
        </div>
        <div>
          <div class="text-muted-fg flex items-center gap-1"><Timer :size="10" />Duration</div>
          <div class="font-medium">{{ fmtDuration(activity.durationS) }}</div>
        </div>
        <div>
          <div class="text-muted-fg flex items-center gap-1"><Timer :size="10" />Moving</div>
          <div class="font-medium">{{ fmtDuration(activity.movingTimeS) }}</div>
        </div>
        <div>
          <div class="text-muted-fg flex items-center gap-1"><Mountain :size="10" />Elevation</div>
          <div class="font-medium">{{ fmtNumber(activity.elevationGainM, 0, ' m') }}</div>
        </div>
        <div>
          <div class="text-muted-fg flex items-center gap-1"><Gauge :size="10" />Avg speed</div>
          <div class="font-medium">{{ fmtNumber(activity.avgSpeedKmh, 1, ' km/h') }}</div>
        </div>
        <div>
          <div class="text-muted-fg flex items-center gap-1"><Gauge :size="10" />Max speed</div>
          <div class="font-medium">{{ fmtNumber(activity.maxSpeedKmh, 1, ' km/h') }}</div>
        </div>
        <div>
          <div class="text-muted-fg flex items-center gap-1"><ActivityIcon :size="10" />HR avg/max</div>
          <div class="font-medium">{{ activity.avgHr ?? '—' }} / {{ activity.maxHr ?? '—' }}</div>
        </div>
        <div v-if="activity.avgCadence != null || activity.maxCadence != null">
          <div class="text-muted-fg flex items-center gap-1"><RotateCw :size="10" />Cadence avg/max</div>
          <div class="font-medium">{{ activity.avgCadence ?? '—' }} / {{ activity.maxCadence ?? '—' }} rpm</div>
        </div>
        <div v-if="activity.avgPowerW != null || activity.maxPowerW != null">
          <div class="text-muted-fg flex items-center gap-1"><Zap :size="10" />Power avg/max</div>
          <div class="font-medium">{{ activity.avgPowerW ?? '—' }} / {{ activity.maxPowerW ?? '—' }} W</div>
        </div>
      </div>

      <!-- ── Start location minimap + location labels ───────────────────────── -->
      <div v-if="activity?.startLat != null && activity?.startLon != null">
        <div class="text-xs font-medium text-muted-fg mb-1 flex items-center gap-1">
          <MapPin :size="11" /> Start
          <span class="text-[10px] font-normal ml-1">{{ fmtCoord(activity.startLat, activity.startLon) }}</span>
        </div>
        <StartLocationMap
          :lat="activity.startLat ?? null"
          :lon="activity.startLon ?? null"
          :activity-id="activity.id ?? null"
        />
        <div class="grid grid-cols-2 gap-3 mt-2">
          <LocationAutocomplete
            v-model:location="startLocation"
            v-model:country="startCountry"
            label="Start location"
            placeholder-location="e.g. Tegernsee"
            placeholder-country="DE"
            compact
          />
          <LocationAutocomplete
            v-model:location="endLocation"
            v-model:country="endCountry"
            label="End location"
            placeholder-location="(blank = loop)"
            placeholder-country=""
            compact
          />
        </div>
      </div>

      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg">Name</span>
        <input v-model="name"
          class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
      </label>

      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg">Description</span>
        <textarea v-model="description" rows="3"
          class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
      </label>

      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg flex items-center gap-1">
          <Scale :size="11" /> Weight (kg)
          <span class="text-[10px] font-normal opacity-60">— captured per ride for trend charting</span>
        </span>
        <input v-model.number="weightKg" type="number" step="0.1" min="30" max="200"
          placeholder="—"
          class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
      </label>

      <div class="text-sm">
        <div class="flex items-center gap-1.5 text-xs font-medium text-muted-fg mb-1">
          <CloudSun :size="13" />
          <span>Weather</span>
        </div>
        <div class="grid grid-cols-3 gap-2">
          <div><span class="text-[10px] text-muted-fg">Temp °C</span>
            <input v-model.number="tempC" type="number" step="0.1" placeholder="—"
              class="block w-full mt-0.5 rounded border border-border bg-transparent px-2 py-1 text-xs" />
          </div>
          <div><span class="text-[10px] text-muted-fg">Humidity %</span>
            <input v-model.number="humidityPct" type="number" placeholder="—"
              class="block w-full mt-0.5 rounded border border-border bg-transparent px-2 py-1 text-xs" />
          </div>
          <div><span class="text-[10px] text-muted-fg">Wind kph</span>
            <input v-model.number="windKph" type="number" step="0.1" placeholder="—"
              class="block w-full mt-0.5 rounded border border-border bg-transparent px-2 py-1 text-xs" />
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
          <component v-if="weatherIcon(weatherCondition)" :is="weatherIcon(weatherCondition)"
            :size="22" :class="['mt-3 flex-shrink-0', weatherColor(weatherCondition)]" />
        </div>
      </div>

      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg flex items-center gap-1"><Bike :size="11" /> Gear</span>
        <select v-model="gearId"
          class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none">
          <option value="">— none —</option>
          <option v-for="g in gear" :key="g.id!" :value="g.id">{{ g.name }}<template v-if="g.type"> ({{ g.type }})</template></option>
        </select>
      </label>

      <div>
        <span class="text-xs font-medium text-muted-fg flex items-center gap-1 mb-1"><TagIcon :size="11" /> Tags</span>
        <TagCombobox v-model="selectedTags" />
      </div>

      <!-- Photos — drop personal photos onto this ride (geo-matched by EXIF) -->
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
        <!-- Legend so public-domain vs personal photos are distinguishable. -->
        <div v-if="media && media.length" class="flex items-center gap-3 mt-2 text-[10px] text-muted-fg">
          <span class="inline-flex items-center gap-1"><span class="photo-legend-dot photo-legend-dot--public"><Globe :size="9" /></span>Public domain</span>
          <span class="inline-flex items-center gap-1"><span class="photo-legend-dot photo-legend-dot--personal"><UserRound :size="9" /></span>Personal</span>
        </div>
        <div v-if="media && media.length" class="grid grid-cols-4 sm:grid-cols-5 gap-1.5 mt-1.5">
          <div v-for="m in media" :key="m.name" class="relative group aspect-square rounded overflow-hidden border border-border"
            :title="m.author ? 'by ' + m.author : ''">
            <video v-if="isVideoFile(m.name)" :src="mediaUrl(m.name)" class="w-full h-full object-cover" muted preload="metadata" />
            <img v-else :src="mediaUrl(m.name)" :alt="m.name" class="w-full h-full object-cover" loading="lazy" />

            <!-- Origin badge (bottom) -->
            <span class="absolute bottom-0 inset-x-0 px-0.5 py-px text-[7px] font-semibold text-white text-center inline-flex items-center justify-center gap-0.5"
              :class="m.origin === 'public' ? 'bg-emerald-600/85' : 'bg-slate-700/85'">
              <component :is="m.origin === 'public' ? Globe : UserRound" :size="7" />{{ originLabel(m.origin) }}
            </span>

            <!-- Always-visible delete button (top-right). -->
            <button type="button"
              class="absolute top-1 right-1 w-5 h-5 inline-flex items-center justify-center rounded-full bg-red-600 text-white shadow ring-1 ring-white/70 hover:bg-red-700 transition-colors"
              title="Delete photo" @click.stop="removeMedia(m.name)">
              <XIcon :size="12" :stroke-width="3" />
            </button>

            <!-- Public → move to personal (top-left). -->
            <button v-if="m.origin === 'public'" type="button"
              class="absolute top-1 left-1 w-5 h-5 inline-flex items-center justify-center rounded-full bg-black/60 text-white shadow ring-1 ring-white/40 hover:bg-black/85 transition-colors disabled:opacity-40"
              :disabled="movingPhoto === m.name" title="Move to personal" @click.stop="movePhotoToPersonal(m.name)">
              <UserRound :size="11" />
            </button>
          </div>
        </div>
      </div>

      <!-- ── Source provenance (read-only) ──────────────────────────────────── -->
      <div v-if="activity" class="text-[11px] border-t border-border pt-3 space-y-1.5">
        <div class="text-xs font-medium text-muted-fg flex items-center gap-1">
          <FileText :size="11" /> Source file
        </div>
        <div class="grid grid-cols-[100px_1fr_auto] gap-x-2 gap-y-1 items-center">
          <div class="text-muted-fg">Format</div>
          <div class="font-mono">{{ activity.sourceFormat?.toUpperCase() ?? '—' }}</div>
          <div />

          <div class="text-muted-fg">Original</div>
          <div class="font-mono truncate" :title="activity.originalFilename ?? ''">{{ activity.originalFilename ?? '—' }}</div>
          <button v-if="activity.originalFilename"
            class="btn-icon" title="Copy original filename"
            @click="copyToClipboard(activity.originalFilename)">
            <Copy :size="11" />
          </button>

          <div class="text-muted-fg">Stored as</div>
          <div class="font-mono truncate text-[10px] opacity-75" :title="activity.sourceFilename ?? ''">{{ activity.sourceFilename ?? '—' }}</div>
          <button v-if="activity.sourceFilename"
            class="btn-icon" title="Copy stored filename (content-addressed)"
            @click="copyToClipboard(activity.sourceFilename)">
            <Copy :size="11" />
          </button>

          <div class="text-muted-fg">Path</div>
          <div class="font-mono text-[10px] truncate" :title="sourcePath ?? ''">{{ sourcePath ?? '—' }}</div>
          <button v-if="sourcePath"
            class="btn-icon" title="Copy full path"
            @click="copyToClipboard(sourcePath)">
            <Copy :size="11" />
          </button>

          <div class="text-muted-fg flex items-center gap-1"><Hash :size="10" />SHA-256</div>
          <div class="font-mono text-[10px] truncate" :title="activity.sourceHash ?? ''">
            {{ activity.sourceHash ? activity.sourceHash.substring(0, 16) + '…' : '—' }}
          </div>
          <button v-if="activity.sourceHash"
            class="btn-icon" title="Copy full hash"
            @click="copyToClipboard(activity.sourceHash)">
            <Copy :size="11" />
          </button>

          <div class="text-muted-fg">Imported</div>
          <div class="font-mono">{{ fmtDateTime(activity.importedAt) }}</div>
          <div />

          <div class="text-muted-fg">Activity ID</div>
          <div class="font-mono text-[10px] truncate" :title="activity.id ?? ''">{{ activity.id ?? '—' }}</div>
          <button v-if="activity.id" class="btn-icon" title="Copy activity ID"
            @click="copyToClipboard(activity.id)">
            <Copy :size="11" />
          </button>
        </div>
      </div>
    </div>

    <div class="flex items-center justify-end gap-2 px-4 py-2.5 border-t border-border bg-muted/10">
      <button class="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded border border-border text-muted-fg hover:text-foreground"
        @click="emit('cancel')">
        <XIcon :size="12" /> Cancel
      </button>
      <button class="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded bg-primary text-primary-fg hover:bg-primary/90 disabled:opacity-50"
        :disabled="saveMut.isPending.value || !name.trim()" @click="saveMut.mutate()">
        <Save :size="12" /> {{ saveMut.isPending.value ? 'Saving…' : 'Save' }}
      </button>
    </div>
  </div>
</template>
