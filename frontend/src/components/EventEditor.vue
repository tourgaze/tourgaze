<script setup lang="ts">
/**
 * Presentational ride-event editor (type picker from the EventType masterdata +
 * optional label, with Save and Delete/Discard). Shared by the ride map
 * (ActivityMap) and the import panel (AddTourPanel). It only edits the bound
 * draft — the parent owns persistence (write to the ride / stage for import).
 */
import type { EventType, RideEvent } from '@/api/client'

/** The draft being edited (a ride event). `_idx` is a staging marker used by the
 *  ride map (>= 0 = existing event, else a new draft); absent during import. */
const model = defineModel<(RideEvent & { _idx?: number }) | null>({ required: true })
defineProps<{ eventTypes: EventType[] }>()
defineEmits<{ save: []; delete: [] }>()
</script>

<template>
  <div v-if="model"
    class="bg-background/95 backdrop-blur-sm border border-border rounded-xl shadow-2xl p-3 space-y-2.5">
    <div class="flex items-center justify-between">
      <span class="text-[11px] font-semibold uppercase tracking-wide text-muted-fg">Ride event</span>
      <button class="btn-icon" title="Close" @click="model = null">✕</button>
    </div>
    <select v-model="model.type"
      class="w-full px-2 py-1.5 text-sm rounded-md border border-border bg-background text-foreground focus:outline-none focus:border-primary">
      <option v-for="t in eventTypes" :key="t.key" :value="t.key">{{ t.name }}</option>
    </select>
    <input v-model="model.label" type="text" placeholder="Label (optional)…"
      class="w-full px-2 py-1.5 text-sm rounded-md border border-border bg-background text-foreground focus:outline-none focus:border-primary" />
    <div class="flex items-center justify-between pt-0.5">
      <button class="px-2 py-1 text-[11px] font-medium rounded border border-red-300 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
        @click="$emit('delete')">{{ (model._idx ?? -1) >= 0 ? 'Delete' : 'Discard' }}</button>
      <button class="px-3 py-1 text-[11px] font-semibold rounded bg-primary text-white hover:opacity-90 transition-opacity"
        @click="$emit('save')">Save</button>
    </div>
  </div>
</template>
