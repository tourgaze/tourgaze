<script setup lang="ts">
import { fmtClock } from '@/lib/format'
import { computed, ref } from 'vue'
import type { EliteStats } from '@/composables/useEliteStats'
import { Gauge, Mountain, HeartPulse, Zap, Timer, TrendingUp, ChevronDown, ChevronRight } from 'lucide-vue-next'

const props = defineProps<{ stats: EliteStats }>()

const decoupleTone = computed(() => {
  const d = props.stats.decouplingPct
  if (d == null) return ''
  return d <= 5 ? 'text-emerald-500' : d <= 8 ? 'text-amber-500' : 'text-red-500'
})

// Collapsible sections — collapse what you don't need so the panel fits without
// scrolling. Gradient distribution lives in a pinned footer (not collapsible).
const collapsed = ref<Set<string>>(new Set())
function toggle(k: string) {
  const n = new Set(collapsed.value)
  if (n.has(k)) n.delete(k); else n.add(k)
  collapsed.value = n
}
const isOpen = (k: string) => !collapsed.value.has(k)
</script>

<template>
  <div v-if="stats.hasData" class="flex flex-col h-full min-h-0">
    <!-- Scrollable sections (collapse any you don't need to avoid scrolling) -->
    <div class="flex-1 min-h-0 overflow-y-auto p-1.5 space-y-1.5 custom-scrollbar">
      <!-- Summary -->
      <section>
        <button type="button" class="elite-h" @click="toggle('summary')">
          <component :is="isOpen('summary') ? ChevronDown : ChevronRight" :size="11" class="opacity-60" />
          <Gauge :size="12" /> Summary
        </button>
        <div v-show="isOpen('summary')" class="elite-grid">
          <div class="elite-card"><span class="elite-val">{{ stats.distanceKm }}</span><span class="elite-unit">km</span><span class="elite-lbl">Distance</span></div>
          <div class="elite-card"><span class="elite-val">{{ fmtClock(stats.movingTimeS) }}</span><span class="elite-lbl">Moving time</span></div>
          <div class="elite-card"><span class="elite-val">{{ stats.avgSpeedKmh ?? '—' }}</span><span class="elite-unit">km/h</span><span class="elite-lbl">Avg speed</span></div>
          <div class="elite-card"><span class="elite-val">{{ stats.maxSpeedKmh ?? '—' }}</span><span class="elite-unit">km/h</span><span class="elite-lbl">Max speed</span></div>
        </div>
      </section>

      <!-- Climbing -->
      <section>
        <button type="button" class="elite-h" @click="toggle('climbing')">
          <component :is="isOpen('climbing') ? ChevronDown : ChevronRight" :size="11" class="opacity-60" />
          <Mountain :size="12" /> Climbing
        </button>
        <div v-show="isOpen('climbing')" class="elite-grid">
          <div class="elite-card"><span class="elite-val">{{ stats.ascentM }}</span><span class="elite-unit">m</span><span class="elite-lbl">Ascent</span></div>
          <div class="elite-card"><span class="elite-val">{{ stats.descentM }}</span><span class="elite-unit">m</span><span class="elite-lbl">Descent</span></div>
          <div class="elite-card"><span class="elite-val">{{ stats.maxGradePct ?? '—' }}</span><span class="elite-unit">%</span><span class="elite-lbl">Max grade</span></div>
          <div class="elite-card"><span class="elite-val">{{ stats.vamMh ?? '—' }}</span><span class="elite-unit">m/h</span><span class="elite-lbl">VAM</span></div>
        </div>
      </section>

      <!-- Heart rate -->
      <section v-if="stats.avgHr != null">
        <button type="button" class="elite-h" @click="toggle('hr')">
          <component :is="isOpen('hr') ? ChevronDown : ChevronRight" :size="11" class="opacity-60" />
          <HeartPulse :size="12" /> Heart rate
        </button>
        <div v-show="isOpen('hr')" class="elite-grid">
          <div class="elite-card"><span class="elite-val">{{ stats.avgHr }}</span><span class="elite-unit">bpm</span><span class="elite-lbl">Avg HR</span></div>
          <div class="elite-card"><span class="elite-val">{{ stats.maxHr ?? '—' }}</span><span class="elite-unit">bpm</span><span class="elite-lbl">Max HR</span></div>
          <div class="elite-card"><span class="elite-val">{{ stats.trimp ?? '—' }}</span><span class="elite-lbl">Training load <span class="opacity-60">(TRIMP)</span></span></div>
          <div class="elite-card">
            <span class="elite-val" :class="decoupleTone">{{ stats.decouplingPct != null ? stats.decouplingPct + '%' : '—' }}</span>
            <span class="elite-lbl">Aerobic decoupling</span>
          </div>
        </div>
      </section>

      <!-- Power (cycling, estimated) -->
      <section v-if="stats.isCycling && stats.estAvgPowerW != null">
        <button type="button" class="elite-h" @click="toggle('power')">
          <component :is="isOpen('power') ? ChevronDown : ChevronRight" :size="11" class="opacity-60" />
          <Zap :size="12" /> Power <span class="elite-est">estimated</span>
        </button>
        <div v-show="isOpen('power')" class="elite-grid">
          <div class="elite-card"><span class="elite-val">{{ stats.estAvgPowerW }}</span><span class="elite-unit">W</span><span class="elite-lbl">Avg power</span></div>
          <div class="elite-card"><span class="elite-val">{{ stats.estNpW ?? '—' }}</span><span class="elite-unit">W</span><span class="elite-lbl">Normalized (NP)</span></div>
          <div class="elite-card"><span class="elite-val">{{ stats.variabilityIndex ?? '—' }}</span><span class="elite-lbl">Variability index</span></div>
          <div class="elite-card"><span class="elite-val">{{ stats.workKj ?? '—' }}</span><span class="elite-unit">kJ</span><span class="elite-lbl">Work · ~{{ stats.calories ?? '—' }} kcal</span></div>
        </div>
      </section>

      <!-- Best efforts -->
      <section v-if="stats.bestEfforts.length">
        <button type="button" class="elite-h" @click="toggle('best')">
          <component :is="isOpen('best') ? ChevronDown : ChevronRight" :size="11" class="opacity-60" />
          <Timer :size="12" /> Best efforts <span class="elite-est">peak avg speed</span>
        </button>
        <div v-show="isOpen('best')" class="flex flex-wrap gap-1.5">
          <div v-for="b in stats.bestEfforts" :key="b.label"
            class="flex-1 min-w-[64px] rounded-md border border-border bg-muted/10 px-2 py-1 text-center">
            <div class="text-sm font-semibold text-foreground">{{ b.speedKmh != null ? b.speedKmh.toFixed(1) : '—' }}<span class="text-[10px] text-muted-fg font-normal"> km/h</span></div>
            <div class="text-[10px] text-muted-fg">{{ b.label }}</div>
          </div>
        </div>
      </section>
    </div>

    <!-- Pinned footer: gradient distribution stays visible while the rest scrolls -->
    <div v-if="stats.gradeBuckets.some(g => g.meters > 0)" class="shrink-0 border-t border-border p-1.5 bg-background">
      <h3 class="elite-h"><TrendingUp :size="12" /> Gradient distribution</h3>
      <div class="flex h-2.5 w-full rounded-full overflow-hidden border border-border">
        <div v-for="g in stats.gradeBuckets" :key="g.label" :style="{ width: g.pct + '%', background: g.color }"
          :title="`${g.label}: ${(g.meters / 1000).toFixed(1)} km (${g.pct.toFixed(0)}%)`" />
      </div>
      <div class="mt-1 flex flex-wrap gap-x-3 gap-y-0.5">
        <span v-for="g in stats.gradeBuckets" :key="g.label" class="inline-flex items-center gap-1 text-[10px] text-muted-fg">
          <span class="w-2 h-2 rounded-sm" :style="{ background: g.color }" />{{ g.label }} · {{ g.pct.toFixed(0) }}%
        </span>
      </div>
    </div>
  </div>

  <div v-else class="flex-1 min-h-0 flex items-center justify-center text-[11px] text-muted-fg">
    No track data for stats.
  </div>
</template>

<style scoped>
.elite-h {
  display: flex; align-items: center; gap: 4px; width: 100%;
  font-size: 9px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em;
  color: hsl(var(--muted-fg)); margin-bottom: 2px;
  background: none; border: none; padding: 1px 0; text-align: left; cursor: pointer;
}
button.elite-h:hover { color: hsl(var(--foreground)); }
.elite-est { text-transform: none; letter-spacing: 0; font-weight: 500; opacity: 0.6; }
/* Compact, dense grid — smaller cards pack more per row so wide panes don't
   leave the top-right empty. */
.elite-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(68px, 1fr)); gap: 4px; }
/* Dashboard tile: kind as a top-right caption (full-width row), then value and
   unit together on one line below. Row-wrap + top alignment keeps it to two
   compact lines so the grid packs densely on a wide monitor. */
.elite-card {
  display: flex; flex-flow: row wrap; align-items: baseline; align-content: center;
  min-height: 34px; row-gap: 0;
  border: 1px solid hsl(var(--border)); border-radius: 6px;
  background-color: hsl(var(--muted) / 0.1); padding: 2px 6px;
}
.elite-lbl {
  order: -1; flex-basis: 100%; text-align: right;
  font-size: 9px; line-height: 1.05; color: hsl(var(--muted-fg));
}
.elite-val { font-size: 13px; font-weight: 700; line-height: 1.1; color: hsl(var(--foreground)); }
.elite-unit { font-size: 9px; color: hsl(var(--muted-fg)); margin-left: 3px; }
</style>
