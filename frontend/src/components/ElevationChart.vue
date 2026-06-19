<script setup lang="ts">
import { computed, watch, ref, onBeforeUnmount } from 'vue'
import type { TrackPoint } from '@/api/client'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import {
  GridComponent, TooltipComponent, DataZoomComponent,
  LegendComponent, AxisPointerComponent, MarkAreaComponent,
} from 'echarts/components'
import VChart from 'vue-echarts'
import { X } from 'lucide-vue-next'
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
     DataZoomComponent, LegendComponent, AxisPointerComponent, MarkAreaComponent])

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
// The tooltip popup should only appear when the pointer is actually over the
// graph — NOT when it's pushed in from map-hover / replay (that was making the
// popup pop up while the mouse was elsewhere).
const mouseInGraph = ref(false)

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

  const hasHr = pts.some(p => p.hr != null && p.hr > 0)
  const hasSpeed = pts.some(p => p.speedMs != null && p.speedMs > 0)

  const extras: string[] = []
  if (hasHr) extras.push('hr')
  if (hasSpeed) extras.push('speed')

  const grids: any[] = []
  const xAxes: any[] = []
  const yAxes: any[] = []
  const series: any[] = []

  const xData = pts.map(p => p.distKm.toFixed(2))
  const leftPad = 48
  const rightPad = 15

  // 1. Dynamic Grid Layout (Elite Faceting)
  if (extras.length === 0) {
    grids.push({ top: 15, left: leftPad, right: rightPad, bottom: 25 })
  } else if (extras.length === 1) {
    grids.push({ top: 15, left: leftPad, right: rightPad, bottom: '38%' }) // Elevation
    grids.push({ top: '68%', left: leftPad, right: rightPad, bottom: 25 }) // Extra 1
  } else {
    grids.push({ top: 15, left: leftPad, right: rightPad, bottom: '48%' }) // Elevation
    grids.push({ top: '57%', left: leftPad, right: rightPad, bottom: '26%' }) // Extra 1
    grids.push({ top: '79%', left: leftPad, right: rightPad, bottom: 25 }) // Extra 2
  }

  // 2. Elevation Series & Axes (Grid 0)
  xAxes.push({
    gridIndex: 0, type: 'category', data: xData,
    axisLabel: { show: extras.length === 0, fontSize: 10, color: '#9ca3af', formatter: '{value} km' },
    axisLine: { show: false }, axisTick: { show: false }, boundaryGap: false
  })
  
  yAxes.push({
    gridIndex: 0, type: 'value',
    min: (v: any) => Math.floor(v.min - Math.max(10, (v.max - v.min) * 0.1)),
    max: (v: any) => Math.ceil(v.max + Math.max(10, (v.max - v.min) * 0.1)),
    axisLabel: { fontSize: 10, color: '#9ca3af', formatter: '{value}m', margin: 8 },
    splitLine: { lineStyle: { color: '#f3f4f6', type: 'dashed' } },
  })

  const sel = selection.value
  // Bands: saved sections (teal, labelled with their name) + the live drag
  // measurement (indigo, no label).
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
  series.push({
    name: 'Elevation', type: 'line', xAxisIndex: 0, yAxisIndex: 0,
    data: pts.map(p => p.altM ?? null),
    smooth: 0.3, symbol: 'none',
    lineStyle: { color: '#22c55e', width: 1.5 },
    areaStyle: {
      color: {
        type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [{ offset: 0, color: 'rgba(34,197,94,0.40)' }, { offset: 1, color: 'rgba(34,197,94,0.02)' }]
      }
    },
    ...(areaData.length ? {
      markArea: {
        silent: true,
        label: { show: true, position: 'insideTop', fontSize: 9, color: '#0d9488', formatter: (p: any) => p.name ?? '' },
        data: areaData,
      },
    } : {}),
  })

  // 3. Extra Series & Axes (Grids 1 & 2)
  extras.forEach((ext, idx) => {
    const gridIdx = idx + 1
    const isHr = ext === 'hr'

    xAxes.push({
      gridIndex: gridIdx, type: 'category', data: xData,
      axisLabel: { show: idx === extras.length - 1, fontSize: 10, color: '#9ca3af', formatter: '{value} km' },
      axisLine: { show: false }, axisTick: { show: false }, boundaryGap: false
    })

    yAxes.push({
      gridIndex: gridIdx, type: 'value',
      min: (v: any) => Math.max(0, Math.floor(v.min - (isHr ? 5 : 2))),
      max: (v: any) => Math.ceil(v.max + (isHr ? 5 : 2)),
      axisLabel: { fontSize: 10, color: isHr ? '#ef4444' : '#f59e0b', margin: 8, formatter: '{value}' },
      splitNumber: 2,
      splitLine: { lineStyle: { color: '#f3f4f6', type: 'dashed' } },
    })

    if (isHr) {
      series.push({
        name: 'Heart rate', type: 'line', xAxisIndex: gridIdx, yAxisIndex: gridIdx,
        data: pts.map(p => p.hr ?? null),
        smooth: 0.4, symbol: 'none',
        lineStyle: { color: '#ef4444', width: 1.5 },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [{ offset: 0, color: 'rgba(239,68,68,0.25)' }, { offset: 1, color: 'rgba(239,68,68,0.02)' }]
          }
        }
      })
    } else {
      series.push({
        name: 'Speed', type: 'line', xAxisIndex: gridIdx, yAxisIndex: gridIdx,
        data: pts.map(p => p.speedMs != null ? +(p.speedMs * 3.6).toFixed(1) : null),
        smooth: 0.3, symbol: 'none',
        lineStyle: { color: '#f59e0b', width: 1.5 },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [{ offset: 0, color: 'rgba(245,158,11,0.25)' }, { offset: 1, color: 'rgba(245,158,11,0.02)' }]
          }
        }
      })
    }
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
    legend: {
      show: extras.length > 0,
      top: 0, right: 15,
      textStyle: { fontSize: 10, color: '#6b7280' },
      itemHeight: 8,
    },
    tooltip: {
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
        for (const item of params) {
          if (item.data == null) continue
          const dot = `<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${item.color};margin-right:4px"></span>`
          const val = item.data + (item.seriesName === 'Elevation' ? ' m' : (item.seriesName === 'Speed' ? ' km/h' : ' bpm'))
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

// Drive the chart cursor from map hover / replay. Only surface the tooltip
// popup when the mouse is actually in the graph; otherwise we'd pop a tooltip
// over the chart while the user is panning the map or watching the replay.
watch(() => props.activeChartIndex, (idx) => {
  if (!chartRef.value?.chart) return
  if (idx == null || !mouseInGraph.value) {
    chartRef.value.chart.dispatchAction({ type: 'hideTip' })
  } else {
    chartRef.value.chart.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: idx })
  }
})

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
    @mouseenter="mouseInGraph = true"
    @mouseleave="mouseInGraph = false; onChartMouseout()">
    <VChart
      ref="chartRef"
      class="w-full h-full"
      :option="option"
      :autoresize="true"
      @mouseout="onChartMouseout"
      @click="onChartClick"
    />

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
