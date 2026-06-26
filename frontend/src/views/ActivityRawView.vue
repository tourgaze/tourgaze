<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { getActivities, getRawTrack, type RawTrack } from '@/api/client'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, AxisPointerComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { ArrowLeft, Gauge } from 'lucide-vue-next'

// Raw per-point channels for one ride — the "all the data" page. The primary
// source is the reduced ride sidecar (same one the detail chart renders); a
// toggle pulls the full-resolution track on demand. Each available channel gets
// its own compact line chart, driven straight off the columnar /raw response.
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, AxisPointerComponent])

const route = useRoute()
const router = useRouter()
const activityId = computed(() => (route.params.id as string) || null)

// Reduced (sidecar) by default; full-res is opt-in and heavier.
const fullRes = ref(false)

const { data: activities } = useQuery({ queryKey: ['activities'], queryFn: getActivities })
const activity = computed(() => activities.value?.find(a => a.id === activityId.value) ?? null)

const { data: raw, isFetching, isError } = useQuery({
  queryKey: computed(() => ['raw', activityId.value, fullRes.value]),
  queryFn: () => getRawTrack(activityId.value!, { reduced: !fullRes.value }),
  enabled: computed(() => activityId.value != null),
  staleTime: 5 * 60 * 1000,
})

// Channel catalog — label, unit, colour, and how to pull/transform the column
// out of the columnar RawTrack. Speed is stored m/s but shown km/h.
type Channel = {
  key: string
  label: string
  unit: string
  color: string
  values: (r: RawTrack) => (number | null | undefined)[] | undefined
  digits?: number
}
const CHANNELS: Channel[] = [
  { key: 'alt', label: 'Elevation', unit: 'm', color: '#10b981', values: r => r.alt },
  { key: 'hr', label: 'Heart rate', unit: 'bpm', color: '#ef4444', values: r => r.hr },
  { key: 'speed', label: 'Speed', unit: 'km/h', color: '#3b82f6', values: r => r.speed?.map(v => (v == null ? null : v * 3.6)), digits: 1 },
  { key: 'cadence', label: 'Cadence', unit: 'rpm', color: '#a855f7', values: r => r.cadence },
  { key: 'power', label: 'Power', unit: 'W', color: '#f59e0b', values: r => r.power },
]

type Series = { ch: Channel; data: (number | null)[]; min: number; avg: number; max: number }
const series = computed<Series[]>(() => {
  const r = raw.value
  if (!r) return []
  const out: Series[] = []
  for (const ch of CHANNELS) {
    const col = ch.values(r)
    if (!col) continue
    const data = col.map(v => (v == null ? null : v))
    const present = data.filter((v): v is number => v != null)
    if (!present.length) continue
    const min = Math.min(...present)
    const max = Math.max(...present)
    const avg = present.reduce((a, b) => a + b, 0) / present.length
    out.push({ ch, data, min, avg, max })
  }
  return out
})

const fmt = (v: number, digits = 0) => v.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits })

function chartOption(s: Series) {
  // [index, value] pairs on a numeric x-axis — robust against container resize
  // races that a category axis mis-positions. Nulls become gaps.
  const pairs = s.data.map((v, i) => [i, v] as [number, number | null])
  return {
    animation: false,
    grid: { left: 8, right: 10, top: 8, bottom: 6, containLabel: true },
    xAxis: { type: 'value', show: false, min: 0, max: s.data.length - 1 },
    yAxis: {
      type: 'value', scale: true,
      splitLine: { lineStyle: { color: 'rgba(127,127,127,0.12)' } },
      axisLabel: { fontSize: 9, color: 'rgb(148,148,148)', hideOverlap: true },
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: s.ch.color, width: 1 } },
      formatter: (p: { value: [number, number | null] }[]) => {
        const v = p?.[0]?.value?.[1]
        return v == null ? '—' : `${fmt(v, s.ch.digits ?? 0)} ${s.ch.unit}`
      },
    },
    series: [{
      type: 'line', data: pairs, showSymbol: false, smooth: false,
      connectNulls: false, lineStyle: { width: 1.2, color: s.ch.color },
      // Fill reads as "terrain" — apt for elevation, noise for the rest.
      ...(s.ch.key === 'alt' ? { areaStyle: { color: s.ch.color, opacity: 0.1 } } : {}),
    }],
  }
}
</script>

<template>
  <div class="h-full flex flex-col overflow-hidden">
    <!-- Header — mirrors ActivityDetailView's bar so the two pages feel like one. -->
    <div class="flex items-center gap-2 px-3 py-1.5 border-b border-border bg-background">
      <button class="btn-icon" title="Back to ride" @click="router.push(`/tour/${activityId}`)"><ArrowLeft :size="14" /></button>
      <Gauge :size="13" class="text-primary shrink-0" />
      <span class="text-[12px] font-semibold truncate">Raw data</span>
      <span v-if="activity" class="text-[11px] text-muted-fg truncate">· {{ activity.name }}</span>
      <span class="flex-1" />
      <span v-if="raw" class="text-[10px] text-muted-fg font-mono tabular-nums">{{ raw?.count?.toLocaleString() }} pts</span>
      <label
        class="flex items-center gap-1 text-[10px] text-muted-fg cursor-pointer select-none"
        title="Reduced = the LTTB ride sidecar (primary source). Full = the complete per-point track."
      >
        <input type="checkbox" v-model="fullRes" class="accent-primary" />
        <span>Full resolution</span>
      </label>
    </div>

    <div class="flex-1 min-h-0 overflow-y-auto p-3">
      <div v-if="isFetching && !raw" class="text-sm text-muted-fg opacity-60 select-none p-6 text-center">Loading raw data…</div>
      <div v-else-if="isError" class="text-sm text-red-400 p-6 text-center">Could not load raw data.</div>
      <div v-else-if="!series.length" class="text-sm text-muted-fg opacity-60 select-none p-6 text-center">
        This ride carries no charted sensor channels.
      </div>

      <div v-else class="grid gap-3" style="grid-template-columns: repeat(auto-fit, minmax(320px, 1fr))">
        <div v-for="s in series" :key="s.ch.key" class="rounded-lg border border-border bg-card overflow-hidden">
          <div class="flex items-baseline gap-2 px-3 pt-2 pb-1">
            <span class="w-2 h-2 rounded-full shrink-0" :style="{ background: s.ch.color }" />
            <span class="text-[12px] font-semibold">{{ s.ch.label }}</span>
            <span class="text-[10px] text-muted-fg">{{ s.ch.unit }}</span>
            <span class="flex-1" />
            <span class="text-[10px] text-muted-fg font-mono tabular-nums">
              min {{ fmt(s.min, s.ch.digits ?? 0) }} · avg {{ fmt(s.avg, s.ch.digits ?? 0) }} · max {{ fmt(s.max, s.ch.digits ?? 0) }}
            </span>
          </div>
          <div class="h-40">
            <VChart :option="chartOption(s)" autoresize class="h-full w-full" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
