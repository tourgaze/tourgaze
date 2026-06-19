<script setup lang="ts">
/**
 * Render a Lucide icon by its string name (e.g. "Mountain"). Used so tags can
 * persist an icon as plain text. Falls back to a neutral Tag icon when the
 * name is empty or unknown. Ported in spirit from matrosdms' DynamicIcon.
 */
import { computed } from 'vue'
import * as LucideIcons from 'lucide-vue-next'
import { Tag as TagFallback } from 'lucide-vue-next'

const props = withDefaults(defineProps<{ name?: string | null; size?: number }>(), {
  size: 14,
})

const icon = computed(() => {
  if (!props.name) return TagFallback
  return (LucideIcons as Record<string, unknown>)[props.name] ?? TagFallback
})
</script>

<template>
  <component :is="icon" :size="size" />
</template>
