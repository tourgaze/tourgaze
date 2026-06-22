<script setup lang="ts">
import { computed, watch, ref, nextTick, onBeforeUnmount } from 'vue'
import type { TrackPoint } from '@/api/client'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import {
  GridComponent, TooltipComponent, DataZoomComponent,
  LegendComponent, AxisPointerComponent, MarkAreaComponent, MarkLineComponent,
} from 'echarts/components'
import VChart from 'vue-echarts'
import { X, Layers } from 'lucide-vue-next'
import { activeIndex } from '@/composables/useTrackData'
import { distanceM } from '@/lib/geo'

/** A curated section (named span) to highlight where this ride's track passes it. */
export type ChartSection = {
  name: string
  startLat: number
  startLon: number
  endLat: number
  endLon: number
}

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent,
     DataZoomComponent, LegendComponent, AxisPointerComponent, MarkAreaComponent, MarkLineComponent])

const props = defineProps<{
  // RDP-filtered points (already have distKm and _rawIdx back-reference into
  // the full-resolution track).
  points: (TrackPoint & { distKm: number; _rawIdx: number })[]
  // Map full-track active index → chart index for cursor
  activeChartIndex: number | null
  /**
   * When true, hover-on-chart shows the tooltip but does NOT mutate the
   * shared activeIndex — that would fight the rAF playback tick and cause
   * the marker to flicker between the hovered sample and the actual rider
   * position. Click still jumps + emits 'jump' (the parent stops playback).
   */
  isPlaying?: boolean
  /** Saved sections (named spans) to draw as labelled bands where they match. */
  sections?: ChartSection[]
}>()

const emit = defineEmits<{
  jump: [rawIdx: number]
}>()

const chartRef = ref<any>(null)

// Custom channel toggles — a reliable HTML "legend" we control. (ECharts' own
// legend only blanked the line without re-laying-out, and a hidden item rendered
// invisibly.) Toggling a chip re-facets the chart so the rest stretch to fill.
const CHANNELS = [
  { key: 'elev', name: 'Elevation', color: '#22c55e', has: (p: any) => p.altM != null },
  { key: 'hr', name: 'HR', color: '#ef4444', has: (p: any) => p.hr != null && p.hr > 0 },
  { key: 'power', name: 'Power', color: '#a855f7', has: (p: any) => p.power != null && p.power > 0 },
  { key: 'speed', name: 'Speed', color: '#f59e0b', has: (p: any) => p.speedMs != null && p.speedMs > 0 },
  { key: 'cadence', name: 'Cadence', color: '#06b6d4', has: (p: any) => p.cadence != null && p.cadence > 0 },
] as const
const presentChannels = computed(() => CHANNELS.filter(c => props.points.some(c.has)))
const hidden = ref<Set<string>>(new Set())
// Overlay switch: stack each graph (false) vs overlay them on one grid with
// per-channel colour-coded y-axes (true).
const overlayMode = ref(false)
function toggleChannel(key: string) {
  const next = new Set(hidden.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    // Keep at least one graph visible.
    if (presentChannels.value.filter(c => !next.has(c.key)).length <= 1) return
    next.add(key)
  }
  hidden.value = next
}

// ── Drag-to-measure ─────────────────────────────────────────────────────────
// Press + drag across the chart to select a segment; we report its distance and
// average gradient ("Steilheit"). Indices are into `props.points` (chart order).
const selStart = ref<number | null>(null)
const selEnd = ref<number | null>(null)

const selection = computed(() => {
  if (selStart.value == null || selEnd.value == null) return null
  const a = Math.min(selStart.value, selEnd.value)
  const b = Math.max(selStart.value, selEnd.value)
  if (b - a < 1) return null
  return { a, b }
})

const selReadout = computed(() => {
  const sel = selection.value
  if (!sel) return null
  const pa = props.points[sel.a], pb = props.points[sel.b]
  if (!pa || !pb) return null
  const distKm = Math.abs(pb.distKm - pa.distKm)
  const dAlt = (pa.altM != null && pb.altM != null) ? (pb.altM - pa.altM) : null
  const gradePct = (dAlt != null && distKm > 0) ? (dAlt / (distKm * 1000)) * 100 : null
  return { distKm, dAlt, gradePct }
})

function clearSelection() { selStart.value = null; selEnd.value = null }

// Grid split-line style. Light mode keeps the (perfect) light-grey dashed look;
// dark mode uses a SLIM SOLID GRAY so the lines don't disturb the dark chart.
function gridLineStyle(): Record<string, unknown> {
  return document.documentElement.classList.contains('dark')
    ? { color: 'rgba(156,163,175,0.22)', width: 0.5, type: 'solid' }
    : { color: '#f3f4f6', type: 'dashed' }
}
// Reset the measurement when the underlying track changes.
watch(() => props.points, clearSelection)

// Curated sections matched onto THIS ride: nearest track point to each end, kept
// only when both ends are within ~250 m of the track (so a section drawn on one
// road doesn't smear onto an unrelated ride).
const MATCH_M = 250
function nearestIdx(lat: number, lon: number): { idx: number; d: number } {
  let best = -1, bestD = Infinity
  const pts = props.points
  for (let i = 0; i < pts.length; i++) {
    const d = distanceM(lat, lon, pts[i].lat, pts[i].lon)
    if (d < bestD) { bestD = d; best = i }
  }
  return { idx: best, d: bestD }
}
const matchedSections = computed(() => {
  const out: { a: number; b: number; label: string }[] = []
  if (!props.points.length) return out
  for (const s of props.sections ?? []) {
    const start = nearestIdx(s.startLat, s.startLon)
    const end = nearestIdx(s.endLat, s.endLon)
    if (start.d > MATCH_M || end.d > MATCH_M) continue
    const a = Math.min(start.idx, end.idx), b = Math.max(start.idx, end.idx)
    if (b - a >= 1) out.push({ a, b, label: s.name || 'Section' })
  }
  return out
})

const option = computed(() => {
  const pts = props.points
  if (!pts.length) return {}

  const hasElev = pts.some(p => p.altM != null)
  const hasHr = pts.some(p => p.hr != null && p.hr > 0)
  const hasSpeed = pts.some(p => p.speedMs != null && p.speedMs > 0)
  const hasPower = pts.some(p => p.power != null && p.power > 0)
  const hasCadence = pts.some(p => p.cadence != null && p.cadence > 0)

  // Facet ONLY the channels that actually have data, then stretch them to fill
  // the height — so an HR-only ride uses the whole chart instead of leaving an
  // empty elevation band up top (MyTourbook-style).
  type Key = 'elev' | 'hr' | 'power' | 'speed' | 'cadence'
  const present: Key[] = []
  if (hasElev) present.push('elev')
  if (hasHr) present.push('hr')
  if (hasPower) present.push('power')
  if (hasSpeed) present.push('speed')
  if (hasCadence) present.push('cadence')
  if (!present.length) return {}
  // Stack only the channels enabled via the toggle chips; the rest are excluded
  // so the visible graphs stretch to fill. (An all-data ride still shows all by
  // default — `hidden` is empty until the user toggles.)
  const visible = present.filter(k => !hidden.value.has(k))
  const panels = visible.length ? visible : present

  const cfg = (key: Key) => {
    if (key === 'elev') return {
      name: 'Elevation', color: '#22c55e', smooth: 0.3,
      data: pts.map(p => p.altM ?? null),
      area: ['rgba(34,197,94,0.40)', 'rgba(34,197,94,0.02)'],
      yAxis: {
        type: 'value',
        min: (v: any) => Math.floor(v.min - Math.max(10, (v.max - v.min) * 0.1)),
        max: (v: any) => Math.ceil(v.max + Math.max(10, (v.max - v.min) * 0.1)),
        axisLabel: { fontSize: 10, color: '#9ca3af', formatter: '{value}m', margin: 8 },
        splitLine: { lineStyle: gridLineStyle() },
      },
    }
    if (key === 'hr') return {
      name: 'Heart rate', color: '#ef4444', smooth: 0.4,
      data: pts.map(p => p.hr ?? null),
      area: ['rgba(239,68,68,0.25)', 'rgba(239,68,68,0.02)'],
      yAxis: {
        type: 'value', splitNumber: 2,
        min: (v: any) => Math.max(0, Math.floor(v.min - 5)),
        max: (v: any) => Math.ceil(v.max + 5),
        axisLabel: { fontSize: 10, color: '#ef4444', margin: 8, formatter: '{value}' },
        splitLine: { lineStyle: gridLineStyle() },
      },
    }
    if (key === 'power') return {
      name: 'Power', color: '#a855f7', smooth: 0.2,
      data: pts.map(p => p.power ?? null),
      area: ['rgba(168,85,247,0.25)', 'rgba(168,85,247,0.02)'],
      yAxis: {
        type: 'value', splitNumber: 2,
        min: () => 0,
        max: (v: any) => Math.ceil(v.max + 10),
        axisLabel: { fontSize: 10, color: '#a855f7', margin: 8, formatter: '{value}' },
        splitLine: { lineStyle: gridLineStyle() },
      },
    }
    if (key === 'cadence') return {
      name: 'Cadence', color: '#06b6d4', smooth: 0.3,
      data: pts.map(p => p.cadence ?? null),
      area: ['rgba(6,182,212,0.22)', 'rgba(6,182,212,0.02)'],
      yAxis: {
        type: 'value', splitNumber: 2,
        min: () => 0,
        max: (v: any) => Math.ceil(v.max + 5),
        axisLabel: { fontSize: 10, color: '#06b6d4', margin: 8, formatter: '{value}' },
        splitLine: { lineStyle: gridLineStyle() },
      },
    }
    return {
      name: 'Speed', color: '#f59e0b', smooth: 0.3,
      data: pts.map(p => p.speedMs != null ? +(p.speedMs * 3.6).toFixed(1) : null),
      area: ['rgba(245,158,11,0.25)', 'rgba(245,158,11,0.02)'],
      yAxis: {
        type: 'value', splitNumber: 2,
        min: (v: any) => Math.max(0, Math.floor(v.min - 2)),
        max: (v: any) => Math.ceil(v.max + 2),
        axisLabel: { fontSize: 10, color: '#f59e0b', margin: 8, formatter: '{value}' },
        splitLine: { lineStyle: gridLineStyle() },
      },
    }
  }

  const grids: any[] = []
  const xAxes: any[] = []
  const yAxes: any[] = []
  const series: any[] = []

  const xData = pts.map(p => p.distKm.toFixed(2))
  const leftPad = 48
  const rightPad = 15

  const sel = selection.value
  // Bands: saved sections (teal, labelled) + the live drag measurement (indigo).
  const areaData: any[] = []
  for (const m of matchedSections.value) {
    areaData.push([
      { xAxis: xData[m.a], name: m.label, itemStyle: { color: 'rgba(13,148,136,0.14)', borderColor: 'rgba(13,148,136,0.5)', borderWidth: 1 } },
      { xAxis: xData[m.b] },
    ])
  }
  if (sel) {
    areaData.push([
      { xAxis: xData[sel.a], itemStyle: { color: 'rgba(99,102,241,0.15)', borderColor: 'rgba(99,102,241,0.5)', borderWidth: 1 } },
      { xAxis: xData[sel.b] },
    ])
  }

  // Weighted stacked grids filling the height. Elevation is the hero (the profile
  // people actually read), so it gets ~2× a secondary row — that keeps the chart
  // legible as channels pile up (HR + speed + power + cadence) instead of every
  // panel shrinking equally. While measuring, open a top lane so the distance /
  // gradient readout floats ABOVE the plot instead of over the peak.
  const N = panels.length

  // Theme-aware chip behind axis labels so overlaid lines don't render over them
  // (especially in dark mode). Drawn in front of the series via a high z.
  const dark = document.documentElement.classList.contains('dark')
  const labelBg = dark ? 'rgba(17,24,39,0.78)' : 'rgba(255,255,255,0.8)'
  const labelChip = { backgroundColor: labelBg, padding: [1, 3] as [number, number], borderRadius: 2 }

  if (overlayMode.value) {
    // OVERLAY: one grid, every channel on top of each other, each with its own
    // colour-coded y-axis (alternating left/right, offset for extras). Lines only
    // — area fills would obscure one another.
    const leftN = Math.ceil(N / 2)
    const rightN = N - leftN
    grids.push({ top: (sel ? 18 : 8) + '%', bottom: 26, left: 10 + leftN * 44, right: 10 + rightN * 44 })
    xAxes.push({
      gridIndex: 0, type: 'category', data: xData,
      axisLabel: { show: true, fontSize: 10, color: dark ? '#9ca3af' : '#374151', formatter: '{value} km', ...labelChip },
      axisLine: { show: false }, axisTick: { show: false }, boundaryGap: false,
    })
    let l = 0, r = 0
    panels.forEach((key, i) => {
      const c = cfg(key)
      const side = i % 2 === 0 ? 'left' : 'right'
      const offset = (side === 'left' ? l++ : r++) * 44
      yAxes.push({
        gridIndex: 0, position: side, offset,
        z: 10,
        ...c.yAxis,
        axisLine: { show: true, lineStyle: { color: c.color } },
        // Colour-coded label on a theme chip so the overlaid lines can't wash it out.
        axisLabel: { ...(c.yAxis.axisLabel as any), fontSize: 9, color: c.color, margin: 4, ...labelChip },
        splitLine: { show: i === 0, lineStyle: gridLineStyle() },
      })
      series.push({
        name: c.name, type: 'line', xAxisIndex: 0, yAxisIndex: i,
        data: c.data, smooth: c.smooth, symbol: 'none',
        lineStyle: { color: c.color, width: 1.5 },
        ...(i === 0 && areaData.length ? {
          markArea: {
            silent: true,
            label: { show: true, position: 'insideTop', fontSize: 9, color: '#0d9488', formatter: (p2: any) => p2.name ?? '' },
            data: areaData,
          },
        } : {}),
      })
    })
  } else {
    const topMargin = sel ? 18 : 6
    const botMargin = 12
    const gap = N > 3 ? 6 : (N > 1 ? 9 : 0)
    const weights = panels.map(k => k === 'elev' ? 1.9 : 1)
    const wsum = weights.reduce((a, b) => a + b, 0)
    const usable = 100 - topMargin - botMargin - gap * (N - 1)
    let acc = topMargin

    panels.forEach((key, i) => {
      const c = cfg(key)
      const h = usable * weights[i] / wsum
      grids.push({ left: leftPad, right: rightPad, top: +acc.toFixed(2) + '%', height: +h.toFixed(2) + '%' })
      acc += h + gap
      xAxes.push({
        gridIndex: i, type: 'category', data: xData,
        axisLabel: { show: i === N - 1, fontSize: 10, color: '#9ca3af', formatter: '{value} km' },
        axisLine: { show: false }, axisTick: { show: false }, boundaryGap: false,
      })
      yAxes.push({ gridIndex: i, ...c.yAxis })
      series.push({
        name: c.name, type: 'line', xAxisIndex: i, yAxisIndex: i,
        data: c.data, smooth: c.smooth, symbol: 'none',
        lineStyle: { color: c.color, width: 1.5 },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [{ offset: 0, color: c.area[0] }, { offset: 1, color: c.area[1] }],
          },
        },
        // Measurement / section bands live on the top panel only.
        ...(i === 0 && areaData.length ? {
          markArea: {
            silent: true,
            label: { show: true, position: 'insideTop', fontSize: 9, color: '#0d9488', formatter: (p2: any) => p2.name ?? '' },
            data: areaData,
          },
        } : {}),
      })
    })
  }
  // Replay-cursor placeholder: declared empty (per series, so it spans every
  // sub-grid) so the markLine component exists from init — renderPlayCursor only
  // updates its `data`. ECharts won't render a markLine first introduced by a
  // later merge, hence declaring it here.
  const cursorLineStyle = { color: '#6366f1', width: 1.5, type: 'solid' as const, opacity: 0.9 }
  series.forEach(s => {
    s.markLine = { silent: true, symbol: 'none', animation: false, label: { show: false }, lineStyle: cursorLineStyle, data: [] }
  })

  return {
    backgroundColor: 'transparent',
    grid: grids,
    xAxis: xAxes,
    yAxis: yAxes,
    series,
    // moveOnMouseMove off so a left-drag measures a segment instead of panning;
    // wheel still zooms. (See drag-to-measure handlers below.)
    dataZoom: [{ type: 'inside', xAxisIndex: xAxes.map((_, i) => i), moveOnMouseMove: false, zoomOnMouseWheel: true }],
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    tooltip: {
      // Only while NOT replaying. During playback the position is shown by the
      // markLine cursor below; the value popup (elevation/speed/HR) would
      // reposition every frame and flicker, so we suppress it entirely.
      show: !props.isPlaying,
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: '#6366f1', width: 1, type: 'dashed' } },
      formatter(params: any[]) {
        if (!params || !params.length) return ''
        const p = params[0]
        // During playback, don't mutate the shared activeIndex on hover —
        // it would race the rAF tick. The tooltip still renders so the
        // user can read km / alt / HR for any point on the chart, they
        // just have to click to actually jump the map there.
        if (p?.dataIndex != null && !props.isPlaying) {
          activeIndex.value = pts[p.dataIndex]?._rawIdx ?? p.dataIndex
        }
        const km = pts[p.dataIndex]?.distKm.toFixed(2) ?? '?'
        let s = `<b>${km} km</b>`
        const units: Record<string, string> = {
          Elevation: ' m', Speed: ' km/h', 'Heart rate': ' bpm', Power: ' W', Cadence: ' rpm',
        }
        for (const item of params) {
          if (item.data == null) continue
          const dot = `<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${item.color};margin-right:4px"></span>`
          const val = item.data + (units[item.seriesName] ?? '')
          s += `<br/>${dot}${item.seriesName}: <b>${val}</b>`
        }
        return s
      },
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#e5e7eb',
      textStyle: { color: '#374151', fontSize: 11 },
      extraCssText: 'box-shadow:0 2px 8px rgba(0,0,0,.08);border-radius:8px;padding:6px 10px;',
    }
  }
})

// How many series the option renders (elevation + optional HR/speed). Cached so
// the per-frame cursor update below doesn't have to read the whole chart option.
const seriesCount = computed(() => {
  // Matches `option`: rendered series = enabled (non-hidden) present channels.
  const visible = presentChannels.value.filter(c => !hidden.value.has(c.key))
  return visible.length || presentChannels.value.length
})

// Map the (full-resolution) shared activeIndex to the NEAREST chart point. The
// chart is RDP-filtered, so the exact activeChartIndex prop is null on almost
// every replay frame (activeIndex advances by 1, chart points are sparse) —
// nearest gives a smooth cursor that tracks playback.
const cursorIdx = computed<number | null>(() => {
  const pts = props.points
  const t = activeIndex.value
  if (t == null || !pts.length) return null
  if (t <= pts[0]._rawIdx) return 0
  const last = pts.length - 1
  if (t >= pts[last]._rawIdx) return last
  let lo = 0, hi = last
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    const r = pts[mid]._rawIdx
    if (r === t) return mid
    if (r < t) lo = mid + 1
    else hi = mid - 1
  }
  // lo = first point past t, hi = last before it → whichever is closer.
  return (pts[lo]._rawIdx - t) < (t - pts[hi]._rawIdx) ? lo : hi
})

// Replay cursor — a vertical line at the current position, drawn as a markLine
// (part of the chart option) instead of a dispatched tooltip. Two reasons:
//  • no value popup → it can't flicker as the position advances each frame;
//  • it lives in the chart's option state, so an autoresize (user dragging the
//    graph pane taller/shorter) re-renders it — a showTip cursor would vanish.
// Only while playing; when paused/scrubbing the normal hover tooltip takes over.
function renderPlayCursor() {
  const chart = chartRef.value?.chart
  const count = seriesCount.value
  if (!chart || !count) return
  const idx = cursorIdx.value
  const pts = props.points
  const show = props.isPlaying && idx != null && idx >= 0 && idx < pts.length
  const data = show ? [{ xAxis: pts[idx].distKm.toFixed(2) }] : []
  // Update only the cursor markLine's DATA — its style is declared up front in
  // `option` (a markLine component merged in after init won't render). One entry
  // per series so the line spans every stacked sub-grid.
  chart.setOption({ series: Array.from({ length: count }, () => ({ markLine: { data } })) })
}
// Defer to nextTick so we re-assert the markLine AFTER vue-echarts applies a
// rebuilt `option` (which would otherwise drop our merged-in cursor).
const scheduleCursor = () => nextTick(renderPlayCursor)
watch(cursorIdx, scheduleCursor)
watch(() => props.isPlaying, scheduleCursor)
watch(option, scheduleCursor)

// Click on chart → jump to that position and signal parent to stop playback
function onChartClick(params: any) {
  if (params?.dataIndex != null) {
    const rawIdx = props.points[params.dataIndex]?._rawIdx ?? params.dataIndex
    activeIndex.value = rawIdx
    emit('jump', rawIdx)
  }
}

// Mouseout clears the shared cursor — but only when we're NOT replaying;
// during playback the rAF tick owns activeIndex and we mustn't touch it.
function onChartMouseout() {
  if (!props.isPlaying) activeIndex.value = null
}

// ── Drag-to-measure: zrender (canvas) mouse events → chart-x indices ──────────
let dragging = false
let downX = 0
function pixelToIdx(offsetX: number): number | null {
  const chart = chartRef.value?.chart
  if (!chart || !props.points.length) return null
  const idx = chart.convertFromPixel({ xAxisIndex: 0 }, offsetX)
  if (idx == null || Number.isNaN(idx)) return null
  return Math.max(0, Math.min(props.points.length - 1, Math.round(idx)))
}
function onDown(e: any) {
  const idx = pixelToIdx(e.offsetX)
  if (idx == null) return
  dragging = true
  downX = e.offsetX
  selStart.value = idx
  selEnd.value = idx
}
function onMove(e: any) {
  if (!dragging) return
  const idx = pixelToIdx(e.offsetX)
  if (idx != null && idx !== selEnd.value) selEnd.value = idx
}
function onUp(e: any) {
  if (!dragging) return
  dragging = false
  // A tiny movement is a click, not a drag → clear (the @click handler jumps).
  if (Math.abs(e.offsetX - downX) < 4) clearSelection()
}

function attachZr() {
  const chart = chartRef.value?.chart
  if (!chart) return
  const zr = chart.getZr()
  zr.off('mousedown', onDown); zr.off('mousemove', onMove); zr.off('mouseup', onUp)
  zr.on('mousedown', onDown); zr.on('mousemove', onMove); zr.on('mouseup', onUp)
}
watch(() => chartRef.value?.chart, (c) => { if (c) attachZr() })
onBeforeUnmount(() => {
  const zr = chartRef.value?.chart?.getZr?.()
  if (zr) { zr.off('mousedown', onDown); zr.off('mousemove', onMove); zr.off('mouseup', onUp) }
})

const fmtSigned = (n: number, digits = 0) => (n >= 0 ? '+' : '') + n.toFixed(digits)
</script>

<template>
  <div class="relative w-full h-full"
    @mouseleave="onChartMouseout()">
    <VChart
      ref="chartRef"
      class="w-full h-full"
      :option="option"
      :autoresize="true"
      @mouseout="onChartMouseout"
      @click="onChartClick"
    />

    <!-- Top-right controls: overlay switch + channel toggles (custom,
         always-visible legend — click a chip to hide/show that graph). -->
    <div v-if="presentChannels.length > 1"
      class="absolute top-0.5 right-2 z-10 flex items-center gap-2 pointer-events-auto select-none
             bg-background/90 backdrop-blur-sm rounded-md border border-border px-1.5 py-0.5 shadow-sm">
      <button type="button"
        class="text-[10px] inline-flex items-center gap-1 px-1.5 py-0.5 rounded border"
        :class="overlayMode ? 'border-primary text-primary bg-primary/10' : 'border-border text-foreground hover:bg-muted/40'"
        :title="overlayMode ? 'Switch to stacked graphs' : 'Overlay all graphs on one axis'"
        @click="overlayMode = !overlayMode">
        <Layers :size="11" /> Overlay
      </button>
      <span class="flex items-center gap-1.5">
        <button v-for="c in presentChannels" :key="c.key" type="button"
          class="text-[10px] inline-flex items-center gap-1 px-1 rounded hover:bg-muted/40"
          :title="hidden.has(c.key) ? 'Show ' + c.name : 'Hide ' + c.name"
          @click="toggleChannel(c.key)">
          <span class="inline-block w-2 h-2 rounded-sm"
            :style="{ backgroundColor: c.color, opacity: hidden.has(c.key) ? 0.3 : 1 }" />
          <span :class="hidden.has(c.key) ? 'line-through text-muted-fg/60' : 'text-foreground font-medium'">{{ c.name }}</span>
        </button>
      </span>
    </div>

    <!-- Drag-to-measure readout: distance + average gradient (Steilheit) -->
    <div
      v-if="selReadout"
      class="absolute top-1 left-1/2 -translate-x-1/2 z-10 flex items-center gap-2 rounded-lg
             border border-indigo-400/70 bg-background px-2.5 py-1 text-[11px]
             shadow-xl ring-1 ring-black/5 backdrop-blur-sm pointer-events-none"
    >
      <span><b class="tabular-nums">{{ selReadout.distKm.toFixed(2) }}</b> km</span>
      <span class="text-muted-fg">·</span>
      <span>Steilheit
        <b class="tabular-nums" :class="(selReadout.gradePct ?? 0) >= 0 ? 'text-red-500' : 'text-emerald-500'">
          {{ selReadout.gradePct != null ? fmtSigned(selReadout.gradePct, 1) + '%' : '—' }}
        </b>
      </span>
      <span v-if="selReadout.dAlt != null" class="text-muted-fg">·</span>
      <span v-if="selReadout.dAlt != null" class="text-muted-fg tabular-nums">Δ {{ fmtSigned(selReadout.dAlt, 0) }} m</span>
      <button
        class="pointer-events-auto ml-0.5 text-muted-fg hover:text-foreground"
        title="Clear measurement" @click="clearSelection"
      ><X :size="12" /></button>
    </div>
  </div>
</template>
