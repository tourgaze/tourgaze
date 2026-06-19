<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { getActivities } from '@/api/client'
import { fmtClock } from '@/lib/format'
import { ArrowLeft, Swords } from 'lucide-vue-next'

/**
 * Ride comparison detail — a side-by-side STATS sheet: the baseline ride (A,
 * blue) vs the compare ride (B, orange). Each metric shows both values as
 * paired bars plus the delta (with a better/worse tint where direction is
 * meaningful — faster time, higher speed). No map/replay here; that lives in
 * the in-viewer ghost race.
 */
const props = defineProps<{ a: string; b: string }>()
const router = useRouter()

const COLOR_A = '#3b82f6', COLOR_B = '#f97316'

const { data: activities, isLoading } = useQuery({ queryKey: ['activities'], queryFn: getActivities })
const rideA = computed(() => activities.value?.find(x => x.id === props.a) ?? null)
const rideB = computed(() => activities.value?.find(x => x.id === props.b) ?? null)

type Better = 'higher' | 'lower' | 'neutral'
type Metric = { key: string; label: string; a: number | null; b: number | null; fmt: (v: number) => string; better: Better }

const km = (v: number) => `${v.toFixed(2)} km`
const metres = (v: number) => `${Math.round(v)} m`
const kmh = (v: number) => `${v.toFixed(1)} km/h`
const bpm = (v: number) => `${Math.round(v)} bpm`

const metrics = computed<Metric[]>(() => {
  const A = rideA.value, B = rideB.value
  if (!A || !B) return []
  return ([
    { key: 'distance', label: 'Distance',       a: A.distanceKm,    b: B.distanceKm,    fmt: km,       better: 'neutral' },
    { key: 'moving',   label: 'Moving time',    a: A.movingTimeS,   b: B.movingTimeS,   fmt: fmtClock, better: 'lower' },
    { key: 'elapsed',  label: 'Elapsed time',   a: A.durationS,     b: B.durationS,     fmt: fmtClock, better: 'lower' },
    { key: 'elev',     label: 'Elevation gain', a: A.elevationGainM, b: B.elevationGainM, fmt: metres,  better: 'neutral' },
    { key: 'avgspd',   label: 'Avg speed',      a: A.avgSpeedKmh,   b: B.avgSpeedKmh,   fmt: kmh,      better: 'higher' },
    { key: 'maxspd',   label: 'Max speed',      a: A.maxSpeedKmh,   b: B.maxSpeedKmh,   fmt: kmh,      better: 'higher' },
    { key: 'avghr',    label: 'Avg HR',         a: A.avgHr,         b: B.avgHr,         fmt: bpm,      better: 'neutral' },
    { key: 'maxhr',    label: 'Max HR',         a: A.maxHr,         b: B.maxHr,         fmt: bpm,      better: 'neutral' },
  ] as Metric[]).filter(m => m.a != null || m.b != null)
})

function barPct(value: number | null, m: Metric): number {
  const max = Math.max(m.a ?? 0, m.b ?? 0)
  if (!max || value == null) return 0
  return Math.max(2, Math.round((value / max) * 100))
}

function delta(m: Metric): number | null {
  return m.a == null || m.b == null ? null : m.b - m.a
}
function deltaText(m: Metric): string {
  const d = delta(m)
  if (d == null) return ''
  if (Math.abs(d) < 1e-6) return 'same'
  const arrow = d > 0 ? '▲' : '▼'
  const pct = m.a ? ` · ${Math.round(Math.abs(d) / Math.abs(m.a) * 100)}%` : ''
  return `${arrow} ${m.fmt(Math.abs(d))}${pct}`
}
function deltaClass(m: Metric): string {
  const d = delta(m)
  if (d == null || d === 0 || m.better === 'neutral') return 'text-muted-fg'
  const better = (m.better === 'higher' && d > 0) || (m.better === 'lower' && d < 0)
  return better ? 'text-emerald-500' : 'text-red-400'
}

function rideDate(iso?: string | null): string {
  return iso ? new Date(iso).toLocaleDateString(undefined, { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric' }) : '—'
}
</script>

<template>
  <div class="w-full h-full overflow-y-auto bg-background custom-scrollbar">
    <div class="max-w-3xl mx-auto px-4 py-4">
      <!-- Header -->
      <div class="flex items-center gap-2 mb-4">
        <button class="inline-flex items-center gap-1 border border-border rounded-lg px-2.5 py-1.5 text-[12px] font-medium hover:bg-muted/40"
          @click="router.back()"><ArrowLeft :size="14" /> Back</button>
        <h1 class="text-sm font-semibold flex items-center gap-1.5 ml-1"><Swords :size="15" class="text-primary" /> Ride comparison</h1>
      </div>

      <div v-if="isLoading" class="text-center text-sm text-muted-fg py-20">Loading…</div>
      <div v-else-if="!rideA || !rideB" class="text-center text-sm text-muted-fg py-20">
        One of these rides could not be found.
      </div>

      <template v-else>
        <!-- Ride header cards -->
        <div class="grid grid-cols-2 gap-3 mb-5">
          <div class="rounded-xl border border-border bg-muted/10 p-3" :style="{ borderTopColor: COLOR_A, borderTopWidth: '3px' }">
            <div class="text-[10px] font-semibold uppercase tracking-wide" :style="{ color: COLOR_A }">Baseline</div>
            <div class="text-[13px] font-medium text-foreground truncate" :title="rideA.name">{{ rideA.name }}</div>
            <div class="text-[11px] text-muted-fg">{{ rideDate(rideA.startTime) }}</div>
          </div>
          <div class="rounded-xl border border-border bg-muted/10 p-3" :style="{ borderTopColor: COLOR_B, borderTopWidth: '3px' }">
            <div class="text-[10px] font-semibold uppercase tracking-wide" :style="{ color: COLOR_B }">Compare</div>
            <div class="text-[13px] font-medium text-foreground truncate" :title="rideB.name">{{ rideB.name }}</div>
            <div class="text-[11px] text-muted-fg">{{ rideDate(rideB.startTime) }}</div>
          </div>
        </div>

        <!-- Metric rows: paired bars + delta -->
        <div class="space-y-2.5">
          <div v-for="m in metrics" :key="m.key" class="rounded-lg border border-border bg-muted/10 px-3 py-2.5">
            <div class="flex items-center justify-between mb-1.5">
              <span class="text-[12px] font-medium text-muted-fg">{{ m.label }}</span>
              <span class="text-[11px] font-mono font-semibold" :class="deltaClass(m)">{{ deltaText(m) }}</span>
            </div>
            <!-- Baseline bar -->
            <div class="flex items-center gap-2 mb-1">
              <span class="inline-block w-2 h-2 rounded-full shrink-0" :style="{ background: COLOR_A }" />
              <div class="flex-1 h-2.5 rounded-full bg-muted/40 overflow-hidden">
                <div class="h-full rounded-full transition-[width] duration-300" :style="{ width: barPct(m.a, m) + '%', background: COLOR_A }" />
              </div>
              <span class="w-24 text-right font-mono text-[12px] text-foreground tabular-nums">{{ m.a != null ? m.fmt(m.a) : '—' }}</span>
            </div>
            <!-- Compare bar -->
            <div class="flex items-center gap-2">
              <span class="inline-block w-2 h-2 rounded-full shrink-0" :style="{ background: COLOR_B }" />
              <div class="flex-1 h-2.5 rounded-full bg-muted/40 overflow-hidden">
                <div class="h-full rounded-full transition-[width] duration-300" :style="{ width: barPct(m.b, m) + '%', background: COLOR_B }" />
              </div>
              <span class="w-24 text-right font-mono text-[12px] text-foreground tabular-nums">{{ m.b != null ? m.fmt(m.b) : '—' }}</span>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
