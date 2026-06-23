<script setup lang="ts">
/**
 * JIRA-style faceted search bar for the Tours list. Presentational — all
 * parsing/matching lives in the {@link useTourSearch} instance passed in via
 * `search`, so the owning view can also use its `matches` predicate.
 *
 * Ported in spirit from matrosdms' GlobalSearch: inline removable filter chips,
 * an autocomplete dropdown (field keys, then values), Enter to commit the
 * facet under the caret, Backspace on an empty input to pop the last chip.
 */
import { ref, computed, watch, nextTick } from 'vue'
import { Search, X, Tag as TagIcon, Bike, Calendar, MapPin, Flag, Gauge } from 'lucide-vue-next'
import { onClickOutside } from '@vueuse/core'
import type { useTourSearch } from '@/composables/useTourSearch'
import { useListNavigation } from '@/composables/useListNavigation'

const props = defineProps<{ search: ReturnType<typeof useTourSearch> }>()
const emit = defineEmits<{ focusResults: [] }>()
const s = props.search

const containerRef = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLInputElement | null>(null)
const open = ref(false)
const activeIndex = ref(-1)

onClickOutside(containerRef, () => { open.value = false })

function onInput() {
  open.value = true
  activeIndex.value = -1
}

function pick(val: string) {
  s.applySuggestion(val)
  activeIndex.value = -1
  nextTick(() => inputRef.value?.focus())
}

// matros-style list navigation over the suggestion dropdown (↑↓ Home End ↵,
// wraps around). Keeps the active item scrolled into view.
const suggestionCount = computed(() => s.suggestions.value.length)
const { handleKey } = useListNavigation({
  listLength: suggestionCount,
  activeIndex,
  loop: true,
  onSelect: (idx) => pick(s.suggestions.value[idx]),
})

// Reset the highlight whenever the suggestion set changes out from under us.
watch(() => s.suggestions.value, () => { activeIndex.value = -1 })
watch(activeIndex, (i) => {
  if (i < 0) return
  nextTick(() => containerRef.value
    ?.querySelector<HTMLElement>(`[data-sug-idx="${i}"]`)
    ?.scrollIntoView({ block: 'nearest' }))
})

function onKeydown(e: KeyboardEvent) {
  // Pop last chip when backspacing on an empty input.
  if (e.key === 'Backspace' && s.query.value === '' && s.activeFilters.value.length > 0) {
    s.popLastFilter()
    return
  }
  // Whenever suggestions exist, the arrows/Home/End/Enter drive the dropdown —
  // open it first if needed so navigation always works (the original bug: it
  // jumped to the results tree instead of into the open suggestion list).
  if (suggestionCount.value > 0 &&
      ['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter'].includes(e.key)) {
    open.value = true
    handleKey(e)
    if (e.defaultPrevented) return // consumed (navigated or selected)
  }
  // ArrowDown with no (more) suggestions to navigate → jump into the result tree.
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    open.value = false
    emit('focusResults')
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    // No highlighted suggestion → commit whatever facet the caret is on.
    if (!s.commitTrigger()) open.value = false
    return
  }
  if (e.key === 'Escape') { open.value = false; activeIndex.value = -1 }
}

// Small icon per facet field so chips read at a glance.
function chipIcon(field: string) {
  if (field === 'sport') return Bike
  if (field === 'year') return Calendar
  if (field === 'tag') return TagIcon
  if (field === 'gear') return Bike
  if (field === 'loc') return MapPin
  if (field === 'country') return Flag
  return Gauge // numeric: dist / elev / hr / speed
}
</script>

<template>
  <div ref="containerRef" class="relative" @keydown="onKeydown">
    <div
      class="flex flex-wrap items-center gap-1 w-full px-2 py-1 rounded border border-border bg-transparent text-[11px] focus-within:border-primary transition-colors"
      @click="inputRef?.focus()"
    >
      <Search :size="12" class="text-muted-fg shrink-0" />

      <!-- Committed facet chips -->
      <span
        v-for="(f, idx) in s.activeFilters.value"
        :key="idx"
        class="inline-flex items-center gap-1 pl-1.5 pr-1 py-0.5 rounded bg-primary/10 text-primary border border-primary/30 select-none"
      >
        <component :is="chipIcon(f.field)" :size="10" />
        <span class="truncate max-w-[160px]">{{ f.label }}</span>
        <button class="hover:text-red-500" title="Remove filter" @click.stop="s.removeFilter(idx)">
          <X :size="10" />
        </button>
      </span>

      <input
        ref="inputRef"
        v-model="s.query.value"
        type="text"
        :placeholder="s.activeFilters.value.length ? 'and…' : 'Search — try sport:cycling year:2020 tag:climb event:puncture'"
        class="flex-1 min-w-[120px] bg-transparent border-none outline-none py-0.5"
        autocomplete="off"
        spellcheck="false"
        @input="onInput"
        @focus="open = true"
      />

      <button
        v-if="!s.isEmpty.value"
        class="text-muted-fg hover:text-foreground shrink-0"
        title="Clear search"
        @click.stop="s.clearAll(); open = false"
      >
        <X :size="12" />
      </button>
    </div>

    <!-- Autocomplete dropdown -->
    <div
      v-if="open && s.suggestions.value.length"
      class="absolute z-30 left-0 right-0 mt-1 max-h-60 overflow-y-auto rounded border border-border bg-background shadow-lg text-[11px]"
    >
      <button
        v-for="(sug, idx) in s.suggestions.value"
        :key="sug"
        type="button"
        :data-sug-idx="idx"
        class="w-full text-left px-2 py-1 truncate"
        :class="idx === activeIndex ? 'bg-primary/15 text-primary' : 'hover:bg-muted/50'"
        @mouseenter="activeIndex = idx"
        @click="pick(sug)"
      >
        {{ sug }}
      </button>
    </div>
  </div>
</template>
