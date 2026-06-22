<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { onKeyStroke } from '@vueuse/core'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { push } from 'notivue'
import { getAllMarkers, getActivities, createMarker, updateMarker, deleteMarker, type Marker } from '@/api/client'
import { MARKER_CATEGORIES, markerCategory, markerIconSvg } from '@/markerCategories'
import { rasterStyle } from '@/lib/mapStyle'
import { Trash2, Pencil, MapPin } from 'lucide-vue-next'

const qc = useQueryClient()
const { data: markers } = useQuery({ queryKey: ['markers', 'all'], queryFn: getAllMarkers })
// Resolve a ride-scoped marker's activityId → ride name + link.
const { data: activities } = useQuery({ queryKey: ['activities'], queryFn: getActivities })
const activityById = computed(() => {
  const m = new Map<string, string>()
  for (const a of activities.value ?? []) if (a.id) m.set(a.id, a.name || 'Untitled ride')
  return m
})
function activityName(id: string | null): string | null {
  return id ? (activityById.value.get(id) ?? null) : null
}
const markerCat = markerCategory
const categories = MARKER_CATEGORIES

// ── Filter + sort ───────────────────────────────────────────────────────────
const filter = ref('')
const sort = ref<{ key: 'label' | 'category' | 'scope'; dir: 'asc' | 'desc' }>({ key: 'label', dir: 'asc' })
function setSort(key: 'label' | 'category' | 'scope') {
  if (sort.value.key === key) sort.value.dir = sort.value.dir === 'asc' ? 'desc' : 'asc'
  else sort.value = { key, dir: 'asc' }
}
const rows = computed(() => {
  const q = filter.value.trim().toLowerCase()
  let list = markers.value ?? []
  if (q) list = list.filter(m => (m.label || '').toLowerCase().includes(q)
    || m.category.toLowerCase().includes(q) || (m.description || '').toLowerCase().includes(q))
  const { key, dir } = sort.value, sign = dir === 'asc' ? 1 : -1
  return [...list].sort((a, b) => {
    let av: string | number, bv: string | number
    if (key === 'scope') { av = a.activityId ? 1 : 0; bv = b.activityId ? 1 : 0 }
    else if (key === 'category') { av = markerCat(a.category).label; bv = markerCat(b.category).label }
    else { av = (a.label || markerCat(a.category).label).toLowerCase(); bv = (b.label || markerCat(b.category).label).toLowerCase() }
    return av < bv ? -sign : av > bv ? sign : 0
  })
})

// ── Map ─────────────────────────────────────────────────────────────────────
let map: maplibregl.Map | null = null
let pins: maplibregl.Marker[] = []
const mapEl = ref<HTMLElement | null>(null)

function renderPins(fit = true) {
  if (!map || !map.isStyleLoaded()) return
  pins.forEach(p => p.remove()); pins = []
  const list = markers.value ?? []
  for (const mk of list) {
    const cat = markerCat(mk.category)
    const el = document.createElement('div')
    el.className = mk.activityId ? 'map-marker-pin' : 'map-marker-pin map-marker-pin--general'
    el.style.background = cat.color
    // Highlight the row-selected marker so "click in list → show on map" is obvious.
    if (mk.id === selectedId.value) {
      el.style.outline = '3px solid hsl(var(--primary))'
      el.style.outlineOffset = '1px'
      el.style.zIndex = '10'
    }
    el.title = (mk.activityId ? '' : 'General · ') + (mk.label || cat.label)
    el.innerHTML = markerIconSvg(cat)
    el.addEventListener('click', (e) => { e.stopPropagation(); select(mk) })
    pins.push(new maplibregl.Marker({ element: el, anchor: 'bottom' }).setLngLat([mk.lon, mk.lat]).addTo(map!))
  }
  if (fit) fitAll()
}
function fitAll() {
  if (!map) return
  const list = markers.value ?? []
  if (list.length < 1) return
  const b = list.reduce((bb, m) => bb.extend([m.lon, m.lat]),
    new maplibregl.LngLatBounds([list[0].lon, list[0].lat], [list[0].lon, list[0].lat]))
  map.fitBounds(b, { padding: 60, maxZoom: 14, duration: 500 })
}
// Focus in to street level on click so the jump is always visible — even when
// the marker was already roughly centred (e.g. you only have one). Only zooms
// IN; keeps a deeper zoom if you're already closer.
function flyTo(m: Marker) { map?.flyTo({ center: [m.lon, m.lat], zoom: Math.max(map.getZoom(), 16), duration: 700 }) }
// Click a list row → select it (fly + highlight its pin).
const selectedId = ref<string | null>(null)
function select(m: Marker) { selectedId.value = m.id; flyTo(m) }

// ── Editor ──────────────────────────────────────────────────────────────────
// `editing` holds either an existing marker (has id) or a NEW draft (id === '',
// created by clicking the map). save() branches on the id.
const editing = ref<Marker | null>(null)
const isNew = computed(() => editing.value?.id === '')
// A draggable preview pin shown while placing a new marker, so the user sees
// (and can fine-tune) where it will land before saving.
let draftPin: maplibregl.Marker | null = null
function clearDraft() { draftPin?.remove(); draftPin = null }
function cancelEdit() { editing.value = null; clearDraft() }
onKeyStroke('Escape', (e) => { if (editing.value) { e.preventDefault(); cancelEdit() } })
function editMarker(m: Marker) { clearDraft(); editing.value = { ...m }; flyTo(m) }

// Click an empty spot on the map → start a new general marker there.
function startCreate(lng: number, lat: number) {
  clearDraft()
  editing.value = { id: '', activityId: null, lat, lon: lng, label: '', description: '', category: categories[0].key }
  if (!map) return
  const el = document.createElement('div')
  el.className = 'map-marker-pin map-marker-pin--general'
  el.style.background = markerCat(categories[0].key).color
  el.style.outline = '3px solid hsl(var(--primary))'
  el.style.outlineOffset = '1px'
  el.innerHTML = markerIconSvg(markerCat(categories[0].key))
  draftPin = new maplibregl.Marker({ element: el, anchor: 'bottom', draggable: true }).setLngLat([lng, lat]).addTo(map)
  draftPin.on('dragend', () => {
    const ll = draftPin!.getLngLat()
    if (editing.value) { editing.value.lat = ll.lat; editing.value.lon = ll.lng }
  })
}
// Keep the draft pin's colour/icon in sync with the category chosen in the editor.
watch(() => editing.value?.category, (cat) => {
  if (!isNew.value || !draftPin || !cat) return
  const el = draftPin.getElement()
  el.style.background = markerCat(cat).color
  el.innerHTML = markerIconSvg(markerCat(cat))
})

function refresh() { return qc.invalidateQueries({ queryKey: ['markers'] }) }
async function save() {
  const m = editing.value; if (!m) return
  try {
    if (m.id === '') {
      await createMarker({ activityId: null, lat: m.lat, lon: m.lon, label: m.label, description: m.description, category: m.category })
    } else {
      await updateMarker(m.id, { label: m.label, description: m.description, category: m.category })
    }
    await refresh(); cancelEdit()
  } catch { push.error('Could not save marker') }
}
async function remove(id: string) {
  try { await deleteMarker(id); await refresh(); if (editing.value?.id === id) cancelEdit() }
  catch { push.error('Could not delete marker') }
}

onMounted(() => {
  if (!mapEl.value) return
  map = new maplibregl.Map({
    container: mapEl.value,
    style: rasterStyle('osm'),
    center: [11, 48], zoom: 6, attributionControl: { compact: true },
  })
  map.addControl(new maplibregl.NavigationControl(), 'top-right')
  map.on('load', renderPins)
  // Three ways to drop a new general marker, mirroring the ride map's gestures
  // so the muscle memory is consistent:
  //  • left-click empty map  (discoverable on this dedicated page; pin clicks
  //    stopPropagation so they don't trigger it)
  //  • middle-click          (places immediately)
  //  • right double-click    (single right-click just suppresses the menu)
  map.on('click', (e) => startCreate(e.lngLat.lng, e.lngLat.lat))
  map.on('mousedown', (e) => {
    if (e.originalEvent.button === 1) {
      e.preventDefault()
      e.originalEvent.preventDefault()       // stop browser autoscroll
      startCreate(e.lngLat.lng, e.lngLat.lat)
    }
  })
  let lastRightClick = 0
  map.on('contextmenu', (e) => {
    e.preventDefault()                        // suppress the browser context menu
    const now = performance.now()
    if (now - lastRightClick < 450) { lastRightClick = 0; startCreate(e.lngLat.lng, e.lngLat.lat) }
    else lastRightClick = now
  })
  map.getCanvas().style.cursor = 'crosshair'
})
watch(markers, () => renderPins())
watch(selectedId, () => renderPins(false))   // re-highlight without re-fitting bounds
onUnmounted(() => { clearDraft(); pins.forEach(p => p.remove()); map?.remove(); map = null })
</script>

<template>
  <div class="h-full w-full flex min-h-0">
    <!-- Table -->
    <div class="w-[380px] max-w-[42%] shrink-0 flex flex-col border-r border-border min-h-0">
      <div class="pane-header"><MapPin :size="13" /><h2>Markers<span v-if="markers" class="opacity-60"> · {{ markers.length }}</span></h2></div>
      <div class="p-2">
        <input v-model="filter" type="text" placeholder="Filter markers…"
          class="w-full px-2 py-1 text-[12px] rounded-md border border-border bg-background text-foreground focus:outline-none focus:border-primary" />
      </div>
      <div class="flex-1 min-h-0 overflow-y-auto px-2 pb-2 custom-scrollbar">
        <table v-if="rows.length" class="w-full text-[11px] border-collapse">
          <thead class="sticky top-0 bg-background z-10">
            <tr class="text-[10px] uppercase tracking-wide text-muted-fg border-b border-border">
              <th class="py-1 pr-2 text-left font-semibold cursor-pointer select-none" @click="setSort('label')">Name<span v-if="sort.key==='label'">{{ sort.dir==='asc'?' ▲':' ▼' }}</span></th>
              <th class="py-1 pr-2 text-left font-semibold cursor-pointer select-none w-20" @click="setSort('category')">Type<span v-if="sort.key==='category'">{{ sort.dir==='asc'?' ▲':' ▼' }}</span></th>
              <th class="py-1 pr-2 text-left font-semibold cursor-pointer select-none w-16" @click="setSort('scope')">Scope<span v-if="sort.key==='scope'">{{ sort.dir==='asc'?' ▲':' ▼' }}</span></th>
              <th class="py-1 w-14"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in rows" :key="m.id" class="border-b border-border/60 cursor-pointer"
              :class="m.id === selectedId ? 'bg-primary/10' : 'hover:bg-primary/5'" @click="select(m)">
              <td class="py-1.5 pr-2">
                <div class="flex items-center gap-1.5 min-w-0">
                  <span class="inline-flex items-center justify-center w-5 h-5 rounded-full text-white shrink-0"
                    :style="{ background: markerCat(m.category).color }" v-html="markerIconSvg(m.category)"></span>
                  <span class="min-w-0">
                    <span class="block font-medium text-foreground truncate">{{ m.label || markerCat(m.category).label }}</span>
                    <span v-if="m.description" class="block text-[10px] text-muted-fg truncate">{{ m.description }}</span>
                    <!-- Ride-scoped marker → link to its ride. -->
                    <router-link v-if="m.activityId" :to="`/tour/${m.activityId}`" @click.stop
                      class="block text-[10px] text-primary hover:underline truncate"
                      :title="`Open ride: ${activityName(m.activityId) || m.activityId}`">
                      ↳ {{ activityName(m.activityId) || 'open ride' }}
                    </router-link>
                  </span>
                </div>
              </td>
              <td class="py-1.5 pr-2 text-muted-fg">{{ markerCat(m.category).label }}</td>
              <td class="py-1.5 pr-2 text-[9px]">
                <span :class="m.activityId ? 'text-muted-fg' : 'text-primary font-semibold'">{{ m.activityId ? 'ride' : 'general' }}</span>
              </td>
              <td class="py-1.5 text-right whitespace-nowrap">
                <button class="btn-icon btn-icon-primary" title="Edit" @click.stop="editMarker(m)"><Pencil :size="13" /></button>
                <button class="btn-icon btn-icon-danger" title="Delete" @click.stop="remove(m.id)"><Trash2 :size="13" /></button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="h-full flex flex-col items-center justify-center text-center text-[11px] text-muted-fg gap-1 py-8">
          <MapPin :size="22" class="opacity-50" />
          <span>No markers yet. Click anywhere on the map to add one, or add them from a ride's map (middle-click or right-double-click).</span>
        </div>
      </div>
    </div>

    <!-- Map -->
    <!-- NOTE: the map div must be h-full/w-full, NOT absolute inset-0 —
         maplibre-gl.css forces `.maplibregl-map { position: relative }`, which
         overrides Tailwind's `absolute`, so `inset-0` is ignored and the element
         collapses to height 0 (blank map). Sizing it explicitly avoids that. -->
    <div class="relative flex-1 min-h-0">
      <div ref="mapEl" class="h-full w-full" />

      <!-- Discoverability hint (hidden while the editor is open). -->
      <div v-if="!editing"
        class="absolute bottom-3 left-1/2 -translate-x-1/2 z-[500] px-2.5 py-1 rounded-full bg-background/90 backdrop-blur-sm border border-border text-[11px] text-muted-fg shadow flex items-center gap-1.5 pointer-events-none">
        <MapPin :size="12" /> Click the map to add a marker
      </div>

      <!-- Editor -->
      <div v-if="editing"
        class="absolute top-3 left-1/2 -translate-x-1/2 z-[1000] w-72 max-w-[92%] bg-background/95 backdrop-blur-sm border border-border rounded-xl shadow-2xl p-3 space-y-2.5">
        <div class="flex items-center justify-between">
          <span class="text-[11px] font-semibold uppercase tracking-wide text-muted-fg">{{ isNew ? 'New marker · drag pin to adjust' : (editing.activityId ? 'Marker · on a ride' : 'Marker · general') }}</span>
          <button class="btn-icon" title="Close" @click="cancelEdit">✕</button>
        </div>
        <router-link v-if="!isNew && editing.activityId" :to="`/tour/${editing.activityId}`"
          class="block text-[11px] text-primary hover:underline truncate -mt-1"
          :title="`Open ride: ${activityName(editing.activityId) || editing.activityId}`">
          ↳ {{ activityName(editing.activityId) || 'open ride' }}
        </router-link>
        <input v-model="editing.label" type="text" placeholder="Label"
          class="w-full px-2 py-1.5 text-sm rounded-md border border-border bg-background text-foreground focus:outline-none focus:border-primary" />
        <div class="flex flex-wrap gap-1">
          <button v-for="c in categories" :key="c.key" type="button" :title="c.label"
            class="w-7 h-7 rounded-full flex items-center justify-center text-white transition-transform"
            :class="editing.category === c.key ? 'ring-2 ring-offset-1 ring-offset-background ring-foreground scale-110' : 'opacity-70 hover:opacity-100'"
            :style="{ background: c.color }" @click="editing.category = c.key" v-html="markerIconSvg(c)"></button>
        </div>
        <textarea v-model="editing.description" rows="3" placeholder="Description…"
          class="w-full px-2 py-1.5 text-sm rounded-md border border-border bg-background text-foreground resize-none focus:outline-none focus:border-primary"></textarea>
        <div class="flex items-center justify-between pt-0.5">
          <div class="flex items-center gap-1.5">
            <button v-if="!isNew" class="px-2 py-1 text-[11px] font-medium rounded border border-red-300 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors" @click="remove(editing.id)">Delete</button>
            <button class="px-2 py-1 text-[11px] font-medium rounded border border-border text-muted-fg hover:text-foreground transition-colors" @click="cancelEdit">{{ isNew ? 'Cancel' : 'Close' }}</button>
          </div>
          <button class="px-3 py-1 text-[11px] font-semibold rounded bg-primary text-white hover:opacity-90 transition-opacity" @click="save">{{ isNew ? 'Add marker' : 'Save' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
