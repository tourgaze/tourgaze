<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { refDebounced, onClickOutside } from '@vueuse/core'
import { searchPlaces, type PlaceProposal } from '@/api/client'

/**
 * Split (location, country) input pair with Nominatim-backed autocomplete.
 *
 * Two-way bound via the shared v-model convention: `v-model:location` and
 * `v-model:country`. The parent owns the strings; this component owns the
 * dropdown UX. Optional `predicted` prop renders the pre-computed proposal
 * (from the PredictionService at import time) as a one-click chip below
 * the inputs — distinct from the live autocomplete results dropdown.
 */
const props = defineProps<{
  location: string
  country: string
  /** Predicted chip — { name, country } as proposed by the import-time geocode. */
  predicted?: { name: string | null; country: string | null } | null
  placeholderLocation?: string
  placeholderCountry?: string
  label?: string
  /** Compact density (smaller font, tighter padding) — for EditTour. */
  compact?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:location', v: string): void
  (e: 'update:country', v: string): void
}>()

const rootEl = ref<HTMLDivElement | null>(null)
const open = ref(false)
const highlight = ref(0)
onClickOutside(rootEl, () => { open.value = false })

// Track what the user types. Debounce 220 ms so each keystroke doesn't fire
// a Nominatim call; their usage policy is 1 req/sec.
const query = computed(() => props.location)
const queryDebounced = refDebounced(query, 220)

const { data: proposals } = useQuery({
  queryKey: ['place-search', queryDebounced],
  queryFn: () => searchPlaces(queryDebounced.value),
  // Only fire when 2+ chars and the dropdown is open.
  enabled: computed(() => open.value && queryDebounced.value.trim().length >= 2),
  staleTime: 7 * 24 * 60 * 60 * 1000,  // backend caches 7 days too
})

const filteredProposals = computed<PlaceProposal[]>(() => proposals.value ?? [])

// Highlight resets to top when results change.
watch(filteredProposals, () => { highlight.value = 0 })

function pick(p: PlaceProposal) {
  emit('update:location', p.name)
  if (p.country) emit('update:country', p.country)
  open.value = false
}

function applyPredicted() {
  if (!props.predicted) return
  if (props.predicted.name) emit('update:location', props.predicted.name)
  if (props.predicted.country) emit('update:country', props.predicted.country)
}

function onKey(e: KeyboardEvent) {
  if (!open.value || !filteredProposals.value.length) {
    if (e.key === 'Escape') open.value = false
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    highlight.value = (highlight.value + 1) % filteredProposals.value.length
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    highlight.value = (highlight.value - 1 + filteredProposals.value.length) % filteredProposals.value.length
  } else if (e.key === 'Enter') {
    e.preventDefault()
    pick(filteredProposals.value[highlight.value])
  } else if (e.key === 'Escape') {
    open.value = false
  }
}

const inputBase = computed(() => props.compact
  ? 'rounded border border-border bg-transparent px-2 py-1 text-xs'
  : 'rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none',
)
</script>

<template>
  <div ref="rootEl" class="block text-sm relative">
    <span v-if="label" :class="compact ? 'text-[10px] text-muted-fg' : 'text-xs font-medium text-muted-fg'">{{ label }}</span>
    <div class="grid grid-cols-[1fr_56px] gap-1 mt-0.5" :class="compact ? '' : 'gap-2'">
      <input
        :value="location"
        @input="emit('update:location', ($event.target as HTMLInputElement).value); open = true"
        @focus="open = true"
        @keydown="onKey"
        type="text"
        autocomplete="off"
        :placeholder="placeholderLocation"
        :class="['block w-full', inputBase]" />
      <input
        :value="country"
        @input="emit('update:country', ($event.target as HTMLInputElement).value.toUpperCase())"
        type="text"
        maxlength="2"
        :placeholder="placeholderCountry"
        :class="['block w-full font-mono uppercase tracking-wider', inputBase]" />
    </div>

    <!-- Live autocomplete dropdown — fires while the user types. -->
    <div v-if="open && filteredProposals.length"
      class="absolute top-full left-0 right-0 mt-1 z-[2000] rounded-md border border-border bg-background shadow-lg overflow-y-auto max-h-72 text-xs">
      <button v-for="(p, i) in filteredProposals" :key="`${p.name}|${p.country}|${p.lat}`"
        type="button"
        class="w-full flex items-start gap-2 px-3 py-1.5 text-left transition-colors"
        :class="i === highlight ? 'bg-primary/10 text-primary' : 'hover:bg-muted/40'"
        @mousedown.prevent
        @click="pick(p)">
        <div class="flex-1 min-w-0">
          <div class="truncate font-medium">
            {{ p.name }}<span v-if="p.country" class="text-muted-fg"> · {{ p.country }}</span>
          </div>
          <div v-if="p.displayLabel && p.displayLabel !== p.name" class="text-[10px] text-muted-fg truncate">
            {{ p.displayLabel }}
          </div>
        </div>
      </button>
    </div>

    <!-- Pre-computed proposal chip from the import-time prediction. -->
    <div v-if="predicted?.name && predicted.name !== location" class="mt-1 flex items-center gap-1">
      <button type="button"
        class="text-[10px] px-1.5 py-0.5 rounded border border-primary/40 text-primary hover:bg-primary/10"
        :title="`Apply predicted location ${predicted.name}${predicted.country ? ' (' + predicted.country + ')' : ''}`"
        @click="applyPredicted">
        {{ predicted.name }}<span v-if="predicted.country" class="opacity-60"> · {{ predicted.country }}</span>
      </button>
      <span class="text-[10px] text-muted-fg">predicted</span>
    </div>
  </div>
</template>
