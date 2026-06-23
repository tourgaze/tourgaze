<script setup lang="ts">
import { onMounted, onUnmounted, watch, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { getChartTrack } from '@/api/client'
import { tileUrl } from '@/lib/mapStyle'
import { markerCategory, markerIconSvg } from '@/markerCategories'

/**
 * Compact basemap preview. Two modes:
 *   - With activityId: fetches the LTTB-reduced chart track (~800 pts, tiny
 *     payload) and draws the full route as a line with start/end markers,
 *     fitted to bounds.
 *   - Without activityId (inbox staging — no Activity row yet): falls back
 *     to a single red dot at the FIT-parsed start point.
 */
const props = withDefaults(defineProps<{
  lat: number | null
  lon: number | null
  activityId?: string | null
  /** Pre-supplied route points (e.g. inbox preview, before an Activity exists). */
  points?: { lat: number; lon: number }[] | null
  /** Tailwind height class for the map box. */
  heightClass?: string
  /** Allow panning/zooming + click-to-place (emits `place`). Off = read-only preview. */
  interactive?: boolean
  /** Markers (places) to render as pins. */
  markers?: { lat: number; lon: number; category: string }[] | null
  /** A draft marker being placed/edited, rendered with a highlight. */
  draft?: { lat: number; lon: number; category: string } | null
}>(), { heightClass: 'h-40', interactive: false })

const emit = defineEmits<{ place: [{ lat: number; lon: number }] }>()

const containerEl = ref<HTMLDivElement | null>(null)
let map: maplibregl.Map | null = null
let marker: maplibregl.Marker | null = null
let markerEls: maplibregl.Marker[] = []
let routeAdded = false

/** Render the passed markers + the draft as teardrop pins (reused styling). */
function renderMarkers() {
  if (!map) return
  markerEls.forEach(m => m.remove())
  markerEls = []
  const pin = (lat: number, lon: number, category: string, draft: boolean) => {
    const cat = markerCategory(category)
    const el = document.createElement('div')
    el.className = 'map-marker-pin'
    el.style.background = cat.color
    if (draft) el.style.outline = '2px solid var(--color-primary, #2563eb)'
    el.innerHTML = markerIconSvg(cat)
    markerEls.push(new maplibregl.Marker({ element: el, anchor: 'bottom' }).setLngLat([lon, lat]).addTo(map!))
  }
  for (const mk of props.markers ?? []) pin(mk.lat, mk.lon, mk.category, false)
  if (props.draft) pin(props.draft.lat, props.draft.lon, props.draft.category, true)
}

const { data: routePoints } = useQuery({
  queryKey: ['minimap-track', () => props.activityId],
  queryFn: () => getChartTrack(props.activityId!),
  enabled: () => props.activityId != null,
  staleTime: 60 * 60 * 1000,
})

function ensureMap() {
  if (!containerEl.value || props.lat == null || props.lon == null) return
  if (map) return

  map = new maplibregl.Map({
    container: containerEl.value,
    interactive: props.interactive,    // read-only preview unless placing markers
    attributionControl: { compact: true },   // OSM/CARTO terms require visible credit
    style: {
      version: 8,
      sources: {
        'osm-raster': {
          type: 'raster',
          tiles: [tileUrl('osm')],
          tileSize: 256,
          maxzoom: 19,
          attribution: '© OpenStreetMap contributors',
        },
        // Separate raster-dem source for hillshade (matches ActivityMap convention).
        'terrain-dem-hillshade': {
          type: 'raster-dem',
          tiles: [tileUrl('terrain')],
          tileSize: 256,
          encoding: 'terrarium',
          maxzoom: 15,
        },
      },
      layers: [
        { id: 'osm-raster-layer', type: 'raster', source: 'osm-raster' },
        {
          id: 'hillshade',
          type: 'hillshade',
          source: 'terrain-dem-hillshade',
          paint: {
            'hillshade-exaggeration': 0.5,
            'hillshade-illumination-direction': 335,
            'hillshade-shadow-color': '#3D2B1F',
            'hillshade-highlight-color': '#FAFAF8',
            'hillshade-accent-color': '#5C4033',
          },
        },
      ],
    },
    center: [props.lon, props.lat],
    zoom: 11,
  })

  map.on('load', () => {
    if (!map || props.lat == null || props.lon == null) return
    const el = document.createElement('div')
    el.className = 'map-hover-cursor'
    marker = new maplibregl.Marker({ element: el, anchor: 'center' })
      .setLngLat([props.lon, props.lat])
      .addTo(map)
    // Draw a route if we have one: explicit points (inbox preview) take
    // priority, else the activity's chart track if it's already cached.
    if (props.points?.length) drawRoute(props.points)
    else if (routePoints.value?.length) drawRoute(routePoints.value)
    renderMarkers()
  })
  // Click-to-place (only when interactive) → let the parent open its editor.
  map.on('click', (e) => {
    if (props.interactive) emit('place', { lat: e.lngLat.lat, lon: e.lngLat.lng })
  })
}

function drawRoute(points: { lat: number; lon: number }[]) {
  if (!map || !points.length) return
  const coords = points.map(p => [p.lon, p.lat] as [number, number])
  const data: GeoJSON.FeatureCollection = {
    type: 'FeatureCollection',
    features: [
      { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } },
      { type: 'Feature', properties: { point: 'start' }, geometry: { type: 'Point', coordinates: coords[0] } },
      { type: 'Feature', properties: { point: 'end' }, geometry: { type: 'Point', coordinates: coords[coords.length - 1] } },
    ],
  }
  if (!routeAdded) {
    map.addSource('mini-track', { type: 'geojson', data, tolerance: 1, maxzoom: 14, buffer: 16 })
    map.addLayer({
      id: 'mini-track-casing', type: 'line', source: 'mini-track',
      filter: ['==', '$type', 'LineString'],
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': '#ffffff', 'line-width': 4, 'line-opacity': 0.8 },
    })
    map.addLayer({
      id: 'mini-track-line', type: 'line', source: 'mini-track',
      filter: ['==', '$type', 'LineString'],
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': '#3b82f6', 'line-width': 2.5, 'line-opacity': 0.95 },
    })
    map.addLayer({
      id: 'mini-track-start', type: 'circle', source: 'mini-track',
      filter: ['==', ['get', 'point'], 'start'],
      paint: { 'circle-radius': 4, 'circle-color': '#22c55e', 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 1.5 },
    })
    map.addLayer({
      id: 'mini-track-end', type: 'circle', source: 'mini-track',
      filter: ['==', ['get', 'point'], 'end'],
      paint: { 'circle-radius': 4, 'circle-color': '#ef4444', 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 1.5 },
    })
    routeAdded = true
  } else {
    (map.getSource('mini-track') as maplibregl.GeoJSONSource).setData(data)
  }
  // Once the full track is shown the start-point dot is redundant.
  marker?.remove()
  marker = null
  // Fit to track bounds with a small pixel pad.
  const b = coords.reduce(
    (bb, c) => bb.extend(c as maplibregl.LngLatLike),
    new maplibregl.LngLatBounds(coords[0], coords[0]),
  )
  map.fitBounds(b, { padding: 18, duration: 600, maxZoom: 14 })
}

onMounted(() => ensureMap())

onUnmounted(() => {
  marker?.remove(); marker = null
  markerEls.forEach(m => m.remove()); markerEls = []
  map?.remove(); map = null
  routeAdded = false
})

// Re-render pins whenever the markers / draft change.
watch(() => [props.markers, props.draft], () => renderMarkers(), { deep: true })

// Recenter + reset marker when the parent passes a different start location.
watch(() => [props.lat, props.lon], ([lat, lon]) => {
  if (!map || lat == null || lon == null) return
  if (routeAdded) return  // route fit-bounds owns the camera once a track is drawn
  map.setCenter([lon, lat])
  if (marker) marker.setLngLat([lon, lat])
})

// Draw the route whenever the track query lands (or a new activity is picked).
watch(routePoints, (pts) => {
  if (!map || !pts?.length) return
  drawRoute(pts)
})

// Draw whenever explicit preview points arrive (inbox route preview).
watch(() => props.points, (pts) => {
  if (!map || !pts?.length) return
  drawRoute(pts)
})
</script>

<template>
  <div v-if="lat != null && lon != null" ref="containerEl" class="w-full rounded border border-border overflow-hidden" :class="heightClass" />
  <div v-else class="w-full rounded border border-dashed border-border flex items-center justify-center text-[11px] text-muted-fg" :class="heightClass">
    No GPS start point in this file
  </div>
</template>
