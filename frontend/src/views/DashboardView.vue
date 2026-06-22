<script setup lang="ts">
import { fmtDuration } from '@/lib/format'
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent } from 'echarts/components'
import { getActivities, getGear, type ActivitySummary } from '@/api/client'
import { Ruler, Mountain, Timer, Activity as ActivityIcon, TrendingUp, Heart, CloudSun, Bike } from 'lucide-vue-next'

use([CanvasRenderer, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent])

const { data: activities } = useQuery({ queryKey: ['activities'], queryFn: getActivities })
const { data: gearList } = useQuery({ queryKey: ['gear'], queryFn: () => getGear() })

const list = computed<ActivitySummary[]>(() => activities.value ?? [])

// ── KPI rollups ─────────────────────────────────────────────────────────────
const thisYear = new Date().getFullYear()

function sumKm(items: ActivitySummary[]): number {
  return items.reduce((s, a) => s + (a.distanceKm ?? 0), 0)
}
function sumElev(items: ActivitySummary[]): number {
  return items.reduce((s, a) => s + (a.elevationGainM ?? 0), 0)
}
function sumSec(items: ActivitySummary[]): number {
  return items.reduce((s, a) => s + (a.movingTimeS ?? 0), 0)
}

// Assisted (e-bike) rides are motor-helped — "a little fake" for speed/distance
// records — so the headline totals are HUMAN-POWERED, and assisted is reported
// separately as an extra. A ride is assisted if its gear is flagged assisted.
const assistedGearIds = computed(() => new Set((gearList.value ?? []).filter(g => g.assisted).map(g => g.id)))
function isAssisted(a: ActivitySummary): boolean { return !!a.gearId && assistedGearIds.value.has(a.gearId) }
const humanList = computed(() => list.value.filter(a => !isAssisted(a)))
const assistedList = computed(() => list.value.filter(a => isAssisted(a)))
const hasAssisted = computed(() => assistedList.value.length > 0)
const stats = (items: ActivitySummary[]) => ({ count: items.length, km: sumKm(items), elev: sumElev(items), sec: sumSec(items) })

const lifetime = computed(() => stats(humanList.value))
const lifetimeAssisted = computed(() => stats(assistedList.value))

const inThisYear = (a: ActivitySummary) => !!a.startTime && new Date(a.startTime).getFullYear() === thisYear
const yearItems = computed(() => humanList.value.filter(inThisYear))
const yearStats = computed(() => stats(yearItems.value))
const yearAssisted = computed(() => stats(assistedList.value.filter(inThisYear)))

const avgHr = computed(() => {
  const arr = list.value.map(a => a.avgHr).filter((n): n is number => n != null)
  return arr.length ? Math.round(arr.reduce((a, b) => a + b, 0) / arr.length) : null
})

const conditionsCount = computed(() => {
  const m = new Map<string, number>()
  for (const a of list.value) if (a.weatherCondition) m.set(a.weatherCondition, (m.get(a.weatherCondition) ?? 0) + 1)
  return m
})

// ── Per-year chart ──────────────────────────────────────────────────────────
const perYearChart = computed(() => {
  const m = new Map<string, number>()
  for (const a of list.value) {
    if (!a.startTime) continue
    const y = String(new Date(a.startTime).getFullYear())
    m.set(y, (m.get(y) ?? 0) + (a.distanceKm ?? 0))
  }
  const years = Array.from(m.keys()).sort()
  const values = years.map(y => Math.round(m.get(y) ?? 0))
  return {
    grid: { left: 32, right: 12, top: 32, bottom: 24 },
    xAxis: { type: 'category', data: years, axisTick: { show: false } },
    yAxis: { type: 'value', name: 'km', axisLine: { show: false } },
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => `${v} km` },
    series: [{ name: 'Distance', type: 'bar', data: values, itemStyle: { color: '#3b82f6', borderRadius: [4, 4, 0, 0] } }],
  }
})

// ── Per-sport chart ─────────────────────────────────────────────────────────
const perSportChart = computed(() => {
  const m = new Map<string, number>()
  for (const a of list.value) {
    const k = a.activityType ?? 'other'
    m.set(k, (m.get(k) ?? 0) + (a.distanceKm ?? 0))
  }
  const data = Array.from(m.entries()).map(([name, value]) => ({ name, value: Math.round(value) }))
  return {
    tooltip: { trigger: 'item', valueFormatter: (v: number) => `${v} km` },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      label: { fontSize: 11, formatter: '{b}: {c} km' },
      data,
    }],
  }
})

// ── Per-month bar for this year ─────────────────────────────────────────────
const perMonthChart = computed(() => {
  const monthNames = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
  const values = new Array(12).fill(0)
  for (const a of yearItems.value) {
    if (!a.startTime) continue
    const m = new Date(a.startTime).getMonth()
    values[m] += a.distanceKm ?? 0
  }
  return {
    grid: { left: 32, right: 12, top: 32, bottom: 24 },
    xAxis: { type: 'category', data: monthNames, axisTick: { show: false } },
    yAxis: { type: 'value', name: 'km' },
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => `${Math.round(v)} km` },
    series: [{ type: 'bar', data: values.map(v => Math.round(v)), itemStyle: { color: '#10b981', borderRadius: [4, 4, 0, 0] } }],
  }
})

// ── Per-gear rollup (bike comparison: MTB vs race bike, …) ───────────────────
type GearRow = {
  id: string; name: string; type: string | null
  count: number; km: number; elev: number; sec: number; last: string | null
}
const gearStats = computed<GearRow[]>(() => {
  const groups = new Map<string, ActivitySummary[]>()
  for (const a of list.value) {
    const key = a.gearId ?? '__none__'
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(a)
  }
  const rowFor = (id: string, name: string, type: string | null, items: ActivitySummary[]): GearRow => ({
    id, name, type,
    count: items.length, km: sumKm(items), elev: sumElev(items), sec: sumSec(items),
    last: items.reduce<string | null>((mx, a) => (a.startTime && (!mx || a.startTime > mx) ? a.startTime : mx), null),
  })
  const rows: GearRow[] = (gearList.value ?? []).map(g => rowFor(g.id!, g.name, g.type ?? null, groups.get(g.id!) ?? []))
  const none = groups.get('__none__') ?? []
  if (none.length) rows.push(rowFor('__none__', 'No gear assigned', null, none))
  // Gear with rides first (by distance), then unused gear.
  return rows.sort((a, b) => b.km - a.km || b.count - a.count)
})
const hasGear = computed(() => (gearList.value?.length ?? 0) > 0)

const perGearChart = computed(() => {
  const rows = gearStats.value.filter(r => r.km > 0)
  return {
    tooltip: { trigger: 'item', valueFormatter: (v: number) => `${v} km` },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      label: { fontSize: 11, formatter: '{b}: {c} km' },
      data: rows.map(r => ({ name: r.name, value: Math.round(r.km) })),
    }],
  }
})

function fmtDate(iso: string | null) {
  return iso ? new Date(iso).toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' }) : '—'
}
function fmtKm(v: number) { return v.toFixed(0) + ' km' }
function fmtElev(v: number) { return Math.round(v).toLocaleString() + ' m' }
</script>

<template>
  <div class="h-full overflow-y-auto p-4 max-w-6xl mx-auto">
    <div class="flex items-baseline justify-between mb-4">
      <h2 class="text-xl font-semibold">Dashboard</h2>
      <span class="text-xs text-muted-fg">{{ list.length }} activities tracked</span>
    </div>

    <!-- KPI row : this year + lifetime side by side -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
      <div class="p-3 rounded border border-border bg-muted/10">
        <div class="text-[10px] text-muted-fg uppercase tracking-wide flex items-center gap-1"><Ruler :size="11" />Distance · {{ thisYear }}</div>
        <div class="text-xl font-bold mt-0.5">{{ fmtKm(yearStats.km) }}</div>
        <div class="text-[10px] text-muted-fg mt-0.5">lifetime {{ fmtKm(lifetime.km) }}</div>
        <div v-if="hasAssisted" class="text-[10px] text-amber-500 mt-0.5" title="Motor-assisted e-bike rides, kept separate">+ e-bike {{ fmtKm(yearAssisted.km) }} · life {{ fmtKm(lifetimeAssisted.km) }}</div>
      </div>
      <div class="p-3 rounded border border-border bg-muted/10">
        <div class="text-[10px] text-muted-fg uppercase tracking-wide flex items-center gap-1"><Mountain :size="11" />Elevation · {{ thisYear }}</div>
        <div class="text-xl font-bold mt-0.5">{{ fmtElev(yearStats.elev) }}</div>
        <div class="text-[10px] text-muted-fg mt-0.5">lifetime {{ fmtElev(lifetime.elev) }}</div>
        <div v-if="hasAssisted" class="text-[10px] text-amber-500 mt-0.5" title="Motor-assisted e-bike rides, kept separate">+ e-bike {{ fmtElev(yearAssisted.elev) }} · life {{ fmtElev(lifetimeAssisted.elev) }}</div>
      </div>
      <div class="p-3 rounded border border-border bg-muted/10">
        <div class="text-[10px] text-muted-fg uppercase tracking-wide flex items-center gap-1"><Timer :size="11" />Moving · {{ thisYear }}</div>
        <div class="text-xl font-bold mt-0.5">{{ fmtDuration(yearStats.sec) }}</div>
        <div class="text-[10px] text-muted-fg mt-0.5">lifetime {{ fmtDuration(lifetime.sec) }}</div>
        <div v-if="hasAssisted" class="text-[10px] text-amber-500 mt-0.5" title="Motor-assisted e-bike rides, kept separate">+ e-bike {{ fmtDuration(yearAssisted.sec) }} · life {{ fmtDuration(lifetimeAssisted.sec) }}</div>
      </div>
      <div class="p-3 rounded border border-border bg-muted/10">
        <div class="text-[10px] text-muted-fg uppercase tracking-wide flex items-center gap-1"><ActivityIcon :size="11" />Tours · {{ thisYear }}</div>
        <div class="text-xl font-bold mt-0.5">{{ yearStats.count }}</div>
        <div class="text-[10px] text-muted-fg mt-0.5">lifetime {{ lifetime.count }}</div>
        <div v-if="hasAssisted" class="text-[10px] text-amber-500 mt-0.5" title="Motor-assisted e-bike rides, kept separate">+ e-bike {{ yearAssisted.count }} · life {{ lifetimeAssisted.count }}</div>
      </div>
    </div>

    <!-- Secondary KPIs -->
    <div class="grid grid-cols-2 md:grid-cols-3 gap-3 mb-6">
      <div class="p-3 rounded border border-border bg-muted/10">
        <div class="text-[10px] text-muted-fg uppercase tracking-wide flex items-center gap-1"><Heart :size="11" /> Avg HR (overall)</div>
        <div class="text-base font-semibold mt-0.5">{{ avgHr ?? '—' }}{{ avgHr != null ? ' bpm' : '' }}</div>
      </div>
      <div class="p-3 rounded border border-border bg-muted/10">
        <div class="text-[10px] text-muted-fg uppercase tracking-wide flex items-center gap-1"><TrendingUp :size="11" /> Avg km / tour</div>
        <div class="text-base font-semibold mt-0.5">{{ lifetime.count ? (lifetime.km / lifetime.count).toFixed(1) + ' km' : '—' }}</div>
      </div>
      <div class="p-3 rounded border border-border bg-muted/10">
        <div class="text-[10px] text-muted-fg uppercase tracking-wide flex items-center gap-1"><CloudSun :size="11" /> Most common weather</div>
        <div class="text-base font-semibold mt-0.5">
          {{ Array.from(conditionsCount.entries()).sort((a, b) => b[1] - a[1])[0]?.[0] ?? '—' }}
        </div>
      </div>
    </div>

    <!-- By gear — compare bikes (MTB vs race bike, …). Hidden until gear exists. -->
    <div v-if="hasGear" class="mb-6">
      <div class="flex items-center gap-1.5 mb-2 text-xs font-semibold text-muted-fg uppercase tracking-wide">
        <Bike :size="13" /> By gear
      </div>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
        <!-- Gear cards -->
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 content-start">
          <div v-for="g in gearStats" :key="g.id"
            class="p-3 rounded border border-border bg-muted/10">
            <div class="flex items-center justify-between gap-2">
              <div class="font-medium text-sm truncate">{{ g.name }}</div>
              <span v-if="g.type" class="text-[9px] uppercase tracking-wide px-1.5 py-0.5 rounded bg-primary/10 text-primary border border-primary/30 shrink-0">{{ g.type }}</span>
            </div>
            <div class="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-[11px]">
              <div class="flex items-center gap-1 text-muted-fg"><Ruler :size="10" /> {{ fmtKm(g.km) }}</div>
              <div class="flex items-center gap-1 text-muted-fg"><Mountain :size="10" /> {{ fmtElev(g.elev) }}</div>
              <div class="flex items-center gap-1 text-muted-fg"><Timer :size="10" /> {{ fmtDuration(g.sec) }}</div>
              <div class="flex items-center gap-1 text-muted-fg"><ActivityIcon :size="10" /> {{ g.count }} {{ g.count === 1 ? 'ride' : 'rides' }}</div>
              <div class="col-span-2 text-muted-fg/80">
                avg {{ g.count ? (g.km / g.count).toFixed(1) + ' km' : '—' }} · last {{ fmtDate(g.last) }}
              </div>
            </div>
          </div>
        </div>
        <!-- Distance-share donut -->
        <div class="rounded border border-border bg-muted/5">
          <div class="px-3 py-2 text-xs font-semibold border-b border-border">Distance per gear</div>
          <div class="relative w-full h-64 overflow-hidden">
            <VChart class="absolute inset-0" :option="perGearChart" autoresize />
          </div>
        </div>
      </div>
    </div>

    <!-- Charts.
         Each chart sits in a fixed-height relatively-positioned box with
         overflow hidden. VChart is absolutely positioned inside, so ECharts
         autoresize can't push the parent grid cell (which would otherwise
         feed back into the ResizeObserver and grow forever — bug seen on
         the per-sport donut). -->
    <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
      <div class="rounded border border-border bg-muted/5">
        <div class="px-3 py-2 text-xs font-semibold border-b border-border">Distance per year</div>
        <div class="relative w-full h-64 overflow-hidden">
          <VChart class="absolute inset-0" :option="perYearChart" autoresize />
        </div>
      </div>
      <div class="rounded border border-border bg-muted/5">
        <div class="px-3 py-2 text-xs font-semibold border-b border-border">Distance per sport</div>
        <div class="relative w-full h-64 overflow-hidden">
          <VChart class="absolute inset-0" :option="perSportChart" autoresize />
        </div>
      </div>
      <div class="rounded border border-border bg-muted/5 md:col-span-2">
        <div class="px-3 py-2 text-xs font-semibold border-b border-border">{{ thisYear }} · Distance per month</div>
        <div class="relative w-full h-64 overflow-hidden">
          <VChart class="absolute inset-0" :option="perMonthChart" autoresize />
        </div>
      </div>
    </div>
  </div>
</template>
