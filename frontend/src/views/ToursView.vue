<script setup lang="ts">
import { fmtDuration } from '@/lib/format'
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Splitpanes, Pane } from 'splitpanes'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import {
  getActivities, getTags, addTagToActivity, getGear,
  getFilterPresets, createFilterPreset, updateFilterPreset, deleteFilterPreset,
  searchPlaces,
  type ActivitySummary, type Tag, type ActivityType,
} from '@/api/client'
import { type GeoBox } from '@/composables/useTourSearch'
import {
  Bike, PersonStanding, Waves, Footprints, Mountain, Activity as ActivityIcon,
  Filter, ChevronDown, ChevronRight, Ruler, Timer, Tag as TagIcon,
  PanelLeftClose, PanelLeftOpen, X, Pencil, Map as MapIcon,
  Bookmark, Save, Trash2, RotateCcw,
} from 'lucide-vue-next'
import { weatherIcon, weatherColor } from '@/composables/weatherIcon'
import { TOURS_LAYOUT_SLOT, autoLayoutRef, registerLayoutSaver, LayoutSlot } from '@/composables/useLayoutState'
import { useTourSearch } from '@/composables/useTourSearch'
import ActivityViewer from '@/components/ActivityViewer.vue'
import EditTourPanel from '@/components/EditTourPanel.vue'
import TagTree from '@/components/TagTree.vue'
import TagTreeSelect from '@/components/TagTreeSelect.vue'
import ToursSearchBar from '@/components/ToursSearchBar.vue'

const qc = useQueryClient()

const route = useRoute()
const router = useRouter()
const { data: activities } = useQuery({ queryKey: ['activities'], queryFn: getActivities })
const { data: tags } = useQuery({ queryKey: ['tags'], queryFn: getTags })
const { data: gearList } = useQuery({ queryKey: ['gear'], queryFn: () => getGear() })

// ── Selection (URL-driven, deep-linkable) ───────────────────────────────────
const selectedId = computed<string | null>(() => {
  const q = route.query.id
  return typeof q === 'string' && q.length > 0 ? q : null
})
function openActivity(id: string | undefined | null) {
  if (!id) return
  rightMode.value = 'view'
  router.replace({ path: '/tours', query: { id } })
}
const selectedName = computed(() =>
  (activities.value ?? []).find(a => a.id === selectedId.value)?.name ?? '')

// ── Drag-drop: tag from the tag tree onto an activity row ───────────────────
const dropHoverId = ref<string | null>(null)
function onActivityDragOver(e: DragEvent, activityId: string) {
  if (!e.dataTransfer?.types.includes('text/tourgaze-tag')) return
  e.preventDefault()
  e.dataTransfer.dropEffect = 'copy'
  dropHoverId.value = activityId
}
function onActivityDragLeave(activityId: string) {
  if (dropHoverId.value === activityId) dropHoverId.value = null
}
async function onActivityDrop(e: DragEvent, activity: ActivitySummary) {
  e.preventDefault()
  dropHoverId.value = null
  const tagId = e.dataTransfer?.getData('text/tourgaze-tag')
  if (!tagId || !activity.id) return
  try {
    await addTagToActivity(activity.id, tagId, activity.tagIds ?? [])
    qc.invalidateQueries({ queryKey: ['activities'] })
    const tag = (tags.value ?? []).find(t => t.id === tagId)
    push.success({
      title: 'Tag applied',
      message: `${tag?.name ?? 'Tag'} → ${activity.name ?? 'tour'}`,
    })
  } catch {
    push.error('Could not apply tag')
  }
}

// View | Edit toggle for the right pane.
const rightMode = ref<'view' | 'edit'>('view')
function startEdit() { if (selectedId.value) rightMode.value = 'edit' }
function endEdit() { rightMode.value = 'view' }

// ── Keyboard navigation ────────────────────────────────────────────────────
// Build a flat ordered list of selectable activities matching what the tree
// renders (filtered + grouped order). Up/Down move selection, Enter opens
// edit mode, Left/Right collapse/expand the current group.
const flatActivities = computed<ActivitySummary[]>(() => {
  const out: ActivitySummary[] = []
  const walk = (groups: GroupNode[]) => {
    for (const g of groups) {
      if (!expanded.value.has(g.key)) continue
      if (g.children?.length) walk(g.children)
      else out.push(...g.activities)
    }
  }
  walk(grouped.value)
  return out
})

function onKeydown(e: KeyboardEvent) {
  // Don't hijack typing in inputs / selects.
  const target = e.target as HTMLElement | null
  if (target && /^(INPUT|SELECT|TEXTAREA)$/.test(target.tagName)) return

  const list = flatActivities.value
  if (!list.length) return
  const curIdx = list.findIndex(a => a.id === selectedId.value)

  if (e.key === 'ArrowDown') {
    e.preventDefault()
    const next = list[Math.min(list.length - 1, curIdx < 0 ? 0 : curIdx + 1)]
    openActivity(next?.id)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    const next = list[Math.max(0, curIdx <= 0 ? 0 : curIdx - 1)]
    openActivity(next?.id)
  } else if (e.key === 'Enter' && selectedId.value) {
    e.preventDefault()
    startEdit()
  } else if (e.key === 'Escape' && rightMode.value === 'edit') {
    e.preventDefault()
    endEdit()
  }
}

import { onMounted, onUnmounted } from 'vue'
onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

// ── Persisted layout state ──────────────────────────────────────────────────
// auto-persist: the separator position / collapse survive without needing the
// explicit Save-layout click (Save still snapshots them too).
const leftCollapsed = autoLayoutRef<boolean>(TOURS_LAYOUT_SLOT, 'leftCollapsed', false)
const leftSize = autoLayoutRef<number>(TOURS_LAYOUT_SLOT, 'leftSize', 28)
// The left pane is two foldable areas: Filter & grouping (top) and Results
// (bottom). Each remembers its open/closed state.
const filterOpen = autoLayoutRef<boolean>(TOURS_LAYOUT_SLOT, 'filterOpen', true)
const resultsOpen = autoLayoutRef<boolean>(TOURS_LAYOUT_SLOT, 'resultsOpen', true)
const tagDockOpen = autoLayoutRef<boolean>(TOURS_LAYOUT_SLOT, 'tagDockOpen', false)
const gearDockOpen = autoLayoutRef<boolean>(TOURS_LAYOUT_SLOT, 'gearDockOpen', false)

// ── Grouping (also persistable) ─────────────────────────────────────────────
// Filtering itself is the faceted search bar — see `search` below.
type GroupBy = 'year-month' | 'sport' | 'year' | 'tag-then-year' | 'tag-children'
const groupBy = autoLayoutRef<GroupBy>(TOURS_LAYOUT_SLOT, 'groupBy', 'year-month')
let didAutoExpand = false // reset when grouping mode changes so the new tree opens its first bucket
// Ad-hoc: when groupBy = 'tag-children', this is the parent tag whose
// children buckets activities (e.g. "Pass" → Gambenpass, Stelvio, ...).
const groupTagId = autoLayoutRef<string>(TOURS_LAYOUT_SLOT, 'groupTagId', '')

const groupPills: { value: GroupBy; label: string }[] = [
  { value: 'year-month',    label: 'Year › Month' },
  { value: 'year',          label: 'Year' },
  { value: 'sport',         label: 'Sport' },
  { value: 'tag-then-year', label: 'Tag › Year' },
]
function selectPillMode(v: GroupBy) {
  groupBy.value = v
  groupTagId.value = ''
}

// Drag-drop: drop a tag from the tag tree onto the group-by zone to
// pivot the tree to that tag's children (ad-hoc dynamic grouping).
const groupDropHover = ref(false)
function onGroupDragOver(e: DragEvent) {
  if (!e.dataTransfer?.types.includes('text/tourgaze-tag')) return
  e.preventDefault()
  e.dataTransfer.dropEffect = 'copy'
  groupDropHover.value = true
}
function onGroupDragLeave() { groupDropHover.value = false }
function onGroupDrop(e: DragEvent) {
  e.preventDefault()
  groupDropHover.value = false
  const tagId = e.dataTransfer?.getData('text/tourgaze-tag')
  if (!tagId) return
  groupTagId.value = tagId
  groupBy.value = 'tag-children'
  didAutoExpand = false // re-trigger first-bucket open on the new tree
}
function clearGroupTag() {
  groupTagId.value = ''
  groupBy.value = 'year-month'
}
// Textbox/tree picker for the same ad-hoc grouping (keyboard/click alternative
// to dragging a tag onto the drop zone).
function setGroupTag(id: string) {
  if (!id) { clearGroupTag(); return }
  groupTagId.value = id
  groupBy.value = 'tag-children'
  didAutoExpand = false // re-trigger first-bucket open on the new tree
}

// ── Splitpane size tracking ─────────────────────────────────────────────────
// Splitpanes' @resize fires while the user drags; we record the latest expanded
// size so it persists (autoLayoutRef) and the unfold action can restore it.
// splitpanes v4 emits an OBJECT { panes: [...] }, older builds emit the array
// directly — accept either so the size is actually captured.
type ResizePayload = { panes?: Array<{ size: number }> }
function panesOf(e: ResizePayload | Array<{ size: number }>): Array<{ size: number }> {
  return Array.isArray(e) ? e : (e?.panes ?? [])
}
function onSplitResize(e: ResizePayload | Array<{ size: number }>) {
  // panes[0] is the left tree pane.
  const panes = panesOf(e)
  if (!leftCollapsed.value && panes[0]?.size != null) {
    leftSize.value = panes[0].size
  }
}

function toggleLeftPane() {
  leftCollapsed.value = !leftCollapsed.value
}

function saveLayout() {
  TOURS_LAYOUT_SLOT.save({
    leftCollapsed: leftCollapsed.value,
    leftSize: leftSize.value,
    groupBy: groupBy.value,
    groupTagId: groupTagId.value,
    tagDockOpen: tagDockOpen.value,
    gearDockOpen: gearDockOpen.value,
    query: search.serialize(),
  })
  push.success({ title: 'Tours layout saved', message: 'Pane size, search and grouping will be restored next time.' })
}

// Promote saveLayout to the global header. Cleared automatically on unmount.
let unregisterSaver: (() => void) | null = null
onMounted(() => { unregisterSaver = registerLayoutSaver('Tours', saveLayout) })
onUnmounted(() => { unregisterSaver?.(); unregisterSaver = null })

// ── Derived data ────────────────────────────────────────────────────────────
const tagsList = computed<Tag[]>(() => (tags.value ?? []) as Tag[])
const tagsById = computed(() => new Map(tagsList.value.map(t => [t.id!, t])))

// ── Faceted search (JIRA/Lucene-style) ──────────────────────────────────────
// `near:<place>` boxes, resolved lazily by forward-geocoding each place once.
const geoBoxes = ref<Map<string, GeoBox | null>>(new Map())
const search = useTourSearch({ activities, tags: tagsList, geoBoxes })

// Geocode any new `near:` place → its bounding box (the place's real extent;
// fall back to a ~22 km box around the point for coordinate-only hits). Cached
// per place so a chip toggle doesn't re-hit Nominatim.
watch(() => search.nearTerms.value, async (terms) => {
  for (const place of terms) {
    if (geoBoxes.value.has(place)) continue
    geoBoxes.value.set(place, null)   // mark in-flight (fail-open until resolved)
    try {
      const hit = (await searchPlaces(place))[0]
      let box: GeoBox | null = null
      if (hit?.south != null && hit.north != null && hit.west != null && hit.east != null) {
        box = { south: hit.south, north: hit.north, west: hit.west, east: hit.east }
      } else if (hit?.lat != null && hit.lon != null) {
        const R = 0.2
        box = { south: hit.lat - R, north: hit.lat + R, west: hit.lon - R, east: hit.lon + R }
      }
      // Replace the map (new ref identity) so the matches() computed recomputes.
      geoBoxes.value = new Map(geoBoxes.value).set(place, box)
    } catch {
      geoBoxes.value = new Map(geoBoxes.value).set(place, null)
    }
  }
}, { immediate: true })
// Seed the bar from the persisted layout query (load() runs once on mount).
search.load(TOURS_LAYOUT_SLOT.load()?.query ?? '')
// Auto-persist the filter so it survives a reload (no need to hit "Save layout").
watch(() => search.serialize(), (q) => {
  // merge-save just the query field (cast to the loose slot type, like autoLayoutRef)
  ;(TOURS_LAYOUT_SLOT as LayoutSlot<Record<string, unknown>>).save({ ...(TOURS_LAYOUT_SLOT.load() ?? {}), query: q })
})

// Double-click / Enter on a tag in the tree → add it as a WHERE condition.
// Matching is transitive (a parent tag matches all its descendants), handled
// in useTourSearch's tag evaluation.
function onTagActivate(tag: Tag) {
  search.addTagFilter(tag.name ?? '')
}

// ── Keyboard: jump from the search bar into the results, then ↑/↓ through them ─
const resultsEl = ref<HTMLElement | null>(null)
function resultButtons(): HTMLElement[] {
  return Array.from(resultsEl.value?.querySelectorAll<HTMLElement>('button') ?? [])
}
function focusFirstResult() {
  resultButtons()[0]?.focus()
}
function onResultsKeydown(e: KeyboardEvent) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
  const btns = resultButtons()
  if (!btns.length) return
  e.preventDefault()
  const i = btns.indexOf(document.activeElement as HTMLElement)
  const next = e.key === 'ArrowDown'
    ? Math.min(btns.length - 1, (i < 0 ? -1 : i) + 1)
    : Math.max(0, (i < 0 ? 0 : i) - 1)
  btns[next]?.focus()
}

// Gear dock: picking a gear adds a `gear:<name>` filter to the search.
// It NEVER writes to ride metadata — it only narrows what's shown.
function onGearPick(name: string) {
  if (name) search.addGearFilter(name)
}

const filtered = computed<ActivitySummary[]>(() => {
  const pred = search.matches.value
  return (activities.value ?? []).filter(pred)
})

// ── Saved presets (named, server-persisted "perspectives") ───────────────────
const { data: presets } = useQuery({ queryKey: ['filter-presets'], queryFn: getFilterPresets })
const selectedPresetId = ref('')

function applyPreset(id: string) {
  selectedPresetId.value = id
  if (!id) return
  const p = (presets.value ?? []).find(x => x.id === id)
  if (!p) return
  search.load(p.query ?? '')
  if (p.groupBy) {
    groupBy.value = p.groupBy as GroupBy
    // Restore the ad-hoc parent tag for tag-children grouping; clear it for
    // every other mode so a stale parent can't leak across presets.
    groupTagId.value = p.groupBy === 'tag-children' ? (p.groupTagId ?? '') : ''
  }
  didAutoExpand = false
}

async function savePreset() {
  const existing = (presets.value ?? []).find(x => x.id === selectedPresetId.value)
  const suggested = existing?.name ?? ''
  const name = window.prompt('Save search as — name:', suggested)?.trim()
  if (!name) return
  const body = {
    name,
    query: search.serialize(),
    groupBy: groupBy.value,
    // Only meaningful for tag-children; omitted (→ null server-side) otherwise.
    groupTagId: groupBy.value === 'tag-children' ? groupTagId.value : undefined,
  }
  try {
    // Overwrite when the typed name matches the loaded preset; else create new.
    const overwrite = existing && existing.name === name ? existing : null
    const saved = overwrite
      ? await updateFilterPreset(overwrite.id!, body)
      : await createFilterPreset(body)
    await qc.invalidateQueries({ queryKey: ['filter-presets'] })
    selectedPresetId.value = saved.id ?? ''
    push.success({ title: overwrite ? 'Preset updated' : 'Preset saved', message: name })
  } catch {
    push.error('Could not save preset')
  }
}

async function removePreset() {
  const p = (presets.value ?? []).find(x => x.id === selectedPresetId.value)
  if (!p) return
  if (!window.confirm(`Delete preset “${p.name}”?`)) return
  try {
    await deleteFilterPreset(p.id!)
    await qc.invalidateQueries({ queryKey: ['filter-presets'] })
    selectedPresetId.value = ''
    push.success({ title: 'Preset deleted', message: p.name ?? '' })
  } catch {
    push.error('Could not delete preset')
  }
}

// ── Grouped tree ────────────────────────────────────────────────────────────
type GroupNode = { key: string; label: string; activities: ActivitySummary[]; children?: GroupNode[]; count: number; km?: number }
const monthNames = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']

const groupedRaw = computed<GroupNode[]>(() => {
  const list = filtered.value

  if (groupBy.value === 'year-month') {
    const byYear = new Map<string, Map<string, ActivitySummary[]>>()
    for (const a of list) {
      const t = a.startTime ? new Date(a.startTime) : null
      const y = t ? String(t.getFullYear()) : 'No date'
      const m = t ? String(t.getMonth()) : '—'
      if (!byYear.has(y)) byYear.set(y, new Map())
      const inner = byYear.get(y)!
      if (!inner.has(m)) inner.set(m, [])
      inner.get(m)!.push(a)
    }
    return Array.from(byYear.entries())
      .sort(([a], [b]) => b.localeCompare(a))
      .map(([year, inner]) => {
        const children = Array.from(inner.entries())
          .sort(([a], [b]) => Number(b) - Number(a))
          .map(([mIdx, acts]) => ({
            key: `${year}-${mIdx}`,
            label: mIdx === '—' ? 'Unknown' : monthNames[Number(mIdx)],
            activities: acts.sort((a, b) => (b.startTime ?? '').localeCompare(a.startTime ?? '')),
            count: acts.length,
          }))
        return { key: year, label: year, activities: [], children, count: children.reduce((s, c) => s + c.count, 0) }
      })
  }

  if (groupBy.value === 'year') {
    const byYear = new Map<string, ActivitySummary[]>()
    for (const a of list) {
      const y = a.startTime ? String(new Date(a.startTime).getFullYear()) : 'No date'
      if (!byYear.has(y)) byYear.set(y, [])
      byYear.get(y)!.push(a)
    }
    return Array.from(byYear.entries())
      .sort(([a], [b]) => b.localeCompare(a))
      .map(([y, acts]) => ({ key: y, label: y, activities: acts, count: acts.length }))
  }

  if (groupBy.value === 'sport') {
    const bySport = new Map<string, ActivitySummary[]>()
    for (const a of list) {
      const s = a.activityType ?? 'Other'
      if (!bySport.has(s)) bySport.set(s, [])
      bySport.get(s)!.push(a)
    }
    return Array.from(bySport.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([s, acts]) => ({ key: s, label: s, activities: acts, count: acts.length }))
  }

  if (groupBy.value === 'tag-children') {
    // Ad-hoc dynamic grouping: drop a parent tag (e.g. "Pass") and we bucket
    // activities by which of its children they carry (Gambenpass, Stelvio, ...).
    const parent = tagsById.value.get(groupTagId.value)
    if (!parent) {
      return [{ key: '__nogroup__', label: 'Drop a tag onto "Group by" →', activities: list, count: list.length }]
    }
    const childTags = tagsList.value.filter(t => t.parentId === parent.id)
    const byChild = new Map<string, ActivitySummary[]>()
    for (const c of childTags) byChild.set(c.id!, [])
    const other: ActivitySummary[] = []
    for (const a of list) {
      const ids = new Set(a.tagIds ?? [])
      const matched = childTags.filter(c => ids.has(c.id!))
      if (matched.length === 0) {
        // Activity has the parent tag but no child — or no relation at all.
        if (ids.has(parent.id!)) other.push(a)
      } else {
        for (const c of matched) byChild.get(c.id!)!.push(a)
      }
    }
    const children = childTags
      .map(c => ({
        key: `tc-${c.id}`,
        label: c.name ?? '(unnamed)',
        activities: byChild.get(c.id!)!.sort((a, b) => (b.startTime ?? '').localeCompare(a.startTime ?? '')),
        count: byChild.get(c.id!)!.length,
      }))
      .filter(g => g.count > 0)
      .sort((a, b) => a.label.localeCompare(b.label))
    if (other.length) {
      children.push({
        key: `tc-${parent.id}-other`,
        label: '(no sub-tag)',
        activities: other.sort((a, b) => (b.startTime ?? '').localeCompare(a.startTime ?? '')),
        count: other.length,
      })
    }
    return [{
      key: `tc-${parent.id}-root`,
      label: parent.name ?? '(tag)',
      activities: [],
      children,
      count: children.reduce((s, c) => s + c.count, 0),
    }]
  }

  // tag-then-year
  const buckets = new Map<string, Map<string, ActivitySummary[]>>()
  for (const a of list) {
    const ids = (a.tagIds ?? [])
    const tagKeys = ids.length ? ids : ['__untagged__']
    const y = a.startTime ? String(new Date(a.startTime).getFullYear()) : 'No date'
    for (const tid of tagKeys) {
      if (!buckets.has(tid)) buckets.set(tid, new Map())
      const inner = buckets.get(tid)!
      if (!inner.has(y)) inner.set(y, [])
      inner.get(y)!.push(a)
    }
  }
  return Array.from(buckets.entries())
    .map(([tid, years]) => {
      const label = tid === '__untagged__' ? '(Untagged)' : (tagsById.value.get(tid)?.name ?? '(unknown)')
      const children = Array.from(years.entries())
        .sort(([a], [b]) => b.localeCompare(a))
        .map(([y, acts]) => ({ key: `${tid}-${y}`, label: y, activities: acts, count: acts.length }))
      return { key: tid, label, activities: [], children, count: children.reduce((s, c) => s + c.count, 0) }
    })
    .sort((a, b) => a.label.localeCompare(b.label))
})

// Annotate every node with the summed distance of its rides (own + descendants)
// so each group header can show e.g. "2026 · 100 km".
function sumKm(n: GroupNode): number {
  const own = n.activities.reduce((s, a) => s + (a.distanceKm ?? 0), 0)
  const kids = (n.children ?? []).reduce((s, c) => s + sumKm(c), 0)
  return own + kids
}
function annotateKm(nodes: GroupNode[]): GroupNode[] {
  return nodes.map(n => ({
    ...n,
    km: sumKm(n),
    children: n.children ? annotateKm(n.children) : undefined,
  }))
}
const grouped = computed<GroupNode[]>(() => annotateKm(groupedRaw.value))

function fmtGroupKm(km: number | null | undefined) {
  if (!km) return ''
  return `${Math.round(km)} km`
}

// ── Expand state (session only — not persisted) ─────────────────────────────
const expanded = ref(new Set<string>())
function toggle(key: string) {
  if (expanded.value.has(key)) expanded.value.delete(key)
  else expanded.value.add(key)
  expanded.value = new Set(expanded.value)
}

watch(grouped, (g) => {
  if (didAutoExpand || !g.length) return
  didAutoExpand = true
  const next = new Set(expanded.value)
  next.add(g[0].key)
  if (g[0].children?.[0]) next.add(g[0].children[0].key)
  expanded.value = next
}, { immediate: true })

// The very first activity in the rendered tree (first open bucket's first ride).
const firstResultActivity = computed<ActivitySummary | null>(() => {
  const g = grouped.value
  if (!g.length) return null
  const top = g[0]
  if (top.children?.length) {
    const sub = top.children.find(c => c.activities.length) ?? top.children[0]
    return sub?.activities?.[0] ?? null
  }
  return top.activities?.[0] ?? null
})

// Elite-UX filtering: whenever the faceted query changes, open the top result
// bucket and preview the first match. Driven off the serialized query so it
// fires on every facet/text change but not on unrelated re-renders. Uses
// router.replace (no history spam) and never steals focus from the search box.
watch(() => search.serialize(), () => {
  nextTick(() => {
    const g = grouped.value
    if (g.length) {
      const next = new Set(expanded.value)
      next.add(g[0].key)
      if (g[0].children?.[0]) next.add(g[0].children[0].key)
      expanded.value = next
    }
    const first = firstResultActivity.value
    if (first?.id && first.id !== selectedId.value) openActivity(first.id)
  })
})

function sportIcon(t: ActivityType | null | undefined) {
  switch (t) {
    case 'cycling': return Bike
    case 'running': return PersonStanding
    case 'swimming': return Waves
    case 'walking': return Footprints
    case 'hiking': return Mountain
    default: return ActivityIcon
  }
}
function fmtDate(iso: string | null | undefined) {
  if (!iso) return ''
  return new Date(iso).toLocaleDateString(undefined, { day: '2-digit', month: 'short' })
}
function fmtDist(km: number | null | undefined) {
  if (km == null) return ''
  return km >= 1 ? `${km.toFixed(1)} km` : `${Math.round(km * 1000)} m`
}

// Computed Pane size so we can collapse/expand without unmounting the splitpane.
const effectiveLeftSize = computed(() => leftCollapsed.value ? 2.4 : leftSize.value)
</script>

<template>
  <Splitpanes class="h-full w-full" @resize="onSplitResize">

    <!-- ── LEFT pane — collapses to a thin handle strip ──────────────────── -->
    <Pane :size="effectiveLeftSize" :min-size="2.4" :max-size="60"
      class="flex flex-col bg-background border-r border-border overflow-hidden">

      <!-- Collapsed strip: only the unfold button -->
      <button v-if="leftCollapsed"
        class="h-full w-full flex items-start justify-center pt-3 text-muted-fg hover:text-foreground"
        title="Show filters &amp; tree"
        @click="toggleLeftPane">
        <PanelLeftOpen :size="16" />
      </button>

      <template v-else>
        <!-- Action bar: collapse + save layout -->
        <div class="flex items-center gap-1 px-2 py-1.5 border-b border-border">
          <button class="btn-icon" title="Hide filters &amp; tree" @click="toggleLeftPane"><PanelLeftClose :size="14" /></button>
          <span class="text-[10px] text-muted-fg font-semibold uppercase tracking-wide ml-1">Tours</span>
        </div>

        <!-- ── Foldable area 1 — Filter & grouping (incl. tags) ────────────── -->
        <button class="w-full flex items-center gap-1 px-2 py-1.5 border-b border-border hover:bg-muted/30 text-left text-[10px] font-semibold uppercase tracking-wide text-muted-fg"
          @click="filterOpen = !filterOpen">
          <component :is="filterOpen ? ChevronDown : ChevronRight" :size="11" />
          <Filter :size="11" /><span>Filter &amp; grouping</span>
        </button>

        <!-- Filter strip -->
        <div v-if="filterOpen" class="flex flex-col gap-1.5 p-2 border-b border-border bg-muted/10 text-[11px]">
          <!-- Saved presets ("perspectives", server-persisted) — kept at the top
               so a saved search (e.g. gear:hibike) is the first thing you reach. -->
          <div class="flex items-center gap-1">
            <Bookmark :size="11" class="text-muted-fg shrink-0" />
            <select
              :value="selectedPresetId"
              class="flex-1 min-w-0 px-1.5 py-0.5 rounded border border-border bg-transparent text-[11px]"
              title="Apply a saved search"
              @change="applyPreset(($event.target as HTMLSelectElement).value)"
            >
              <option value="">Saved searches…</option>
              <option v-for="p in (presets ?? [])" :key="p.id!" :value="p.id">{{ p.name }}</option>
            </select>
            <button class="btn-icon disabled:opacity-30" title="Restore — re-apply the selected saved search (discard unsaved edits)"
              :disabled="!selectedPresetId" @click="applyPreset(selectedPresetId)">
              <RotateCcw :size="13" />
            </button>
            <button class="btn-icon" title="Save current search as preset" @click="savePreset">
              <Save :size="13" />
            </button>
            <button class="btn-icon disabled:opacity-30" title="Delete selected preset"
              :disabled="!selectedPresetId" @click="removePreset">
              <Trash2 :size="13" />
            </button>
          </div>

          <!-- Faceted search — JIRA/Lucene-style key:value tokens + free text -->
          <ToursSearchBar :search="search" @focus-results="focusFirstResult" />

          <div class="mt-0.5 flex flex-col gap-1">
            <div class="flex items-center gap-1 flex-wrap">
              <span class="text-[10px] text-muted-fg mr-0.5">Group by</span>
              <button v-for="g in groupPills" :key="g.value" type="button"
                class="px-1.5 py-[1px] rounded text-[10px] border transition-colors"
                :class="groupBy === g.value
                  ? 'bg-primary/15 text-primary border-primary/40'
                  : 'border-border text-muted-fg hover:bg-muted/40 hover:text-foreground'"
                @click="selectPillMode(g.value)">
                {{ g.label }}
              </button>
            </div>
            <!-- Pick a parent tag by keyboard/click (alternative to dragging) -->
            <TagTreeSelect
              :model-value="groupBy === 'tag-children' ? groupTagId : ''"
              placeholder="Group by tag children…"
              @update:model-value="setGroupTag"
            />
            <!-- Tag drop zone — drag a parent tag from the dock to group by its children -->
            <div
              data-testid="group-drop-zone"
              class="flex items-center gap-1 px-1.5 py-1 rounded border border-dashed text-[10px] transition-colors"
              :class="groupDropHover
                ? 'border-primary bg-primary/10 text-primary'
                : groupBy === 'tag-children' && groupTagId
                  ? 'border-primary/50 bg-primary/5'
                  : 'border-border text-muted-fg'"
              @dragover="onGroupDragOver"
              @dragleave="onGroupDragLeave"
              @drop="onGroupDrop"
            >
              <TagIcon :size="10" class="shrink-0" />
              <template v-if="groupBy === 'tag-children' && groupTagId">
                <span
                  class="inline-block w-2 h-2 rounded-sm shrink-0"
                  :style="{ backgroundColor: tagsById.get(groupTagId)?.color || '#9ca3af' }"
                />
                <span class="truncate">by <b>{{ tagsById.get(groupTagId)?.name ?? '(unknown)' }}</b> children</span>
                <button type="button" class="ml-auto p-0.5 rounded hover:bg-muted/60"
                  title="Clear ad-hoc grouping" @click="clearGroupTag">
                  <X :size="10" />
                </button>
              </template>
              <template v-else>
                <span class="italic opacity-80">drop a parent tag here →&nbsp;ad-hoc subgroups</span>
              </template>
            </div>
          </div>
          <p class="text-[10px] text-muted-fg">{{ filtered.length }} {{ filtered.length === 1 ? 'tour' : 'tours' }}</p>
        </div>

        <!-- Tag tree — double-click / Enter to filter by a tag (recursive: a
             parent matches all its descendants); drag a tag onto a tour to
             apply it. Foldable, part of the Filter & grouping area. -->
        <div v-if="filterOpen" class="border-b border-border">
          <button class="w-full flex items-center gap-1 px-2 py-1 hover:bg-muted/30 text-left text-[10px] font-semibold uppercase tracking-wide text-muted-fg"
            @click="tagDockOpen = !tagDockOpen">
            <component :is="tagDockOpen ? ChevronDown : ChevronRight" :size="11" />
            <TagIcon :size="11" />
            <span>Tags</span>
            <span class="ml-auto text-[9px] normal-case font-normal opacity-70">dbl-click filters · drag onto a tour</span>
          </button>
          <div v-if="tagDockOpen" class="px-2 pb-2 max-h-72 overflow-y-auto">
            <TagTree mode="pick" :draggable="true" @activate="onTagActivate" />
          </div>
        </div>

        <!-- Gear — foldable. Picking a gear adds a `gear:<name>` filter to the
             search; it only narrows the list and never alters ride metadata. -->
        <div v-if="filterOpen && gearList && gearList.length" class="border-b border-border">
          <button class="w-full flex items-center gap-1 px-2 py-1 hover:bg-muted/30 text-left text-[10px] font-semibold uppercase tracking-wide text-muted-fg"
            @click="gearDockOpen = !gearDockOpen">
            <component :is="gearDockOpen ? ChevronDown : ChevronRight" :size="11" />
            <Bike :size="11" />
            <span>Gear</span>
            <span class="ml-auto text-[9px] normal-case font-normal opacity-70">click to filter by gear</span>
          </button>
          <div v-if="gearDockOpen" class="px-2 pb-2">
            <select
              :value="''"
              class="w-full text-[10px] rounded border border-border bg-transparent px-1 py-0.5 focus:border-primary focus:outline-none"
              title="Add a gear filter to the search"
              @change="onGearPick(($event.target as HTMLSelectElement).value)">
              <option value="">Filter by gear…</option>
              <option v-for="g in gearList" :key="g.id!" :value="g.name">{{ g.name }}<template v-if="g.type"> ({{ g.type }})</template></option>
            </select>
          </div>
        </div>

        <!-- ── Foldable area 2 — Results ───────────────────────────────────── -->
        <button class="w-full flex items-center gap-1 px-2 py-1.5 border-b border-border hover:bg-muted/30 text-left text-[10px] font-semibold uppercase tracking-wide text-muted-fg"
          @click="resultsOpen = !resultsOpen">
          <component :is="resultsOpen ? ChevronDown : ChevronRight" :size="11" />
          <span>Results</span>
          <span class="ml-auto text-[9px] normal-case font-normal opacity-70">{{ filtered.length }} {{ filtered.length === 1 ? 'tour' : 'tours' }}</span>
        </button>

        <!-- Grouped tree -->
        <div v-if="resultsOpen" ref="resultsEl" class="flex-1 overflow-y-auto text-xs" @keydown="onResultsKeydown">
          <div v-for="g in grouped" :key="g.key">
            <button class="w-full flex items-center justify-between gap-2 px-2 py-1 cursor-pointer
                           hover:bg-muted/60 hover:text-foreground text-left border-b border-border
                           transition-colors duration-100"
              @click="toggle(g.key)">
              <div class="flex items-center gap-1">
                <component :is="expanded.has(g.key) ? ChevronDown : ChevronRight" :size="12" class="text-muted-fg" />
                <span class="font-semibold">{{ g.label }}</span>
              </div>
              <span class="flex items-center gap-1.5 text-[10px] text-muted-fg">
                <span v-if="g.km" class="tabular-nums">{{ fmtGroupKm(g.km) }}</span>
                <span class="opacity-70">{{ g.count }}</span>
              </span>
            </button>

            <div v-if="expanded.has(g.key)">
              <template v-if="g.children?.length">
                <div v-for="sg in g.children" :key="sg.key">
                  <button class="w-full flex items-center justify-between gap-2 pl-5 pr-2 py-0.5 cursor-pointer
                                 hover:bg-muted/60 hover:text-foreground text-left bg-muted/5
                                 transition-colors duration-100"
                    @click="toggle(sg.key)">
                    <div class="flex items-center gap-1">
                      <component :is="expanded.has(sg.key) ? ChevronDown : ChevronRight" :size="10" class="text-muted-fg" />
                      <span>{{ sg.label }}</span>
                    </div>
                    <span class="flex items-center gap-1.5 text-[10px] text-muted-fg">
                      <span v-if="sg.km" class="tabular-nums">{{ fmtGroupKm(sg.km) }}</span>
                      <span class="opacity-70">{{ sg.count }}</span>
                    </span>
                  </button>
                  <div v-if="expanded.has(sg.key)">
                    <button v-for="a in sg.activities" :key="a.id!" type="button"
                      v-memo="[a, selectedId === a.id, dropHoverId === a.id]"
                      class="tour-card w-full text-left flex items-center gap-2 pl-9 pr-2 py-1 cursor-pointer
                             border-l-2 border-l-transparent
                             hover:bg-muted/70 hover:border-l-primary/50 hover:text-foreground
                             transition-colors duration-100"
                      :class="[
                        selectedId === a.id ? 'bg-primary/15 text-primary !border-l-primary' : '',
                        dropHoverId === a.id ? 'ring-2 ring-primary bg-primary/15' : '',
                      ]"
                      @click="openActivity(a.id)"
                      @dragover="onActivityDragOver($event, a.id!)"
                      @dragleave="onActivityDragLeave(a.id!)"
                      @drop="onActivityDrop($event, a)">
                      <component :is="sportIcon(a.activityType)" :size="11" :class="selectedId === a.id ? 'text-primary' : 'text-muted-fg'" />
                      <div class="flex-1 min-w-0">
                        <div class="truncate font-medium">{{ a.name ?? 'Unnamed' }}</div>
                        <div class="text-[10px] text-muted-fg flex gap-1.5">
                          <span>{{ fmtDate(a.startTime) }}</span>
                          <span v-if="a.distanceKm" class="flex items-center gap-0.5"><Ruler :size="8" />{{ fmtDist(a.distanceKm) }}</span>
                          <span v-if="a.movingTimeS" class="flex items-center gap-0.5"><Timer :size="8" />{{ fmtDuration(a.movingTimeS) }}</span>
                          <component v-if="a.weatherCondition" :is="weatherIcon(a.weatherCondition)" :size="9" :class="weatherColor(a.weatherCondition)" />
                          <span v-if="a.gearName" class="flex items-center gap-0.5"><Bike :size="8" />{{ a.gearName }}</span>
                        </div>
                        <!-- Applied tag labels -->
                        <div v-if="(a.tagIds ?? []).length" class="flex flex-wrap gap-0.5 mt-0.5">
                          <span v-for="tid in (a.tagIds ?? [])" :key="tid"
                            class="text-[9px] px-1 rounded-full border border-border text-muted-fg"
                            :style="tagsById.get(tid)?.color ? { borderColor: tagsById.get(tid)!.color!, color: tagsById.get(tid)!.color! } : {}">
                            {{ tagsById.get(tid)?.name ?? '…' }}
                          </span>
                        </div>
                      </div>
                    </button>
                  </div>
                </div>
              </template>
              <template v-else>
                <button v-for="a in g.activities" :key="a.id!" type="button"
                  v-memo="[a, selectedId === a.id, dropHoverId === a.id]"
                  class="tour-card w-full text-left flex items-center gap-2 pl-5 pr-2 py-1 cursor-pointer
                         border-l-2 border-l-transparent
                         hover:bg-muted/70 hover:border-l-primary/50 hover:text-foreground
                         transition-colors duration-100"
                  :class="[
                    selectedId === a.id ? 'bg-primary/15 text-primary !border-l-primary' : '',
                    dropHoverId === a.id ? 'ring-2 ring-primary bg-primary/15' : '',
                  ]"
                  @click="openActivity(a.id)"
                  @dragover="onActivityDragOver($event, a.id!)"
                  @dragleave="onActivityDragLeave(a.id!)"
                  @drop="onActivityDrop($event, a)">
                  <component :is="sportIcon(a.activityType)" :size="11" :class="selectedId === a.id ? 'text-primary' : 'text-muted-fg'" />
                  <div class="flex-1 min-w-0">
                    <div class="truncate font-medium">{{ a.name ?? 'Unnamed' }}</div>
                    <div class="text-[10px] text-muted-fg flex gap-1.5">
                      <span>{{ fmtDate(a.startTime) }}</span>
                      <span v-if="a.distanceKm" class="flex items-center gap-0.5"><Ruler :size="8" />{{ fmtDist(a.distanceKm) }}</span>
                      <span v-if="a.movingTimeS" class="flex items-center gap-0.5"><Timer :size="8" />{{ fmtDuration(a.movingTimeS) }}</span>
                      <component v-if="a.weatherCondition" :is="weatherIcon(a.weatherCondition)" :size="9" :class="weatherColor(a.weatherCondition)" />
                      <span v-if="a.gearName" class="flex items-center gap-0.5"><Bike :size="8" />{{ a.gearName }}</span>
                    </div>
                    <div v-if="(a.tagIds ?? []).length" class="flex flex-wrap gap-0.5 mt-0.5">
                      <span v-for="tid in (a.tagIds ?? [])" :key="tid"
                        class="text-[9px] px-1 rounded-full border border-border text-muted-fg"
                        :style="tagsById.get(tid)?.color ? { borderColor: tagsById.get(tid)!.color!, color: tagsById.get(tid)!.color! } : {}">
                        {{ tagsById.get(tid)?.name ?? '…' }}
                      </span>
                    </div>
                  </div>
                </button>
              </template>
            </div>
          </div>
        </div>
      </template>
    </Pane>

    <!-- ── RIGHT: viewer or editor for the selected tour ─────────────────── -->
    <!-- Splitpanes only auto-fills the second pane on initial mount. Binding
         size explicitly so collapsing the sidebar reclaims the full width. -->
    <Pane :size="100 - effectiveLeftSize" class="overflow-hidden flex flex-col">
      <!-- View/Edit toolbar — only when a tour is selected -->
      <div v-if="selectedId" class="flex items-center gap-1 px-2 py-1 border-b border-border bg-muted/10 text-[11px]">
        <div class="flex items-center gap-1 shrink-0">
          <button class="inline-flex items-center gap-1 px-2 py-0.5 rounded transition-colors"
            :class="rightMode === 'view' ? 'bg-primary/10 text-primary' : 'text-muted-fg hover:text-foreground'"
            @click="endEdit">
            <MapIcon :size="11" /> Map
          </button>
          <button class="inline-flex items-center gap-1 px-2 py-0.5 rounded transition-colors"
            :class="rightMode === 'edit' ? 'bg-primary/10 text-primary' : 'text-muted-fg hover:text-foreground'"
            @click="startEdit">
            <Pencil :size="11" /> Edit
          </button>
        </div>
        <!-- Tour title, centred next to the map; click to edit it in the details. -->
        <button class="flex-1 min-w-0 text-center text-[12px] font-semibold text-foreground truncate px-2 hover:text-primary transition-colors"
          title="Edit in tour details" @click="startEdit">{{ selectedName }}</button>
        <div class="w-[96px] shrink-0"></div>
      </div>

      <div class="flex-1 min-h-0">
        <ActivityViewer v-if="rightMode === 'view'" :activity-id="selectedId" />
        <EditTourPanel v-else-if="selectedId" :activity-id="selectedId" @done="endEdit" @cancel="endEdit" />
      </div>
    </Pane>

  </Splitpanes>
</template>
