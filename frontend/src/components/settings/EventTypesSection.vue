<script setup lang="ts">
/**
 * Manage the ride-event type list (masterdata, mirrors SportsSection). Seeded
 * with the built-in kinds (WEATHER_RAIN = "Rainfall", emitted by the importer)
 * plus handy user kinds. Each install curates its own — add your own (drink
 * break, puncture, …), hide the rest (hiding keeps old events valid), pick a
 * Lucide icon + colour. A ride event stores the `key`; the GUI renders this
 * name/icon/colour.
 */
import { ref, computed, watch } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { getEventTypes, createEventType, updateEventType, deleteEventType, type EventType } from '@/api/client'
import IconPicker from '@/components/IconPicker.vue'
import DynamicIcon from '@/components/DynamicIcon.vue'
import { Trash2, Plus, Eye, EyeOff } from 'lucide-vue-next'

type Proposal = { key: string; name: string; icon: string }

const qc = useQueryClient()
const { data: types } = useQuery({ queryKey: ['event-types', 'all'], queryFn: () => getEventTypes(false) })
const { data: proposals } = useQuery({
  queryKey: ['event-proposals'],
  queryFn: async (): Promise<Proposal[]> => (await fetch('/event_proposals.json')).json(),
})
function refresh() { return qc.invalidateQueries({ queryKey: ['event-types'] }) }

const newName = ref('')
const newKey = ref('')
const newIcon = ref('')
const newColor = ref('#3b82f6')

const hints = computed(() => {
  const have = new Set((types.value ?? []).map(t => t.key))
  return (proposals.value ?? []).filter(p => !have.has(p.key))
})

watch(newName, (n) => {
  const match = (proposals.value ?? []).find(p => p.name.toLowerCase() === n.trim().toLowerCase())
  if (match) { newKey.value = match.key; if (!newIcon.value) newIcon.value = match.icon }
  else { newKey.value = '' }
})

const createMut = useMutation({
  mutationFn: () => createEventType({
    key: newKey.value || undefined, name: newName.value.trim(),
    icon: newIcon.value || undefined, color: newColor.value, enabled: true,
  } as EventType),
  onSuccess: () => { newName.value = ''; newKey.value = ''; newIcon.value = ''; refresh() },
  onError: (e: any) => push.error(String(e?.message ?? '').includes('409') ? 'An event type with that name already exists' : 'Could not add event type'),
})
const saveMut = useMutation({
  mutationFn: (t: EventType) => updateEventType(t.id!, t),
  onSuccess: () => refresh(),
  onError: () => push.error('Could not save'),
})
const deleteMut = useMutation({
  mutationFn: (id: string) => deleteEventType(id),
  onSuccess: () => refresh(),
  onError: () => push.error('Could not delete'),
})

function toggleEnabled(t: EventType) { saveMut.mutate({ ...t, enabled: !t.enabled }) }

// System types whose visibility the app manages — PHOTO is intentionally hidden
// (photos are the media feature, not a manual event), so its hide/show is locked.
const SYSTEM_KEYS = new Set(['PHOTO'])
function isLocked(t: EventType): boolean { return !!t.key && SYSTEM_KEYS.has(t.key) }
</script>

<template>
  <div class="w-full space-y-4">
    <p class="text-[11px] text-muted-fg">
      Things that happened on a ride — pinned on the replay map. The importer adds
      <code>Rainfall</code> automatically when it detects a shower; add your own
      kinds (drink break, puncture, …) here. The name/icon/colour are what the GUI
      shows; hiding keeps old events valid.
    </p>

    <!-- List -->
    <div class="border border-border rounded divide-y divide-border">
      <div v-for="t in types ?? []" :key="t.id"
        class="flex items-center gap-2 px-3 py-2" :class="t.enabled ? '' : 'opacity-50'">
        <span class="inline-flex items-center justify-center w-6 h-6 rounded-full shrink-0 text-white" :style="{ background: t.color || '#64748b' }">
          <DynamicIcon :name="t.icon" :size="13" />
        </span>
        <input :value="t.name" @change="saveMut.mutate({ ...t, name: ($event.target as HTMLInputElement).value })"
          class="flex-1 min-w-0 bg-transparent border border-transparent hover:border-border focus:border-primary rounded px-1.5 py-1 text-sm focus:outline-none" />
        <code class="text-[10px] text-muted-fg shrink-0">{{ t.key }}</code>
        <div class="w-36 shrink-0"><IconPicker :model-value="t.icon ?? ''" @update:model-value="saveMut.mutate({ ...t, icon: $event || undefined })" /></div>
        <input type="color" :value="t.color || '#64748b'" @change="saveMut.mutate({ ...t, color: ($event.target as HTMLInputElement).value })"
          class="w-7 h-7 rounded border border-border bg-transparent cursor-pointer shrink-0" title="Colour" />
        <button class="btn-icon shrink-0" :disabled="isLocked(t)"
          :title="isLocked(t) ? 'System type — managed by the app' : (t.enabled ? 'Hide' : 'Show')"
          :class="isLocked(t) ? 'opacity-30 cursor-not-allowed' : ''"
          @click="!isLocked(t) && toggleEnabled(t)">
          <component :is="t.enabled ? Eye : EyeOff" :size="14" />
        </button>
        <button v-if="!t.builtin" class="btn-icon btn-icon-danger shrink-0" title="Delete"
          @click="deleteMut.mutate(t.id!)"><Trash2 :size="14" /></button>
      </div>
    </div>

    <!-- Add -->
    <form @submit.prevent="newName.trim() && createMut.mutate()"
      class="flex items-center gap-2 p-3 border border-border bg-background rounded">
      <span class="inline-flex items-center justify-center w-6 h-6 rounded-full shrink-0 text-white" :style="{ background: newColor }">
        <DynamicIcon :name="newIcon" :size="13" />
      </span>
      <input v-model="newName" required list="event-type-hints" placeholder="New event type — e.g. Drink break…"
        class="flex-1 min-w-0 px-2 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary" />
      <datalist id="event-type-hints">
        <option v-for="h in hints" :key="h.key" :value="h.name" />
      </datalist>
      <div class="w-36 shrink-0"><IconPicker v-model="newIcon" /></div>
      <input type="color" v-model="newColor" class="w-7 h-7 rounded border border-border bg-transparent cursor-pointer shrink-0" title="Colour" />
      <button type="submit" :disabled="createMut.isPending.value || !newName.trim()"
        class="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium rounded bg-primary text-primary-fg hover:bg-primary/90 disabled:opacity-50 shrink-0">
        <Plus :size="13" /> Add
      </button>
    </form>
  </div>
</template>
