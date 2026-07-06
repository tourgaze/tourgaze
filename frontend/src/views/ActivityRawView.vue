<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { useVirtualList } from '@vueuse/core'
import { getActivities, getRawTrack, type RawTrack } from '@/api/client'
import { fmtDuration } from '@/lib/format'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, AxisPointerComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { ArrowLeft, Gauge, LineChart as LineChartIcon, Table as TableIcon } from 'lucide-vue-next'

// Raw per-point channels for one ride — the "all the data" page. The primary
// source is the reduced ride sidecar (same one the detail chart renders); a
// toggle pulls the full-resolution track on demand. Two ways to read it:
// per-channel charts, or a virtualised table of every sample. A summary strip
// up top carries the authoritative ride aggregates.
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, AxisPointerComponent])

const route = useRoute()
const router = useRouter()
const activityId = computed(() => (route.params.id as string) || null)

const fullRes = ref(false)
const viewMode = ref<'charts' | 'table'>('charts')

const { data: activities } = useQuery({ queryKey: ['activities'], queryFn: getActivities })
const activity = computed(() => activities.value?.find(a => a.id === activityId.value) ?? null)

const { data: raw, isFetching, isError } = useQuery({
  queryKey: computed(() => ['raw', activityId.value, fullRes.value]),
  queryFn: () => getRawTrack(activityId.value!, { reduced: !fullRes.value }),
  enabled: computed(() => activityId.value != null),
  staleTime: 5 * 60 * 1000,
})

const fmt = (v: number, digits = 0) => v.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits })
const cell = (v: number | null | undefined, digits = 0) => (v == null ? '—' : fmt(v, digits))

// Elevation gain/loss derived from the actual altitude samples — used as a
// fallback for older rides whose stored loss predates descent capture (a 0.5 m
// step threshold filters barometric jitter). Consistent pair, so we never mix
// an authoritative gain with a derived loss.
const derivedElev = computed<{ gain: number; loss: number } | null>(() => {
  const a = raw.value?.alt
  if (!a) return null
  let gain = 0, loss = 0
  for (let i = 1; i < a.length; i++) {
    const p = a[i - 1], c = a[i]
    if (p == null || c == null) continue
    const d = c - p
    if (d > 0.5) gain += d
    else if (d < -0.5) loss += -d
  }
  return { gain, loss }
})

// ── Summary strip — authoritative ride aggregates (from the activity record,
//    not re-derived from the downsampled track, so they always match the ride).
type Tile = { label: string; val: string; sub?: string }
const tiles = computed<Tile[]>(() => {
  const a = activity.value
  if (!a) return []
  const t: Tile[] = []
  if (a.distanceKm != null) t.push({ label: 'Distance', val: `${a.distanceKm.toFixed(1)} km` })
  if (a.movingTimeS != null) t.push({ label: 'Moving', val: fmtDuration(a.movingTimeS), sub: a.durationS != null ? `of ${fmtDuration(a.durationS)}` : undefined })
  // Prefer stored gain+loss; if loss is missing (older rides), fall back to a
  // consistent derived pair rather than showing ↓ 0 next to a real climb.
  let gain = a.elevationGainM, loss = a.elevationLossM
  if ((loss == null || loss === 0) && derivedElev.value) {
    gain = derivedElev.value.gain
    loss = derivedElev.value.loss
  }
  if (gain != null || loss != null)
    t.push({ label: 'Elevation', val: `↑ ${Math.round(gain ?? 0)} m`, sub: `↓ ${Math.round(loss ?? 0)} m` })
  if (a.calories != null) t.push({ label: 'Calories', val: `${a.calories.toLocaleString()} kcal` })
  if (a.avgPowerW != null && a.movingTimeS != null)
    t.push({ label: 'Work', val: `${Math.round(a.avgPowerW * a.movingTimeS / 1000).toLocaleString()} kJ` })
  if (a.avgHr != null) t.push({ label: 'Heart rate', val: `${a.avgHr} avg`, sub: a.maxHr != null ? `${a.maxHr} max bpm` : undefined })
  if (a.avgPowerW != null) t.push({ label: 'Power', val: `${a.avgPowerW} avg`, sub: a.maxPowerW != null ? `${a.maxPowerW} max W` : undefined })
  if (a.avgSpeedKmh != null) t.push({ label: 'Speed', val: `${a.avgSpeedKmh.toFixed(1)} avg`, sub: a.maxSpeedKmh != null ? `${a.maxSpeedKmh.toFixed(1)} max km/h` : undefined })
  if (a.avgCadence != null) t.push({ label: 'Cadence', val: `${a.avgCadence} avg`, sub: a.maxCadence != null ? `${a.maxCadence} max rpm` : undefined })
  return t
})

const SENSOR_LABEL: Record<string, string> = {
  hr: 'Heart rate', cadence: 'Cadence', power: 'Power', speed: 'Speed',
  altitude: 'Altitude', temperature: 'Temperature', barometer: 'Barometer',
  radar: 'Radar', gps: 'GPS',
}

// ── Per-channel chart series (Charts view) ──────────────────────────────────
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
    // One loop, no spread — Math.min(...present) blows the call stack on
    // full-resolution tracks with >~65k samples (each sample is an argument).
    let min = Infinity, max = -Infinity, sum = 0
    for (const v of present) {
      if (v < min) min = v
      if (v > max) max = v
      sum += v
    }
    const avg = sum / present.length
    out.push({ ch, data, min, avg, max })
  }
  return out
})

function chartOption(s: Series) {
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
      ...(s.ch.key === 'alt' ? { areaStyle: { color: s.ch.color, opacity: 0.1 } } : {}),
    }],
  }
}

// ── Virtual table (Table view) ──────────────────────────────────────────────
// Columns are chosen from the channels actually present; rows are just indices
// into the columnar arrays, so we never materialise per-row objects.
type Col = { key: string; label: string; w: number; cell: (r: RawTrack, i: number) => string }
const ALL_COLS: Col[] = [
  { key: 'i', label: '#', w: 60, cell: (_r, i) => String(i + 1) },
  { key: 'alt', label: 'Alt m', w: 66, cell: (r, i) => cell(r.alt?.[i], 0) },
  { key: 'hr', label: 'HR', w: 54, cell: (r, i) => cell(r.hr?.[i], 0) },
  { key: 'speed', label: 'km/h', w: 64, cell: (r, i) => cell(r.speed?.[i] != null ? r.speed[i]! * 3.6 : null, 1) },
  { key: 'cadence', label: 'Cad', w: 54, cell: (r, i) => cell(r.cadence?.[i], 0) },
  { key: 'power', label: 'W', w: 54, cell: (r, i) => cell(r.power?.[i], 0) },
  { key: 'lat', label: 'Lat', w: 92, cell: (r, i) => cell(r.lat?.[i], 5) },
  { key: 'lon', label: 'Lon', w: 92, cell: (r, i) => cell(r.lon?.[i], 5) },
]
const tableColumns = computed<Col[]>(() => {
  const r = raw.value
  if (!r) return []
  const channelKeys = new Set(series.value.map(s => s.ch.key))
  return ALL_COLS.filter(c =>
    c.key === 'i'
    || channelKeys.has(c.key)
    || ((c.key === 'lat' || c.key === 'lon') && !!r.lat && !!r.lon),
  )
})
const gridStyle = computed(() => ({ gridTemplateColumns: tableColumns.value.map(c => `${c.w}px`).join(' ') }))

const rowIndices = computed<number[]>(() => {
  const r = raw.value
  if (viewMode.value !== 'table' || !r) return []
  return Array.from({ length: r.count ?? 0 }, (_, i) => i)
})
const ROW_H = 26
const { list: vRows, containerProps, wrapperProps } = useVirtualList(rowIndices, { itemHeight: ROW_H, overscan: 12 })
</script>

<template>
  <div class="h-full flex flex-col overflow-hidden">
    <!-- Header — mirrors ActivityDetailView's bar so the two pages feel like one. -->
    <div class="flex items-center gap-2 px-3 py-1.5 border-b border-border bg-background shrink-0">
      <button class="btn-icon" title="Back to ride" @click="router.push(`/tour/${activityId}`)"><ArrowLeft :size="14" /></button>
      <Gauge :size="13" class="text-primary shrink-0" />
      <span class="text-[12px] font-semibold truncate">Raw data</span>
      <span v-if="activity" class="text-[11px] text-muted-fg truncate">· {{ activity.name }}</span>
      <span class="flex-1" />
      <!-- Charts / Table switch -->
      <div class="inline-flex rounded-md border border-border overflow-hidden text-[10px] font-medium">
        <button
          class="inline-flex items-center gap-1 px-2 py-0.5 transition-colors"
          :class="viewMode === 'charts' ? 'bg-primary/15 text-primary' : 'text-muted-fg hover:bg-muted/40'"
          @click="viewMode = 'charts'"
        >
          <LineChartIcon :size="11" /> Charts
        </button>
        <button
          class="inline-flex items-center gap-1 px-2 py-0.5 border-l border-border transition-colors"
          :class="viewMode === 'table' ? 'bg-primary/15 text-primary' : 'text-muted-fg hover:bg-muted/40'"
          @click="viewMode = 'table'"
        >
          <TableIcon :size="11" /> Table
        </button>
      </div>
      <span v-if="raw" class="text-[10px] text-muted-fg font-mono tabular-nums">{{ raw?.count?.toLocaleString() }} pts</span>
      <label
        class="flex items-center gap-1 text-[10px] text-muted-fg cursor-pointer select-none"
        title="Reduced = the LTTB ride sidecar (primary source). Full = the complete per-point track."
      >
        <input type="checkbox" v-model="fullRes" class="accent-primary" />
        <span>Full resolution</span>
      </label>
    </div>

    <!-- Summary strip — authoritative ride aggregates + sensor chips. -->
    <div v-if="tiles.length || activity?.sensors?.length" class="shrink-0 border-b border-border bg-muted/10 px-3 py-2">
      <div class="flex flex-wrap items-stretch gap-2">
        <div
          v-for="t in tiles"
          :key="t.label"
          class="rounded-md border border-border bg-card px-2.5 py-1 min-w-[88px]"
        >
          <div class="text-[9px] uppercase tracking-wide text-muted-fg">{{ t.label }}</div>
          <div class="text-[12px] font-semibold leading-tight tabular-nums">{{ t.val }}</div>
          <div v-if="t.sub" class="text-[9px] text-muted-fg tabular-nums">{{ t.sub }}</div>
        </div>
      </div>
      <div v-if="activity?.sensors?.length" class="flex flex-wrap items-center gap-1 mt-2">
        <span class="text-[9px] uppercase tracking-wide text-muted-fg mr-1">Sensors</span>
        <span
          v-for="s in activity.sensors"
          :key="s"
          class="text-[10px] px-1.5 py-0.5 rounded bg-primary/10 text-primary"
        >{{ SENSOR_LABEL[s] ?? s }}</span>
      </div>
    </div>

    <!-- Content -->
    <div class="flex-1 min-h-0">
      <div v-if="isFetching && !raw" class="text-sm text-muted-fg opacity-60 select-none p-6 text-center">Loading raw data…</div>
      <div v-else-if="isError" class="text-sm text-red-400 p-6 text-center">Could not load raw data.</div>
      <div v-else-if="!series.length" class="text-sm text-muted-fg opacity-60 select-none p-6 text-center">
        This ride carries no charted sensor channels.
      </div>

      <!-- Charts -->
      <div v-else-if="viewMode === 'charts'" class="h-full overflow-y-auto p-3">
        <div class="grid gap-3" style="grid-template-columns: repeat(auto-fit, minmax(320px, 1fr))">
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

      <!-- Table (virtualised) -->
      <div v-else-if="raw" class="h-full flex flex-col p-3">
        <div class="flex-1 min-h-0 flex flex-col border border-border rounded-lg overflow-hidden">
          <!-- Sticky header row -->
          <div
            class="grid bg-muted/30 border-b border-border text-[10px] font-semibold text-muted-fg uppercase tracking-wide shrink-0"
            :style="gridStyle"
          >
            <div v-for="c in tableColumns" :key="c.key" class="px-2 py-1.5 text-right">{{ c.label }}</div>
          </div>
          <!-- Virtual body -->
          <div v-bind="containerProps" class="flex-1 min-h-0">
            <div v-bind="wrapperProps">
              <div
                v-for="item in vRows"
                :key="item.index"
                class="grid items-center text-[11px] font-mono tabular-nums border-b border-border/30 hover:bg-muted/20"
                :style="{ ...gridStyle, height: `${ROW_H}px` }"
              >
                <div
                  v-for="c in tableColumns"
                  :key="c.key"
                  class="px-2 text-right truncate"
                  :class="c.key === 'i' ? 'text-muted-fg' : 'text-foreground'"
                >
                  {{ c.cell(raw, item.data) }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
