<script setup lang="ts">
/**
 * Edit a ride's EVENTS (typed: rain, drink break, … pinned on the replay map)
 * and its free-form ATTRIBUTES (arbitrary key/value JSON). Reused by the tour
 * detail "Attributes" tab and the edit panel. Events resolve their display
 * name/icon/colour from the EventType masterdata (Settings → Event types).
 */
import { ref, computed, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import {
  getActivityEvents, setActivityEvents, getActivityAttributes, setActivityAttribute,
  getEventTypes, type RideEvent, type EventType,
} from '@/api/client'
import DynamicIcon from '@/components/DynamicIcon.vue'
import { Trash2, Plus, MapPin } from 'lucide-vue-next'

const props = defineProps<{ activityId: string }>()
const qc = useQueryClient()

const { data: serverEvents } = useQuery({
  queryKey: computed(() => ['events', props.activityId]),
  queryFn: () => getActivityEvents(props.activityId),
})
const { data: attrs } = useQuery({
  queryKey: computed(() => ['attributes', props.activityId]),
  queryFn: () => getActivityAttributes(props.activityId),
})
const { data: eventTypes } = useQuery({ queryKey: ['event-types', 'enabled'], queryFn: () => getEventTypes(true) })

const typeByKey = computed(() => {
  const m = new Map<string, EventType>()
  for (const t of eventTypes.value ?? []) if (t.key) m.set(t.key, t)
  return m
})
function typeMeta(key: string): { name: string; icon?: string; color?: string } {
  const t = typeByKey.value.get(key)
  return { name: t?.name ?? key, icon: t?.icon, color: t?.color }
}

// ── Events (local editable copy; persist on each change) ─────────────────────
const events = ref<RideEvent[]>([])
watch(serverEvents, (e) => { events.value = (e ?? []).map(x => ({ ...x })) }, { immediate: true })

async function persistEvents() {
  try {
    await setActivityEvents(props.activityId, events.value)
    await qc.invalidateQueries({ queryKey: ['events', props.activityId] })
    await qc.invalidateQueries({ queryKey: ['activities'] })
  } catch { push.error('Could not save events') }
}
function addEvent() {
  const first = (eventTypes.value ?? [])[0]
  // No location → added here as a non-spatial annotation; place it precisely by
  // adding from the ride map instead. lat/lon/time omitted (undefined).
  events.value.push({ type: first?.key ?? 'WEATHER_RAIN', label: first?.name ?? '' })
  persistEvents()
}
function removeEvent(i: number) { events.value.splice(i, 1); persistEvents() }

// Type options for a row — include the row's own type even if it's been deleted
// from the masterdata, so a custom/legacy kind isn't silently dropped.
function typeOptions(current: string): EventType[] {
  const list = eventTypes.value ?? []
  if (current && !list.some(t => t.key === current)) {
    return [{ id: current, key: current, name: current, icon: '', color: '', ordinal: -1, enabled: true, builtin: false } as EventType, ...list]
  }
  return list
}

// ── Free-form attributes (everything except the events list) ─────────────────
const attrRows = computed(() => Object.entries(attrs.value ?? {}).filter(([k]) => k !== 'events'))
function isEditable(v: unknown): boolean { return v === null || ['string', 'number', 'boolean'].includes(typeof v) }
function asText(v: unknown): string { return typeof v === 'string' ? v : JSON.stringify(v) }

const newKey = ref('')
const newValue = ref('')
async function setAttr(key: string, value: unknown) {
  try {
    await setActivityAttribute(props.activityId, key, value)
    await qc.invalidateQueries({ queryKey: ['attributes', props.activityId] })
    await qc.invalidateQueries({ queryKey: ['activities'] })
  } catch { push.error('Could not save attribute') }
}
async function addAttr() {
  const k = newKey.value.trim()
  if (!k || k === 'events') return
  await setAttr(k, newValue.value)
  newKey.value = ''; newValue.value = ''
}
</script>

<template>
  <div class="space-y-4 text-[12px]">
    <!-- Events -->
    <section class="space-y-1.5">
      <div class="flex items-center justify-between">
        <h3 class="text-[11px] font-semibold uppercase tracking-wide text-muted-fg">Events</h3>
        <button class="inline-flex items-center gap-1 px-2 py-1 text-[11px] rounded border border-border hover:bg-muted/40" @click="addEvent">
          <Plus :size="12" /> Add event
        </button>
      </div>
      <p v-if="!events.length" class="text-[11px] text-muted-fg">No events. Rain showers are detected on import; add your own (drink break, puncture, …).</p>
      <div v-for="(ev, i) in events" :key="i" class="flex items-center gap-1.5">
        <span class="inline-flex items-center justify-center w-6 h-6 rounded-full shrink-0 text-white"
          :style="{ background: typeMeta(ev.type ?? '').color || '#64748b' }">
          <DynamicIcon :name="typeMeta(ev.type ?? '').icon" :size="12" />
        </span>
        <select :value="ev.type ?? ''" @change="ev.type = ($event.target as HTMLSelectElement).value; persistEvents()"
          class="shrink-0 w-32 px-1.5 py-1 rounded border border-border bg-background text-foreground focus:outline-none focus:border-primary">
          <option v-for="t in typeOptions(ev.type ?? '')" :key="t.key" :value="t.key">{{ t.name }}</option>
        </select>
        <input :value="ev.label ?? ''" placeholder="Label…" @change="ev.label = ($event.target as HTMLInputElement).value; persistEvents()"
          class="flex-1 min-w-0 px-1.5 py-1 rounded border border-border bg-background text-foreground focus:outline-none focus:border-primary" />
        <span v-if="ev.lat != null && ev.lon != null" class="shrink-0 text-[10px] text-muted-fg inline-flex items-center gap-0.5" :title="`${ev.lat?.toFixed(5)}, ${ev.lon?.toFixed(5)}`"><MapPin :size="10" /></span>
        <button class="btn-icon btn-icon-danger shrink-0" title="Delete event" @click="removeEvent(i)"><Trash2 :size="13" /></button>
      </div>
    </section>

    <!-- Free-form attributes -->
    <section class="space-y-1.5">
      <h3 class="text-[11px] font-semibold uppercase tracking-wide text-muted-fg">Attributes</h3>
      <div v-for="[k, v] in attrRows" :key="k" class="flex items-center gap-1.5">
        <code class="shrink-0 w-28 truncate text-[11px] text-muted-fg" :title="k">{{ k }}</code>
        <input v-if="isEditable(v)" :value="asText(v)" @change="setAttr(k, ($event.target as HTMLInputElement).value)"
          class="flex-1 min-w-0 px-1.5 py-1 rounded border border-border bg-background text-foreground focus:outline-none focus:border-primary" />
        <code v-else class="flex-1 min-w-0 truncate text-[10px] text-muted-fg" :title="asText(v)">{{ asText(v) }}</code>
        <button class="btn-icon btn-icon-danger shrink-0" title="Remove" @click="setAttr(k, null)"><Trash2 :size="13" /></button>
      </div>
      <form class="flex items-center gap-1.5 pt-1" @submit.prevent="addAttr">
        <input v-model="newKey" placeholder="key" class="w-28 px-1.5 py-1 rounded border border-border bg-background text-foreground focus:outline-none focus:border-primary" />
        <input v-model="newValue" placeholder="value" class="flex-1 min-w-0 px-1.5 py-1 rounded border border-border bg-background text-foreground focus:outline-none focus:border-primary" />
        <button type="submit" :disabled="!newKey.trim()" class="inline-flex items-center gap-1 px-2 py-1 text-[11px] rounded border border-border hover:bg-muted/40 disabled:opacity-50"><Plus :size="12" /> Add</button>
      </form>
    </section>
  </div>
</template>
