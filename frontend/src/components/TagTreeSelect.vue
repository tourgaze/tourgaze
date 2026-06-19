<script setup lang="ts">
/**
 * Single-tag picker styled like a textbox: click to open a popover holding the
 * full TagTree (pick mode), navigate it by keyboard (↑↓ move · →← open/close ·
 * `*` expand all · type to search · ↵ / dbl-click to choose), and the chosen tag
 * fills the box. A keyboard/click alternative to dragging a tag onto a drop zone.
 *
 * v-model is a single tag id ('' = nothing selected).
 */
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { onClickOutside, onKeyStroke } from '@vueuse/core'
import { ChevronDown, X } from 'lucide-vue-next'
import { getTags, type Tag } from '@/api/client'
import DynamicIcon from '@/components/DynamicIcon.vue'
import TagTree from '@/components/TagTree.vue'

const props = withDefaults(defineProps<{
  modelValue: string
  placeholder?: string
}>(), {
  placeholder: 'Pick a parent tag…',
})
const emit = defineEmits<{ 'update:modelValue': [v: string] }>()

const { data: tags } = useQuery({ queryKey: ['tags'], queryFn: getTags })
const selected = computed<Tag | undefined>(() =>
  props.modelValue ? (tags.value ?? []).find(t => t.id === props.modelValue) : undefined,
)

const open = ref(false)
const containerRef = ref<HTMLElement | null>(null)
onClickOutside(containerRef, () => { open.value = false })
onKeyStroke('Escape', (e) => { if (open.value) { e.preventDefault(); open.value = false } })

function onPick(tag: Tag) {
  emit('update:modelValue', tag.id!)
  open.value = false
}
function clear() {
  emit('update:modelValue', '')
  open.value = false
}
</script>

<template>
  <div ref="containerRef" class="relative">
    <!-- Trigger: looks like a text input, shows the current selection -->
    <button
      type="button"
      class="flex w-full items-center gap-1.5 px-1.5 py-1 rounded border border-border bg-background text-[11px] text-left transition-colors"
      :class="open ? 'border-primary' : 'hover:border-primary/60'"
      :title="selected ? `Grouping by ${selected.name} children — click to change` : placeholder"
      @click="open = !open"
    >
      <template v-if="selected">
        <DynamicIcon v-if="selected.icon" :name="selected.icon" :size="12" class="shrink-0"
          :style="{ color: selected.color || undefined }" />
        <span v-else class="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
          :style="{ backgroundColor: selected.color || '#9ca3af' }" />
        <span class="truncate flex-1">{{ selected.name }}</span>
        <X :size="12" class="shrink-0 text-muted-fg hover:text-foreground"
          role="button" aria-label="Clear" @click.stop="clear" />
      </template>
      <template v-else>
        <span class="truncate flex-1 text-muted-fg italic">{{ placeholder }}</span>
        <ChevronDown :size="12" class="shrink-0 text-muted-fg" />
      </template>
    </button>

    <!-- Popover: the reusable tag tree in pick mode -->
    <div
      v-if="open"
      class="absolute z-40 left-0 right-0 mt-1 max-h-80 overflow-y-auto rounded-lg border border-border bg-background shadow-lg p-2"
    >
      <TagTree mode="pick" @activate="onPick" />
    </div>
  </div>
</template>
