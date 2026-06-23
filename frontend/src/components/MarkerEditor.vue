<script setup lang="ts">
/**
 * Presentational marker editor card (label + category picker + description, with
 * Save and Delete/Discard). Shared by the ride map (ActivityMap) and the import
 * panel (AddTourPanel). It only edits the bound draft — the parent owns the
 * create/update/delete API calls via the emitted events.
 */
import { MARKER_CATEGORIES, markerIconSvg } from '@/markerCategories'
import type { Marker } from '@/api/client'

const model = defineModel<Marker | null>({ required: true })
defineEmits<{ save: []; delete: [] }>()
const categories = MARKER_CATEGORIES
</script>

<template>
  <div v-if="model"
    class="bg-background/95 backdrop-blur-sm border border-border rounded-xl shadow-2xl p-3 space-y-2.5">
    <div class="flex items-center justify-between">
      <span class="text-[11px] font-semibold uppercase tracking-wide text-muted-fg">Marker</span>
      <button class="btn-icon" title="Close" @click="model = null">✕</button>
    </div>
    <input v-model="model.label" type="text" placeholder="Label (e.g. Nice restaurant)"
      class="w-full px-2 py-1.5 text-sm rounded-md border border-border bg-background text-foreground
             focus:outline-none focus:border-primary" />
    <div class="flex flex-wrap gap-1">
      <button v-for="c in categories" :key="c.key" type="button" :title="c.label"
        class="w-7 h-7 rounded-full flex items-center justify-center text-white transition-transform"
        :class="model.category === c.key ? 'ring-2 ring-offset-1 ring-offset-background ring-foreground scale-110' : 'opacity-70 hover:opacity-100'"
        :style="{ background: c.color }"
        @click="model.category = c.key"
        v-html="markerIconSvg(c)"></button>
    </div>
    <textarea v-model="model.description" rows="3" placeholder="Description…"
      class="w-full px-2 py-1.5 text-sm rounded-md border border-border bg-background text-foreground
             resize-none focus:outline-none focus:border-primary"></textarea>
    <div class="flex items-center justify-between pt-0.5">
      <button class="px-2 py-1 text-[11px] font-medium rounded border border-red-300 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
        @click="$emit('delete')">{{ model.id ? 'Delete' : 'Discard' }}</button>
      <button class="px-3 py-1 text-[11px] font-semibold rounded bg-primary text-white hover:opacity-90 transition-opacity"
        @click="$emit('save')">Save</button>
    </div>
  </div>
</template>
