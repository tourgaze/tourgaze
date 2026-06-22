<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { getTileProviders, type TileProvider } from '@/api/client'
import type { HrZone } from '@/composables/useHrZones'
import type { ReplayStrategy } from '@/composables/replayStrategies'
import ActivityMap from '@/components/ActivityMap.vue'

/**
 * The map viewport is renderer-pluggable. Each tile provider declares which
 * renderer owns the center area — today only `maplibre` exists (handled by
 * `ActivityMap.vue`), but the abstraction lets a future Three.js / streets.gl
 * / Cesium renderer slot in without touching the rest of the app.
 *
 * Selection rules:
 *   1. Fetch the provider catalog from `/api/tile-providers` so the picker
 *      and the renderer choice both come from one source of truth.
 *   2. Look up the active provider by its id (from the `map.provider` setting,
 *      passed in via `tileProvider`).
 *   3. Mount the renderer that provider declares (`provider.renderer`).
 *
 * If the active provider is unknown (stale setting, network error), fall
 * back to the MapLibre renderer with the raw provider id — ActivityMap can
 * still handle unknown raster ids via its proxy path.
 */
const props = defineProps<{
  activityId: string
  tileProvider?: string
  hoverIndex?: number | null
  colorMode?: 'none' | 'hr' | 'slope'
  hrZones?: HrZone[]
  isPlaying?: boolean
  replayStrategy?: ReplayStrategy
  hoverCoords?: [number, number] | null
  playFrac?: number | null
  isFollowing?: boolean
  showHillshade?: boolean
  showPhotos?: boolean
  compareLines?: { id: string; color: string; points: { lat: number; lon: number }[] }[] | null
  compareCursors?: { id: string; color: string; lat: number; lon: number }[] | null
  highlights?: { passes: any[]; peaks: any[] } | null
  nearbyTours?: { id: string; name: string; lat: number; lon: number }[]
}>()

const emit = defineEmits<{
  (e: 'userInteracted'): void
  (e: 'photoJump', index: number): void
}>()

const { data: providers } = useQuery({
  queryKey: ['tile-providers'],
  queryFn: getTileProviders,
  staleTime: 60 * 60 * 1000,  // catalog is essentially static — refetch once an hour
})

const activeProvider = computed<TileProvider | null>(() => {
  const id = props.tileProvider ?? 'osm'
  return providers.value?.find(p => p.id === id) ?? null
})

const renderer = computed(() => activeProvider.value?.renderer ?? 'maplibre')

// Forward the inner renderer's imperative API (e.g. `animateToPoint(idx)` so
// the elevation chart's click-to-jump can call into the map). Without this
// the chart's `mapRef.value.animateToPoint(...)` is a no-op — the parent
// holds a ref to MapRenderer, not to the underlying ActivityMap, and
// MapRenderer would otherwise expose nothing.
const inner = ref<{ animateToPoint?: (idx: number) => void; centerTour?: () => void; flyToCoords?: (lon: number, lat: number) => void; openMarkerEditor?: (m: any) => void } | null>(null)
defineExpose({
  animateToPoint: (idx: number) => inner.value?.animateToPoint?.(idx),
  centerTour: () => inner.value?.centerTour?.(),
  flyToCoords: (lon: number, lat: number) => inner.value?.flyToCoords?.(lon, lat),
  openMarkerEditor: (m: any) => inner.value?.openMarkerEditor?.(m),
})
</script>

<template>
  <!-- MapLibre renderer — handles raster + vector tile providers natively.
       The fallback path also lands here so we always show *something* even
       before the provider catalog is loaded. -->
  <!-- Explicit `key` on both branches so Vue treats them as distinct vnodes
       and unmounts one fully before mounting the other. Without that, a
       branch switch (e.g. provider catalog loading swaps renderer mid-flow)
       leaves Vue with a stale child component reference and throws "can't
       access property 'emitsOptions', component is null" during the next
       patch. -->
  <ActivityMap
    v-if="renderer === 'maplibre'"
    key="renderer-maplibre"
    ref="inner"
    :activity-id="activityId"
    :tile-provider="tileProvider"
    :hover-index="hoverIndex"
    :color-mode="colorMode"
    :hr-zones="hrZones"
    :is-playing="isPlaying"
    :replay-strategy="replayStrategy"
    :hover-coords="hoverCoords"
    :play-frac="playFrac"
    :active-provider="activeProvider"
    :is-following="isFollowing"
    :show-hillshade="showHillshade"
    :show-photos="showPhotos"
    :compare-lines="compareLines"
    :compare-cursors="compareCursors"
    :highlights="highlights"
    :nearby-tours="nearbyTours"
    @user-interacted="emit('userInteracted')"
    @photo-jump="emit('photoJump', $event)"
  />

  <!-- Future renderers go here. Each is responsible for the full center area
       (track line, marker, camera). They consume the same prop contract as
       ActivityMap so swapping is a one-line change. -->
  <div v-else key="renderer-fallback" class="w-full h-full flex items-center justify-center text-sm text-muted-fg">
    Renderer "{{ renderer }}" not available
  </div>
</template>
