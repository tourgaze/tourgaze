<script setup lang="ts">
/**
 * Type-to-search/create tag input used by the Add/Edit Tour panels.
 *
 * Two dropdown modes:
 *  - Browse (empty input): the full tag hierarchy as an indented, expandable
 *    TREE — every node is selectable, parents as well as leaves (selecting a
 *    parent tags the ride with that parent; the faceted search then matches it
 *    transitively).
 *  - Search (typing): a flat, path-labelled result list across the whole tree,
 *    plus an inline "Create" row for a brand-new name.
 *
 * v-model is a Set<string> of selected tag IDs.
 */
import { computed, ref, watch, nextTick } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { X, Plus, ChevronRight, ChevronDown, Check } from 'lucide-vue-next'
import { onClickOutside } from '@vueuse/core'
import { getTags, createTag, type Tag } from '@/api/client'
import DynamicIcon from '@/components/DynamicIcon.vue'

const ROOT = '__root__'

const props = defineProps<{ modelValue: Set<string> }>()
const emit = defineEmits<{ 'update:modelValue': [v: Set<string>] }>()

const qc = useQueryClient()
const { data: tags } = useQuery({ queryKey: ['tags'], queryFn: getTags })

const byId = computed(() => new Map((tags.value ?? []).map(t => [t.id!, t])))

/** Full ancestry path, e.g. "Pass › Jaufenpass". */
function pathOf(t: Tag): string {
  const parts: string[] = [t.name ?? '']
  let cur = t.parentId ? byId.value.get(t.parentId) : undefined
  let guard = 0
  while (cur && guard++ < 20) {
    parts.unshift(cur.name ?? '')
    cur = cur.parentId ? byId.value.get(cur.parentId) : undefined
  }
  return parts.join(' › ')
}

const selectedTags = computed<Tag[]>(() =>
  Array.from(props.modelValue).map(id => byId.value.get(id)).filter(Boolean) as Tag[],
)

// parentId (or ROOT) → sorted children, for the browse tree.
const childrenByParent = computed(() => {
  const m = new Map<string, Tag[]>()
  for (const t of tags.value ?? []) {
    const pid = t.parentId && byId.value.has(t.parentId) ? t.parentId : ROOT
    const arr = m.get(pid) ?? []
    arr.push(t)
    m.set(pid, arr)
  }
  for (const arr of m.values()) arr.sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
  return m
})
const hasChildren = (id: string) => (childrenByParent.value.get(id)?.length ?? 0) > 0

// ── Input + dropdown state ───────────────────────────────────────────────────
const text = ref('')
const open = ref(false)
const activeIndex = ref(0)
const containerRef = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLInputElement | null>(null)
onClickOutside(containerRef, () => { open.value = false })

// Expand every parent by default the first time tags arrive, so the whole tree
// is visible/selectable without hunting for disclosure triangles.
const expanded = ref<Set<string>>(new Set())
watch(tags, (t) => {
  if (t && expanded.value.size === 0) {
    expanded.value = new Set(t.filter(x => hasChildren(x.id!)).map(x => x.id!))
  }
}, { immediate: true })
function toggleExpand(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  expanded.value = next
}

type Row = { tag: Tag; depth: number; hasChildren: boolean }

// Flat search results (typing) — across the whole tree, path-labelled.
const searchRows = computed<Row[]>(() => {
  const q = text.value.trim().toLowerCase()
  return (tags.value ?? [])
    .filter(t => !q || (t.name ?? '').toLowerCase().includes(q) || pathOf(t).toLowerCase().includes(q))
    .sort((a, b) => pathOf(a).localeCompare(pathOf(b)))
    .slice(0, 40)
    .map(t => ({ tag: t, depth: 0, hasChildren: false }))
})

// Indented hierarchy (browsing) — respects the expanded set.
const treeRows = computed<Row[]>(() => {
  const out: Row[] = []
  const walk = (nodes: Tag[], depth: number) => {
    for (const n of nodes) {
      const kids = hasChildren(n.id!)
      out.push({ tag: n, depth, hasChildren: kids })
      if (kids && expanded.value.has(n.id!)) walk(childrenByParent.value.get(n.id!)!, depth + 1)
    }
  }
  walk(childrenByParent.value.get(ROOT) ?? [], 0)
  return out
})

const visibleRows = computed<Row[]>(() => (text.value.trim() ? searchRows.value : treeRows.value))

// Offer to create only when the typed name matches no existing tag exactly.
const canCreate = computed(() => {
  const q = text.value.trim()
  if (!q) return false
  return !(tags.value ?? []).some(t => (t.name ?? '').toLowerCase() === q.toLowerCase())
})

const itemCount = computed(() => visibleRows.value.length + (canCreate.value ? 1 : 0))
const isCreateRow = (i: number) => canCreate.value && i === visibleRows.value.length

function toggleTag(id: string) {
  const next = new Set(props.modelValue)
  if (next.has(id)) next.delete(id); else next.add(id)
  emit('update:modelValue', next)
  // After a search pick, reset the query so browse mode returns; keep focus.
  if (text.value.trim()) { text.value = ''; activeIndex.value = 0 }
  nextTick(() => inputRef.value?.focus())
}

function remove(id: string) {
  const next = new Set(props.modelValue)
  next.delete(id)
  emit('update:modelValue', next)
}

const createMut = useMutation({
  mutationFn: (name: string) => createTag({ name: name.trim() } as Partial<Tag>),
  onSuccess: async (created) => {
    await qc.invalidateQueries({ queryKey: ['tags'] })
    if (created?.id) {
      const next = new Set(props.modelValue)
      next.add(created.id)
      emit('update:modelValue', next)
    }
    push.success({ title: 'Tag created', message: created?.name ?? '' })
    text.value = ''
    activeIndex.value = 0
    nextTick(() => inputRef.value?.focus())
  },
  onError: () => push.error('Could not create tag'),
})

function commit(i: number) {
  if (i < visibleRows.value.length) toggleTag(visibleRows.value[i].tag.id!)
  else if (isCreateRow(i)) createMut.mutate(text.value.trim())
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Backspace' && text.value === '' && selectedTags.value.length) {
    remove(selectedTags.value[selectedTags.value.length - 1].id!)
    return
  }
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    if (!itemCount.value) return
    e.preventDefault()
    open.value = true
    const dir = e.key === 'ArrowDown' ? 1 : -1
    activeIndex.value = (activeIndex.value + dir + itemCount.value) % itemCount.value
    return
  }
  // Right/Left expand or collapse the focused tree node (browse mode).
  if ((e.key === 'ArrowRight' || e.key === 'ArrowLeft') && !text.value.trim()) {
    const row = visibleRows.value[activeIndex.value]
    if (row?.hasChildren) {
      e.preventDefault()
      const isOpen = expanded.value.has(row.tag.id!)
      if (e.key === 'ArrowRight' && !isOpen) toggleExpand(row.tag.id!)
      if (e.key === 'ArrowLeft' && isOpen) toggleExpand(row.tag.id!)
    }
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    if (itemCount.value) commit(Math.min(activeIndex.value, itemCount.value - 1))
    return
  }
  if (e.key === 'Escape') { open.value = false }
}
</script>

<template>
  <div ref="containerRef" class="relative" @keydown="onKeydown">
    <div
      class="flex flex-wrap items-center gap-1 w-full px-1.5 py-1 rounded border border-border bg-background text-xs focus-within:border-primary transition-colors"
      @click="inputRef?.focus()"
    >
      <!-- Selected chips -->
      <span
        v-for="t in selectedTags"
        :key="t.id!"
        class="inline-flex items-center gap-1 pl-1.5 pr-1 py-0.5 rounded border select-none"
        :style="{ borderColor: (t.color || '#cbd5e1') + '88', backgroundColor: (t.color || '#94a3b8') + '1a' }"
        :title="pathOf(t)"
      >
        <DynamicIcon v-if="t.icon" :name="t.icon" :size="11" :style="{ color: t.color || undefined }" />
        <span v-else class="inline-block w-2 h-2 rounded-sm" :style="{ backgroundColor: t.color || '#94a3b8' }" />
        <span class="truncate max-w-[160px]">{{ t.name }}</span>
        <button class="hover:text-red-500" title="Remove tag" @click.stop="remove(t.id!)"><X :size="10" /></button>
      </span>

      <input
        ref="inputRef"
        v-model="text"
        type="text"
        :placeholder="selectedTags.length ? 'add tag…' : 'Type to search or create tags…'"
        class="flex-1 min-w-[120px] bg-transparent border-none outline-none py-0.5"
        autocomplete="off"
        @focus="open = true"
        @input="open = true; activeIndex = 0"
      />
    </div>

    <!-- Dropdown -->
    <div
      v-if="open && itemCount"
      class="absolute z-40 left-0 right-0 mt-1 max-h-72 overflow-y-auto rounded border border-border bg-background shadow-lg text-xs"
    >
      <div
        v-for="(row, i) in visibleRows"
        :key="row.tag.id!"
        class="w-full flex items-center gap-1 pr-2 py-1 cursor-pointer"
        :class="i === activeIndex ? 'bg-primary/15 text-primary' : 'hover:bg-muted/50'"
        :style="{ paddingLeft: (6 + row.depth * 14) + 'px' }"
        @mouseenter="activeIndex = i"
        @click="toggleTag(row.tag.id!)"
      >
        <!-- Disclosure triangle (browse mode, nodes with children) -->
        <button
          v-if="row.hasChildren && !text.trim()"
          class="w-4 h-4 flex items-center justify-center text-muted-fg shrink-0"
          @click.stop="toggleExpand(row.tag.id!)"
        >
          <component :is="expanded.has(row.tag.id!) ? ChevronDown : ChevronRight" :size="12" />
        </button>
        <span v-else class="w-4 h-4 shrink-0" />

        <DynamicIcon v-if="row.tag.icon" :name="row.tag.icon" :size="12" class="shrink-0" :style="{ color: row.tag.color || undefined }" />
        <span v-else class="inline-block w-2 h-2 rounded-sm shrink-0" :style="{ backgroundColor: row.tag.color || '#94a3b8' }" />

        <span class="truncate flex-1">{{ text.trim() ? pathOf(row.tag) : row.tag.name }}</span>

        <Check v-if="modelValue.has(row.tag.id!)" :size="12" class="shrink-0 text-primary" />
      </div>

      <button
        v-if="canCreate"
        type="button"
        class="w-full flex items-center gap-1.5 px-2 py-1 text-left border-t border-border"
        :class="isCreateRow(activeIndex) ? 'bg-primary/15 text-primary' : 'hover:bg-muted/50 text-muted-fg'"
        @mouseenter="activeIndex = visibleRows.length"
        @click="createMut.mutate(text.trim())"
      >
        <Plus :size="12" class="shrink-0" />
        <span class="truncate">Create “{{ text.trim() }}”</span>
      </button>
    </div>
  </div>
</template>
