<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { ChevronRight, ChevronDown, Plus, FolderPlus, Pencil, RefreshCw, Search, X, GripVertical, Keyboard } from 'lucide-vue-next'
import { getTags, createTag, type Tag } from '@/api/client'
import TagEditDialog from '@/components/TagEditDialog.vue'
import IconPicker from '@/components/IconPicker.vue'
import DynamicIcon from '@/components/DynamicIcon.vue'

type TagNode = Tag & { children: TagNode[] }

/**
 * Reusable matros-style category tree (ported in spirit from matrosdms'
 * CategoryTree.vue). Two modes:
 *
 *  - `manage` (Settings): double-click / Enter opens the edit dialog, and the
 *    create form + per-row add/edit chrome are shown.
 *  - `pick` (Tours filter): double-click / Enter emits `activate` with the tag
 *    so the host can act on it (e.g. add a `tag:` WHERE condition). Management
 *    chrome is hidden. Set `draggable` to also allow dragging a tag out (the
 *    Tours "drag a tag onto an activity / group-by zone" flow).
 *
 * Keyboard nav (↑↓ move, →← open/close, Home/End, `*` expand subtree, type to
 * search) is scoped to the focused tree — click a row (or tab in) first.
 */
const props = withDefaults(defineProps<{
  mode?: 'manage' | 'pick'
  draggable?: boolean
}>(), {
  mode: 'manage',
  draggable: false,
})

const emit = defineEmits<{ activate: [tag: Tag] }>()

const qc = useQueryClient()
const { data: tags, isPending } = useQuery({ queryKey: ['tags'], queryFn: getTags })

// Filter box (matros-style): typing narrows the tree and force-expands so
// matches deep in the hierarchy stay visible.
const filterText = ref('')

// The keyboard-shortcut legend is tucked behind a hint toggle so it doesn't
// crowd the (often narrow) pane — click the ⌨ icon to reveal it.
const showHint = ref(false)

const tree = computed<TagNode[]>(() => {
  const list = (tags.value ?? []) as Tag[]
  const byId = new Map<string, TagNode>(list.map(t => [t.id!, { ...t, children: [] }]))
  const roots: TagNode[] = []
  for (const node of byId.values()) {
    if (node.parentId && byId.has(node.parentId)) byId.get(node.parentId)!.children.push(node)
    else roots.push(node)
  }
  const sortRecurse = (ns: TagNode[]) => {
    ns.sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
    for (const n of ns) sortRecurse(n.children)
  }
  sortRecurse(roots)
  return roots
})

const tagsById = computed(() => {
  const m = new Map<string, Tag>()
  for (const t of (tags.value ?? []) as Tag[]) if (t.id) m.set(t.id, t)
  return m
})

const expanded = ref(new Set<string>())
function toggle(id: string) {
  if (expanded.value.has(id)) expanded.value.delete(id)
  else expanded.value.add(id)
  expanded.value = new Set(expanded.value)
}

// ── Inline create form (manage mode only) ────────────────────────────────────
// `formParentId` of null means "create as a top-level root". Two affordances
// for that: the dedicated "+ New root" button up top, OR picking "— Root —"
// in the parent dropdown inside the form.
const formOpen = ref(false)
const formParentId = ref<string | null>(null)
const newName = ref('')
const newColor = ref('#3b82f6')
const newIcon = ref('')

const flatForSelect = computed(() => {
  const out: { id: string; label: string }[] = []
  const walk = (ns: TagNode[], depth: number) => {
    for (const n of ns) {
      out.push({ id: n.id!, label: '— '.repeat(depth) + n.name })
      walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

function openNewRoot() {
  formParentId.value = null
  newName.value = ''
  newIcon.value = ''
  formOpen.value = true
}
function openNewChild(parentId: string) {
  formParentId.value = parentId
  newName.value = ''
  newIcon.value = ''
  formOpen.value = true
  expanded.value.add(parentId)
  expanded.value = new Set(expanded.value)
}

const createMut = useMutation({
  mutationFn: () => createTag({
    name: newName.value.trim(),
    color: newColor.value,
    icon: newIcon.value || undefined,
    parentId: formParentId.value ?? undefined,
  } as any),
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['tags'] })
    push.success({ title: 'Tag added' })
    newName.value = ''
    formOpen.value = false
  },
  onError: () => push.error('Could not add tag'),
})

// Editing happens in a dedicated dialog (TagEditDialog) — it also handles
// delete with an affected-counts warning, so there's no inline delete button.
const editingTagId = ref<string | null>(null)
function openEdit(id: string) { if (props.mode === 'manage') editingTagId.value = id }
function closeEdit() { editingTagId.value = null }

// ── Persistent selection + keyboard navigation ──────────────────────────────
// A clicked row stays highlighted; the keyboard moves around the tree
// Windows-Explorer-style. Scoped to the focused tree (see rootEl) so it doesn't
// hijack arrow keys for the rest of the page when used as a secondary panel.
const rootEl = ref<HTMLElement | null>(null)
const selectedId = ref<string | null>(null)
function selectRow(id: string) {
  selectedId.value = id
  rootEl.value?.focus()
  scrollSelectedIntoView()
}

function scrollSelectedIntoView() {
  if (!selectedId.value) return
  // Defer to next frame so the row exists if we just expanded its parent.
  requestAnimationFrame(() => {
    const el = document.querySelector<HTMLElement>(`[data-tag-row-id="${selectedId.value}"]`)
    el?.scrollIntoView({ block: 'nearest' })
  })
}

/** What double-click / Enter does, per mode. */
function activateRow(node: TagNode) {
  if (props.mode === 'pick') emit('activate', node)
  else openEdit(node.id!)
}
function activateById(id: string) {
  const t = tagsById.value.get(id)
  if (!t) return
  if (props.mode === 'pick') emit('activate', t); else openEdit(id)
}

// ── Drag source (pick + draggable): drag a tag onto an activity / group zone ──
function onDragStart(e: DragEvent, tagId: string) {
  if (!e.dataTransfer) return
  e.dataTransfer.effectAllowed = 'copy'
  e.dataTransfer.setData('text/tourgaze-tag', tagId)
  // A second copy with a generic MIME so foreign drop zones don't ghost-accept.
  e.dataTransfer.setData('text/plain', `tag:${tagId}`)
}

// Collect this node + all transitive descendants, used by `*` (expand-all).
function collectDescendants(n: TagNode, out: Set<string>) {
  out.add(n.id!)
  for (const c of n.children) collectDescendants(c, out)
}

// Type-ahead navigation: typing letters within ~800ms jumps to the next row
// whose name starts with the buffer (matros / Windows Explorer style).
let typeBuffer = ''
let typeTimer: number | null = null
function pushType(ch: string) {
  typeBuffer += ch.toLowerCase()
  if (typeTimer != null) window.clearTimeout(typeTimer)
  typeTimer = window.setTimeout(() => { typeBuffer = '' }, 800)
}

function onKeydown(e: KeyboardEvent) {
  if (!flatRows.value.length) return
  const target = e.target as HTMLElement | null
  if (target && /^(INPUT|SELECT|TEXTAREA)$/.test(target.tagName)) return

  const rows = flatRows.value
  const curIdx = rows.findIndex(r => r.node.id === selectedId.value)

  switch (e.key) {
    case 'ArrowDown':
      e.preventDefault()
      selectedId.value = rows[Math.min(rows.length - 1, curIdx < 0 ? 0 : curIdx + 1)].node.id!
      scrollSelectedIntoView()
      break
    case 'ArrowUp':
      e.preventDefault()
      selectedId.value = rows[Math.max(0, curIdx <= 0 ? 0 : curIdx - 1)].node.id!
      scrollSelectedIntoView()
      break
    case 'Home':
      e.preventDefault()
      selectedId.value = rows[0].node.id!
      scrollSelectedIntoView()
      break
    case 'End':
      e.preventDefault()
      selectedId.value = rows[rows.length - 1].node.id!
      scrollSelectedIntoView()
      break
    case 'ArrowRight':
      e.preventDefault()
      if (curIdx < 0) { selectedId.value = rows[0].node.id!; scrollSelectedIntoView(); break }
      {
        const row = rows[curIdx]
        if (row.node.children.length) {
          if (!expanded.value.has(row.node.id!)) {
            expanded.value.add(row.node.id!)
            expanded.value = new Set(expanded.value)
          } else if (rows[curIdx + 1]) {
            // already open → step into first child
            selectedId.value = rows[curIdx + 1].node.id!
            scrollSelectedIntoView()
          }
        }
      }
      break
    case 'ArrowLeft':
      e.preventDefault()
      if (curIdx < 0) break
      {
        const row = rows[curIdx]
        if (row.node.children.length && expanded.value.has(row.node.id!)) {
          expanded.value.delete(row.node.id!)
          expanded.value = new Set(expanded.value)
        } else if (row.depth > 0) {
          // jump to parent row (first row above us at a lower depth)
          for (let i = curIdx - 1; i >= 0; i--) {
            if (rows[i].depth < row.depth) {
              selectedId.value = rows[i].node.id!
              scrollSelectedIntoView()
              break
            }
          }
        }
      }
      break
    case '*':
      // matros convention: '*' on a node expands the whole sub-tree.
      e.preventDefault()
      if (curIdx >= 0) {
        const ids = new Set<string>()
        collectDescendants(rows[curIdx].node, ids)
        const next = new Set(expanded.value)
        for (const id of ids) next.add(id)
        expanded.value = next
      }
      break
    case 'Enter':
      // pick → activate (filter); manage → edit.
      if (selectedId.value) { e.preventDefault(); activateById(selectedId.value) }
      break
    case 'F2':
      if (props.mode === 'manage' && selectedId.value) { e.preventDefault(); openEdit(selectedId.value) }
      break
    case 'Delete':
      // Open the editor (which has the confirm-delete flow). Manage mode only.
      if (props.mode === 'manage' && selectedId.value) { e.preventDefault(); openEdit(selectedId.value) }
      break
    default:
      // Type-ahead: single printable character jumps to the next matching row.
      if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey && /\S/.test(e.key)) {
        pushType(e.key)
        const startFrom = (curIdx + 1) % rows.length
        for (let i = 0; i < rows.length; i++) {
          const r = rows[(startFrom + i) % rows.length]
          if ((r.node.name ?? '').toLowerCase().startsWith(typeBuffer)) {
            selectedId.value = r.node.id!
            scrollSelectedIntoView()
            e.preventDefault()
            break
          }
        }
      }
      break
  }
}

type FlatRow = { node: TagNode; depth: number }
const flatRows = computed<FlatRow[]>(() => {
  const q = filterText.value.trim().toLowerCase()
  const out: FlatRow[] = []
  const matches = (n: TagNode) => !q || (n.name ?? '').toLowerCase().includes(q)
  const subtreeHasMatch = (n: TagNode): boolean => matches(n) || n.children.some(subtreeHasMatch)
  const walk = (ns: TagNode[], depth: number) => {
    for (const n of ns) {
      if (q && !subtreeHasMatch(n)) continue
      out.push({ node: n, depth })
      // While filtering, force every branch open so deep matches stay visible.
      const open = q ? true : expanded.value.has(n.id!)
      if (n.children.length && open) walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

// ── Header actions (manage mode) ─────────────────────────────────────────────
function editSelected() { if (selectedId.value) openEdit(selectedId.value) }
function addUnderSelection() {
  if (selectedId.value) openNewChild(selectedId.value)
  else openNewRoot()
}
function refreshTags() { qc.invalidateQueries({ queryKey: ['tags'] }) }
</script>

<template>
  <div ref="rootEl" tabindex="0" class="space-y-2 outline-none" @keydown="onKeydown">
    <!-- Pane header: title · actions (refresh / edit / add) -->
    <div class="flex items-center gap-2 pb-1 border-b border-border">
      <span class="text-[11px] font-semibold uppercase tracking-wide text-muted-fg">Tags</span>
      <span class="text-[10px] text-muted-fg">
        {{ tree.length }} root{{ tree.length === 1 ? '' : 's' }} · {{ tags?.length ?? 0 }} total
      </span>
      <button class="btn-icon ml-auto" :class="showHint ? 'text-primary' : ''"
        title="Keyboard shortcuts" @click="showHint = !showHint"><Keyboard :size="13" /></button>
      <div v-if="mode === 'manage'" class="flex items-center gap-0.5">
        <button class="btn-icon" title="Refresh" @click="refreshTags"><RefreshCw :size="13" /></button>
        <button class="btn-icon disabled:opacity-30" title="Edit selected — F2"
          :disabled="!selectedId" @click="editSelected"><Pencil :size="13" /></button>
        <button class="btn-icon" title="New root tag" @click="openNewRoot"><FolderPlus :size="13" /></button>
        <button class="btn-icon text-primary" title="Add (child of selection, or root)" @click="addUnderSelection">
          <Plus :size="15" stroke-width="2.5" />
        </button>
      </div>
    </div>

    <!-- Filter box -->
    <div class="relative">
      <Search :size="12" class="absolute left-2 top-1/2 -translate-y-1/2 text-muted-fg pointer-events-none" />
      <input
        v-model="filterText"
        type="search"
        placeholder="Filter tags…"
        class="w-full pl-7 pr-7 py-1 rounded border border-border bg-transparent text-[11px] focus:border-primary focus:outline-none"
      />
      <button v-if="filterText"
        class="absolute right-1.5 top-1/2 -translate-y-1/2 text-muted-fg hover:text-foreground"
        title="Clear filter" @click="filterText = ''">
        <X :size="11" />
      </button>
    </div>

    <!-- Inline create form (manage mode, shows only while you're adding) -->
    <form v-if="formOpen && mode === 'manage'"
      class="p-2 border border-primary bg-primary/5 rounded flex flex-wrap items-center gap-2 text-xs"
      @submit.prevent="createMut.mutate()">
      <span class="text-[10px] text-muted-fg">
        {{ formParentId
          ? 'Adding child of: ' + (flatForSelect.find(o => o.id === formParentId)?.label.trim() ?? '…')
          : 'Adding as a new top-level root' }}
      </span>
      <input v-model="newName" required autofocus placeholder="Tag name"
        class="flex-1 min-w-[120px] px-2 py-1 rounded border border-border bg-background focus:outline-none focus:border-primary" />
      <select v-model="formParentId"
        class="px-2 py-1 rounded border border-border bg-background"
        title="Where to put it. Pick — Root — for a new top-level category.">
        <option :value="null">— Root —</option>
        <option v-for="o in flatForSelect" :key="o.id" :value="o.id">{{ o.label }}</option>
      </select>
      <input v-model="newColor" type="color" class="w-7 h-7 rounded border border-border" />
      <div class="w-32"><IconPicker v-model="newIcon" /></div>
      <button type="submit" :disabled="createMut.isPending.value || !newName.trim()"
        class="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-primary text-primary-fg font-medium disabled:opacity-50">
        <Plus :size="11" /> Add
      </button>
      <button type="button" class="text-muted-fg hover:text-foreground px-1" @click="formOpen = false">Cancel</button>
    </form>

    <!-- Tree -->
    <div v-if="isPending" class="text-xs text-muted-fg">Loading tags…</div>
    <div v-else-if="!tree.length" class="text-xs text-muted-fg opacity-60 px-2 py-3">
      No tags yet.
      <template v-if="mode === 'manage'">
        Click <strong>New root</strong> above to create your first one (e.g. <code>People</code>, <code>Pass</code>, <code>Conditions</code>).
      </template>
      <template v-else>Create some in Settings → Tags.</template>
    </div>
    <div v-else>
      <p v-if="showHint" class="text-[10px] text-muted-fg px-1 mb-1">
        <kbd class="px-1 rounded bg-muted/40 border border-border">↑↓</kbd> move ·
        <kbd class="px-1 rounded bg-muted/40 border border-border">→←</kbd> open/close ·
        <kbd class="px-1 rounded bg-muted/40 border border-border">*</kbd> expand all ·
        type to search ·
        <template v-if="mode === 'pick'">
          <kbd class="px-1 rounded bg-muted/40 border border-border">↵</kbd> / dbl-click filter
        </template>
        <template v-else>
          <kbd class="px-1 rounded bg-muted/40 border border-border">F2</kbd> edit
        </template>
      </p>
      <ul class="space-y-0.5">
      <li v-for="row in flatRows" :key="row.node.id!"
        :data-tag-row-id="row.node.id!"
        :draggable="draggable"
        class="flex items-center gap-1.5 py-1 px-2 rounded text-sm group transition-colors border-l-2"
        :class="[
          selectedId === row.node.id
            ? 'bg-primary/10 border-l-primary text-foreground'
            : 'border-l-transparent hover:bg-muted/30',
          draggable ? 'cursor-grab active:cursor-grabbing select-none' : 'cursor-pointer',
        ]"
        :style="{ paddingLeft: (8 + row.depth * 14) + 'px' }"
        :title="mode === 'pick' ? `Double-click or Enter to filter by: ${row.node.name}` : undefined"
        @click="selectRow(row.node.id!)"
        @dblclick="activateRow(row.node)"
        @dragstart="draggable ? onDragStart($event, row.node.id!) : undefined">
        <GripVertical v-if="draggable" :size="10" class="text-muted-fg opacity-50 shrink-0" />
        <button v-if="row.node.children.length"
          class="w-4 h-4 flex items-center justify-center text-muted-fg"
          @click.stop="toggle(row.node.id!)">
          <component :is="expanded.has(row.node.id!) ? ChevronDown : ChevronRight" :size="12" />
        </button>
        <span v-else class="w-4 h-4" />

        <!-- Icon (tinted by the tag colour) when set; otherwise the colour swatch -->
        <DynamicIcon v-if="row.node.icon" :name="row.node.icon" :size="14"
          class="flex-shrink-0" :style="{ color: row.node.color || '#9ca3af' }" />
        <span v-else class="inline-block w-2.5 h-2.5 rounded-sm flex-shrink-0"
          :style="{ backgroundColor: row.node.color || '#9ca3af' }" />

        <span class="flex-1 truncate" :class="selectedId === row.node.id ? 'font-medium' : ''">
          {{ row.node.name }}
        </span>

        <span v-if="!row.node.parentId" class="text-[9px] uppercase tracking-wide text-muted-fg/70 mr-1">root</span>

        <template v-if="mode === 'manage'">
          <button class="btn-icon focus-visible:opacity-100"
            :class="selectedId === row.node.id ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'"
            title="Add child tag"
            @click.stop="openNewChild(row.node.id!)">
            <Plus :size="12" />
          </button>
          <button class="btn-icon focus-visible:opacity-100"
            :class="selectedId === row.node.id ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'"
            title="Edit tag (rename, recolor, change parent, delete) — F2 / Enter"
            @click.stop="openEdit(row.node.id!)">
            <Pencil :size="12" />
          </button>
        </template>
      </li>
      </ul>
    </div>

    <TagEditDialog v-if="mode === 'manage'" :tag-id="editingTagId" @close="closeEdit" />
  </div>
</template>
