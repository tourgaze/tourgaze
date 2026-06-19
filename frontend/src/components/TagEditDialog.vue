<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { X, Save, Trash2, AlertTriangle } from 'lucide-vue-next'
import { getTags, getActivities, updateTag, deleteTag, getTagImpact, type Tag } from '@/api/client'
import { useEscapeClose } from '@/composables/useEscapeClose'
import IconPicker from '@/components/IconPicker.vue'

/**
 * Matrosdms-style tag editor: rename, recolor, change parent. Cycle-free
 * parent picker (can't make a tag a child of itself or one of its
 * descendants). Delete shows a warning of how many child tags + activities
 * are about to be affected before the cascading drop.
 */
const props = defineProps<{ tagId: string | null }>()
const emit = defineEmits<{ close: [] }>()
useEscapeClose(() => props.tagId != null, () => emit('close'))

const qc = useQueryClient()
const { data: tags } = useQuery({ queryKey: ['tags'], queryFn: getTags })
const { data: activities } = useQuery({ queryKey: ['activities'], queryFn: getActivities })

const tag = computed<Tag | null>(() =>
  tags.value?.find(t => t.id === props.tagId) ?? null,
)

// ── Form state (resets whenever a new tag opens) ─────────────────────────────
const name = ref('')
const color = ref('#3b82f6')
const icon = ref('')
const parentId = ref<string | null>(null)
const showDeleteConfirm = ref(false)

watch(tag, (t) => {
  if (!t) return
  name.value = t.name ?? ''
  color.value = t.color || '#3b82f6'
  icon.value = t.icon ?? ''
  parentId.value = t.parentId ?? null
  showDeleteConfirm.value = false
}, { immediate: true })

// ── Cycle-free parent options ────────────────────────────────────────────────
// The parent picker hides the tag itself + everything beneath it, otherwise
// the user could create a loop (A → B → A).
const descendantIds = computed<Set<string>>(() => {
  const out = new Set<string>()
  if (!props.tagId || !tags.value) return out
  const childrenOf = new Map<string, string[]>()
  for (const t of tags.value) {
    if (!t.parentId) continue
    if (!childrenOf.has(t.parentId)) childrenOf.set(t.parentId, [])
    childrenOf.get(t.parentId)!.push(t.id!)
  }
  const stack = [props.tagId]
  while (stack.length) {
    const cur = stack.pop()!
    out.add(cur)
    for (const c of childrenOf.get(cur) ?? []) stack.push(c)
  }
  return out
})

const parentOptions = computed(() => {
  const out: { id: string; label: string; depth: number }[] = []
  if (!tags.value) return out
  const byParent = new Map<string | null, Tag[]>()
  for (const t of tags.value) {
    const p = t.parentId ?? null
    if (!byParent.has(p)) byParent.set(p, [])
    byParent.get(p)!.push(t)
  }
  for (const [, arr] of byParent) arr.sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
  const walk = (pid: string | null, depth: number) => {
    for (const t of byParent.get(pid) ?? []) {
      if (descendantIds.value.has(t.id!)) continue  // skip self + descendants
      out.push({ id: t.id!, label: t.name ?? '(unnamed)', depth })
      walk(t.id!, depth + 1)
    }
  }
  walk(null, 0)
  return out
})

// ── Impact analysis shown before destructive delete ──────────────────────────
// Authoritative numbers come from the server (counts the full subtree, the
// activity↔tag links, AND the filter presets that group by an affected tag —
// which a client-side count can't see). Fetched lazily when the user opens the
// confirm step; falls back to a client-side estimate while in flight.
const { data: impact, isFetching: impactLoading } = useQuery({
  queryKey: computed(() => ['tag-impact', props.tagId]),
  queryFn: () => getTagImpact(props.tagId!),
  enabled: computed(() => showDeleteConfirm.value && props.tagId != null),
  staleTime: 0,
})

const childCount = computed(() => impact.value?.descendantTags
  ?? (tags.value ?? []).filter(t => t.parentId === props.tagId).length)
const activityCount = computed(() => {
  if (impact.value) return impact.value.activities
  if (!props.tagId || !activities.value) return 0
  return activities.value.filter(a => (a.tagIds ?? []).some(id => descendantIds.value.has(id))).length
})
const presetCount = computed(() => impact.value?.presets ?? 0)

// ── Mutations ────────────────────────────────────────────────────────────────
const saveMut = useMutation({
  mutationFn: () => updateTag(props.tagId!, {
    name: name.value.trim(),
    color: color.value,
    icon: icon.value || undefined,
    parentId: parentId.value ?? undefined,
  } as any),
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['tags'] })
    push.success({ title: 'Tag saved' })
    emit('close')
  },
  onError: () => push.error('Could not save tag'),
})

const deleteMut = useMutation({
  mutationFn: () => deleteTag(props.tagId!),
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['tags'] })
    qc.invalidateQueries({ queryKey: ['activities'] })
    push.success({ title: 'Tag deleted' })
    emit('close')
  },
  onError: () => push.error('Could not delete tag'),
})
</script>

<template>
  <div v-if="tagId && tag" class="fixed inset-0 z-[4000] flex items-center justify-center bg-black/50 backdrop-blur-sm p-4"
    @click.self="emit('close')">
    <div class="w-full max-w-md bg-background border border-border rounded-xl shadow-xl overflow-hidden flex flex-col">

      <!-- Header -->
      <div class="flex items-center gap-2 px-4 py-3 border-b border-border">
        <span class="inline-block w-3 h-3 rounded-sm" :style="{ backgroundColor: tag.color || '#9ca3af' }" />
        <h2 class="text-sm font-semibold">Edit tag</h2>
        <button class="btn-icon ml-auto" @click="emit('close')"><X :size="14" /></button>
      </div>

      <!-- Form -->
      <div v-if="!showDeleteConfirm" class="p-4 space-y-3">
        <label class="block text-sm">
          <span class="text-xs font-medium text-muted-fg">Name</span>
          <input v-model="name" required
            class="mt-1 block w-full rounded border border-border bg-transparent px-3 py-1.5 text-sm focus:border-primary focus:outline-none" />
        </label>

        <div class="grid grid-cols-2 gap-3">
          <label class="block text-sm">
            <span class="text-xs font-medium text-muted-fg">Color</span>
            <input v-model="color" type="color"
              class="mt-1 block w-full h-9 rounded border border-border bg-transparent cursor-pointer" />
          </label>
          <div class="block text-sm">
            <span class="text-xs font-medium text-muted-fg">Icon</span>
            <div class="mt-1"><IconPicker v-model="icon" /></div>
          </div>
        </div>

        <div class="grid grid-cols-1 gap-3">
          <label class="block text-sm">
            <span class="text-xs font-medium text-muted-fg">Parent</span>
            <select v-model="parentId"
              class="mt-1 block w-full rounded border border-border bg-transparent px-2 py-1.5 text-xs">
              <option :value="null">— Root —</option>
              <option v-for="o in parentOptions" :key="o.id" :value="o.id">
                {{ '— '.repeat(o.depth) + o.label }}
              </option>
            </select>
          </label>
        </div>

        <p class="text-[10px] text-muted-fg">
          Pick <strong>— Root —</strong> to promote this tag to its own top-level category.
          Tags that would create a cycle are filtered out of the list.
        </p>
      </div>

      <!-- Delete confirmation -->
      <div v-else class="p-4 space-y-3">
        <div class="flex items-start gap-2 p-3 rounded border border-amber-300 bg-amber-50 dark:bg-amber-950/30 text-amber-700 dark:text-amber-300 text-xs">
          <AlertTriangle :size="16" class="flex-shrink-0 mt-0.5" />
          <div>
            <p class="font-semibold mb-1">Delete &ldquo;{{ tag.name }}&rdquo;?</p>
            <p v-if="impactLoading" class="opacity-70">Checking impact…</p>
            <ul v-else class="list-disc pl-4 space-y-0.5">
              <li v-if="childCount > 0"><strong>{{ childCount }}</strong> child tag{{ childCount === 1 ? '' : 's' }} will be deleted too (cascade).</li>
              <li v-if="activityCount > 0"><strong>{{ activityCount }}</strong> activit{{ activityCount === 1 ? 'y' : 'ies' }} currently use this tag (or a descendant); they'll lose those tag links.</li>
              <li v-if="presetCount > 0"><strong>{{ presetCount }}</strong> filter preset{{ presetCount === 1 ? '' : 's' }} group by it (or a descendant); their grouping will be cleared.</li>
              <li v-if="childCount === 0 && activityCount === 0 && presetCount === 0">Nothing else depends on it.</li>
            </ul>
          </div>
        </div>
        <p class="text-[11px] text-muted-fg">This cannot be undone.</p>
      </div>

      <!-- Footer -->
      <div class="flex items-center justify-between gap-2 px-4 py-2.5 border-t border-border bg-muted/10">
        <button v-if="!showDeleteConfirm"
          class="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium rounded border border-red-300 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20"
          @click="showDeleteConfirm = true">
          <Trash2 :size="12" /> Delete
        </button>
        <button v-else
          class="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium rounded border border-border text-muted-fg hover:text-foreground"
          @click="showDeleteConfirm = false">
          Back
        </button>

        <div class="flex gap-2">
          <button class="px-3 py-1 text-xs font-medium rounded border border-border text-muted-fg hover:text-foreground"
            @click="emit('close')">
            Cancel
          </button>
          <button v-if="!showDeleteConfirm"
            class="inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium rounded bg-primary text-primary-fg hover:bg-primary/90 disabled:opacity-50"
            :disabled="saveMut.isPending.value || !name.trim()"
            @click="saveMut.mutate()">
            <Save :size="12" /> {{ saveMut.isPending.value ? 'Saving…' : 'Save' }}
          </button>
          <button v-else
            class="inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium rounded bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
            :disabled="deleteMut.isPending.value"
            @click="deleteMut.mutate()">
            <Trash2 :size="12" /> {{ deleteMut.isPending.value ? 'Deleting…' : 'Delete tag' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
