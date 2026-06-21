<script setup lang="ts">
import { fmtDuration } from '@/lib/format'
import { computed } from 'vue'
import type { HrZone } from '@/composables/useHrZones'

const props = defineProps<{
  zones: HrZone[]
  seconds: number[]
  totalSec: number
  /** Show the per-zone Z#·duration line under the bar (default true). Off in the
   *  graph view, where the full breakdown lives in the stats panel. */
  breakdown?: boolean
}>()


const rows = computed(() =>
  props.zones.map((z, i) => ({
    zone: z,
    sec: props.seconds[i] ?? 0,
    pct: props.totalSec > 0 ? (props.seconds[i] ?? 0) / props.totalSec : 0,
  })),
)
</script>

<template>
  <!-- Compact: title + stacked bar + total on one line, then a single wrapping
       Z#·duration line. The full per-zone breakdown lives in the stats panel. -->
  <div v-if="totalSec > 0" class="px-3 py-1.5 border-t border-border bg-muted/10">
    <div class="flex items-center gap-2">
      <span class="text-[10px] font-semibold uppercase tracking-wide text-muted-fg shrink-0">Zones</span>
      <div class="flex h-2.5 flex-1 rounded overflow-hidden bg-muted/30">
        <div
          v-for="r in rows"
          :key="r.zone.index"
          :style="{ width: (r.pct * 100) + '%', backgroundColor: r.zone.color }"
          :title="`${r.zone.name} (${r.zone.lo}–${r.zone.hi} bpm): ${fmtDuration(r.sec)}`"
        />
      </div>
      <span class="text-[9px] text-muted-fg font-mono shrink-0">{{ fmtDuration(totalSec) }}</span>
    </div>
    <div v-if="breakdown !== false" class="flex flex-wrap gap-x-2.5 gap-y-0.5 mt-1 text-[9px] text-muted-fg">
      <span v-for="r in rows" :key="r.zone.index" class="inline-flex items-center gap-1"
        :title="`${r.zone.name} (${r.zone.lo}–${r.zone.hi} bpm)`">
        <span class="inline-block w-1.5 h-1.5 rounded-sm" :style="{ backgroundColor: r.zone.color }" />
        Z{{ r.zone.index }} <span class="font-mono text-foreground">{{ fmtDuration(r.sec) }}</span>
      </span>
    </div>
  </div>
</template>
