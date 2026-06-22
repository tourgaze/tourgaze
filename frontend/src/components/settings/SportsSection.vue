<script setup lang="ts">
/**
 * Manage the sport / activity-type list (masterdata). Seeded Garmin-aligned;
 * each install curates its own — add the sports you do, hide the rest (hiding
 * keeps old rides valid), pick a Lucide icon + colour. The importer maps the
 * device/GPS-inferred sport onto these keys.
 */
import { ref, computed, watch } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { getSports, createSport, updateSport, deleteSport, type Sport } from '@/api/client'
import IconPicker from '@/components/IconPicker.vue'
import DynamicIcon from '@/components/DynamicIcon.vue'
import { Trash2, Plus, Eye, EyeOff } from 'lucide-vue-next'

type Proposal = { key: string; name: string; icon: string }

const qc = useQueryClient()
const { data: sports } = useQuery({ queryKey: ['sports', 'all'], queryFn: () => getSports(false) })
// Garmin/FIT sport hints for the "add" field — a static, editable dataset.
const { data: proposals } = useQuery({
  queryKey: ['sport-proposals'],
  queryFn: async (): Promise<Proposal[]> => (await fetch('/sport_proposals.json')).json(),
})
function refresh() { return qc.invalidateQueries({ queryKey: ['sports'] }) }

const newName = ref('')
const newKey = ref('')   // set when a Garmin hint is chosen → keeps the canonical FIT key
const newIcon = ref('')
const newColor = ref('#10b981')

// Only hint sports not already in the list, by key (and not matched by name).
const hints = computed(() => {
  const have = new Set((sports.value ?? []).map(s => s.key))
  return (proposals.value ?? []).filter(p => !have.has(p.key))
})

// Picking/typing a known Garmin name fills the canonical key + its icon.
watch(newName, (n) => {
  const match = (proposals.value ?? []).find(p => p.name.toLowerCase() === n.trim().toLowerCase())
  if (match) { newKey.value = match.key; if (!newIcon.value) newIcon.value = match.icon }
  else { newKey.value = '' }
})

const createMut = useMutation({
  mutationFn: () => createSport({
    key: newKey.value || undefined, name: newName.value.trim(),
    icon: newIcon.value || undefined, color: newColor.value, enabled: true,
  } as Sport),
  onSuccess: () => { newName.value = ''; newKey.value = ''; newIcon.value = ''; refresh() },
  onError: (e: any) => push.error(String(e?.message ?? '').includes('409') ? 'A sport with that name already exists' : 'Could not add sport'),
})
const saveMut = useMutation({
  mutationFn: (s: Sport) => updateSport(s.id!, s),
  onSuccess: () => refresh(),
  onError: () => push.error('Could not save'),
})
const deleteMut = useMutation({
  mutationFn: (id: string) => deleteSport(id),
  onSuccess: () => refresh(),
  onError: () => push.error('Could not delete'),
})

function toggleEnabled(s: Sport) { saveMut.mutate({ ...s, enabled: !s.enabled }) }
</script>

<template>
  <div class="w-full space-y-4">
    <p class="text-[11px] text-muted-fg">
      Seeded from the Garmin standard so it matches what your device reports.
      Add the sports you do, hide the rest (hiding keeps old rides valid). The
      icon shows on tours; the colour is for charts/grouping.
    </p>

    <!-- List -->
    <div class="border border-border rounded divide-y divide-border">
      <div v-for="s in sports ?? []" :key="s.id"
        class="flex items-center gap-2 px-3 py-2" :class="s.enabled ? '' : 'opacity-50'">
        <span class="inline-flex items-center justify-center w-6 h-6 rounded-full shrink-0 text-white" :style="{ background: s.color || '#64748b' }">
          <DynamicIcon :name="s.icon" :size="13" />
        </span>
        <input :value="s.name" @change="saveMut.mutate({ ...s, name: ($event.target as HTMLInputElement).value })"
          class="flex-1 min-w-0 bg-transparent border border-transparent hover:border-border focus:border-primary rounded px-1.5 py-1 text-sm focus:outline-none" />
        <code class="text-[10px] text-muted-fg shrink-0">{{ s.key }}</code>
        <div class="w-36 shrink-0"><IconPicker :model-value="s.icon ?? ''" @update:model-value="saveMut.mutate({ ...s, icon: $event || undefined })" /></div>
        <input type="color" :value="s.color || '#64748b'" @change="saveMut.mutate({ ...s, color: ($event.target as HTMLInputElement).value })"
          class="w-7 h-7 rounded border border-border bg-transparent cursor-pointer shrink-0" title="Colour" />
        <button class="btn-icon shrink-0" :title="s.enabled ? 'Hide' : 'Show'" @click="toggleEnabled(s)">
          <component :is="s.enabled ? Eye : EyeOff" :size="14" />
        </button>
        <button class="btn-icon btn-icon-danger shrink-0" title="Delete" @click="deleteMut.mutate(s.id!)"><Trash2 :size="14" /></button>
      </div>
    </div>

    <!-- Add -->
    <form @submit.prevent="newName.trim() && createMut.mutate()"
      class="flex items-center gap-2 p-3 border border-border bg-background rounded">
      <span class="inline-flex items-center justify-center w-6 h-6 rounded-full shrink-0 text-white" :style="{ background: newColor }">
        <DynamicIcon :name="newIcon" :size="13" />
      </span>
      <input v-model="newName" required list="garmin-sport-hints" placeholder="New sport — start typing for Garmin hints…"
        class="flex-1 min-w-0 px-2 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary" />
      <datalist id="garmin-sport-hints">
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
