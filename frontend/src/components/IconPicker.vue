<script setup lang="ts">
/**
 * Lucide icon picker — a searchable, keyboard-navigable icon window (a bigger
 * take on matrosdms' IconPicker), restyled to TourGaze tokens. v-model is the
 * icon name (empty string = none).
 *
 * Keyboard (while the window is open): type to filter, ←/→/↑/↓ to move the
 * selection across the grid, Enter to pick, Esc to close.
 */
import { ref, computed, shallowRef, nextTick, watch } from 'vue'
import { onClickOutside } from '@vueuse/core'
import { Search, X, ChevronDown } from 'lucide-vue-next'
import * as LucideIcons from 'lucide-vue-next'

const COLS = 8
const MAX_RESULTS = 200

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [string] }>()

const isOpen = ref(false)
const containerRef = ref<HTMLElement | null>(null)
const gridRef = ref<HTMLElement | null>(null)
const searchQuery = ref('')
const activeIndex = ref(0)

// Non-reactive list of all exported icon names (PascalCase components).
const allIcons = shallowRef(
  Object.keys(LucideIcons).filter(k => /^[A-Z]/.test(k) && k !== 'Icon' && !k.endsWith('Icon')),
)

const matches = computed(() => {
  const q = searchQuery.value.toLowerCase().trim()
  return q ? allIcons.value.filter(n => n.toLowerCase().includes(q)) : allIcons.value
})
const filteredIcons = computed(() => matches.value.slice(0, MAX_RESULTS))

const getIcon = (name: string) => (LucideIcons as Record<string, unknown>)[name]
const current = computed(() => (props.modelValue ? getIcon(props.modelValue) : null))

function selectIcon(name: string) {
  emit('update:modelValue', name)
  isOpen.value = false
}
function clear() { emit('update:modelValue', '') }

function scrollActiveIntoView() {
  nextTick(() => {
    gridRef.value?.querySelector<HTMLElement>(`[data-idx="${activeIndex.value}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  })
}

function onKeydown(e: KeyboardEvent) {
  const n = filteredIcons.value.length
  if (!n) return
  let next = activeIndex.value
  switch (e.key) {
    case 'ArrowRight': next = Math.min(n - 1, activeIndex.value + 1); break
    case 'ArrowLeft': next = Math.max(0, activeIndex.value - 1); break
    case 'ArrowDown': next = Math.min(n - 1, activeIndex.value + COLS); break
    case 'ArrowUp': next = Math.max(0, activeIndex.value - COLS); break
    case 'Enter': e.preventDefault(); selectIcon(filteredIcons.value[Math.min(activeIndex.value, n - 1)]); return
    case 'Escape': isOpen.value = false; return
    default: return
  }
  e.preventDefault()
  activeIndex.value = next
  scrollActiveIntoView()
}

// Reset the cursor whenever the result set changes.
watch(filteredIcons, () => { activeIndex.value = 0 })

function openWindow() {
  isOpen.value = !isOpen.value
  if (isOpen.value) { searchQuery.value = ''; activeIndex.value = 0 }
}

onClickOutside(containerRef, () => { isOpen.value = false })
</script>

<template>
  <div ref="containerRef" class="relative">
    <div
      class="flex items-center justify-between gap-2 h-8 px-2 bg-background border border-border rounded cursor-pointer hover:bg-muted/40 transition-colors text-xs"
      :class="{ 'ring-1 ring-primary': isOpen }"
      @click="openWindow"
    >
      <div class="flex items-center gap-1.5 overflow-hidden">
        <component :is="current" v-if="current" :size="14" class="text-primary shrink-0" />
        <span v-if="modelValue" class="truncate">{{ modelValue }}</span>
        <span v-else class="text-muted-fg italic">Icon…</span>
      </div>
      <div class="flex items-center gap-1 shrink-0">
        <button v-if="modelValue" class="p-0.5 hover:text-red-500 text-muted-fg" @click.stop="clear">
          <X :size="12" />
        </button>
        <ChevronDown :size="12" class="text-muted-fg opacity-60" />
      </div>
    </div>

    <div
      v-if="isOpen"
      class="absolute z-50 top-full mt-1 w-[360px] bg-background border border-border rounded-lg shadow-xl overflow-hidden"
      @keydown="onKeydown"
    >
      <div class="p-2 border-b border-border bg-muted/20">
        <div class="relative">
          <Search :size="13" class="absolute left-2 top-1/2 -translate-y-1/2 text-muted-fg" />
          <input
            v-model="searchQuery"
            type="text"
            placeholder="Search icons… (↑↓←→ to move, Enter to pick)"
            class="w-full pl-7 pr-2 py-1.5 text-xs bg-background border border-border rounded focus:outline-none focus:border-primary"
            autofocus
          />
        </div>
      </div>
      <div ref="gridRef" class="p-2 grid grid-cols-8 gap-1 max-h-[300px] overflow-y-auto">
        <button
          v-for="(name, i) in filteredIcons"
          :key="name"
          :data-idx="i"
          class="flex items-center justify-center p-1.5 rounded transition-colors"
          :class="modelValue === name
            ? 'bg-primary/20 text-primary'
            : i === activeIndex ? 'bg-primary/15 text-primary' : 'hover:bg-primary/10 hover:text-primary text-muted-fg'"
          :title="name"
          @mouseenter="activeIndex = i"
          @click="selectIcon(name)"
        >
          <component :is="getIcon(name)" :size="16" />
        </button>
      </div>
      <div class="px-2 py-1 bg-muted/20 text-[9px] text-center text-muted-fg border-t border-border">
        {{ filteredIcons.length }} of {{ matches.length }}{{ matches.length > MAX_RESULTS ? ' — refine to see more' : '' }}
      </div>
    </div>
  </div>
</template>
