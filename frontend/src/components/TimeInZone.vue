<script setup lang="ts">
import { fmtDuration } from '@/lib/format'
import { computed } from 'vue'
import type { HrZone } from '@/composables/useHrZones'

const props = defineProps<{
  zones: HrZone[]
  seconds: number[]
  totalSec: number
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
  <div v-if="totalSec > 0" class="px-3 py-2 border-t border-border bg-muted/10">
    <div class="flex items-baseline justify-between mb-1.5">
      <h4 class="text-[11px] font-semibold uppercase tracking-wide text-muted-fg">Time in zone</h4>
      <span class="text-[10px] text-muted-fg font-mono">{{ fmtDuration(totalSec) }} HR data</span>
    </div>

    <!-- Stacked bar -->
    <div class="flex h-2 rounded overflow-hidden bg-muted/30">
      <div
        v-for="r in rows"
        :key="r.zone.index"
        :style="{ width: (r.pct * 100) + '%', backgroundColor: r.zone.color }"
        :title="`${r.zone.name} (${r.zone.lo}–${r.zone.hi} bpm): ${fmtDuration(r.sec)}`"
      />
    </div>

    <!-- Per-zone breakdown -->
    <div class="grid grid-cols-5 gap-1 mt-1.5 text-[10px]">
      <div v-for="r in rows" :key="r.zone.index" class="text-center">
        <div class="flex items-center justify-center gap-1">
          <span class="inline-block w-2 h-2 rounded-sm" :style="{ backgroundColor: r.zone.color }" />
          <span class="font-semibold text-foreground">Z{{ r.zone.index }}</span>
        </div>
        <div class="text-muted-fg leading-tight">{{ r.zone.lo }}–{{ r.zone.hi }}</div>
        <div class="font-mono text-foreground">{{ fmtDuration(r.sec) }}</div>
      </div>
    </div>
  </div>
</template>
