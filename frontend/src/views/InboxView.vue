<script setup lang="ts">
import { fmtDuration, fmtDateTime } from '@/lib/format'
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { Splitpanes, Pane } from 'splitpanes'
import { push } from 'notivue'
import { Inbox, Bike, MapPin, Timer, Ruler, Upload, Trash2, Clock, Loader2, FolderInput, Sparkles, Eraser, Search, X as XIcon, ChevronDown, ChevronRight, ArrowUpRight } from 'lucide-vue-next'
import { getInbox, refreshInbox, refreshInboxItem, scanWatchFolders, getSkippedInbox, importInbox, uploadFit, discardInbox, wipeInbox, SOURCE_FORMATS, type InboxItem } from '@/api/client'
import { InboxStreamEvent } from '@/enums/generated'
import AddTourPanel from '@/components/AddTourPanel.vue'

const qc = useQueryClient()

// Accepted-format UI is driven entirely by the backend SourceFormat enum, so a
// new format added server-side flows here through the regenerated types.
const acceptAttr = SOURCE_FORMATS.map(f => '.' + f).join(',')
const formatRegex = new RegExp('\\.(' + SOURCE_FORMATS.join('|') + ')$', 'i')
const formatLabel = SOURCE_FORMATS.map(f => '.' + f).join(' / ')

const { data: items, isPending } = useQuery({
  queryKey: ['inbox'],
  queryFn: getInbox,
  // Updates arrive via the SSE stream below (push). The interval is just a
  // backstop in case the stream is unavailable (e.g. behind a proxy that buffers).
  refetchInterval: 60000,
})

// Live push: subscribe to the inbox event stream and refetch on change, instead
// of tight polling. EventSource reconnects automatically if the stream drops.
let inboxStream: EventSource | null = null
onMounted(() => {
  try {
    inboxStream = new EventSource('/api/inbox/stream')
    // Event name derived from the OpenAPI-published enum (api-first), not a magic string.
    inboxStream.addEventListener(InboxStreamEvent.INBOX_CHANGED, () => {
      qc.invalidateQueries({ queryKey: ['inbox'] })
      qc.invalidateQueries({ queryKey: ['inbox-skipped'] })
    })
  } catch { /* SSE unavailable — the backstop interval still refreshes */ }
})
onBeforeUnmount(() => { inboxStream?.close(); inboxStream = null })

const selected = ref<string | null>(null)
const selectedItem = computed<InboxItem | null>(() =>
  items.value?.find(i => i.filename === selected.value) ?? null,
)

// "Already imported" (Ignored) filter: files the last scan skipped because
// they're already in the library. Read-only transparency — confirms the watcher
// saw your device files even though it didn't (re-)stage them.
const showSkipped = ref(false)
const { data: skipped } = useQuery({ queryKey: ['inbox-skipped'], queryFn: getSkippedInbox, refetchInterval: 60000 })

// Auto-select the first item once data arrives.
watch(items, (list) => {
  if (!selected.value && list && list.length > 0) {
    selected.value = list[0].filename ?? null
  }
  if (selected.value && list && !list.some(i => i.filename === selected.value)) {
    selected.value = list[0]?.filename ?? null
  }
}, { immediate: true })

// After a tour is imported / discarded / processed, advance to the NEXT pending
// item so you can keep working through the inbox without re-clicking. We pick the
// next by filename from the CURRENT list (synchronously — the saved item only
// drops off on the async refetch), falling back to the previous, else clearing.
// Doing it here (not via the watcher + null) makes it deterministic instead of
// racing the refetch, which is why it sometimes didn't advance.
function advanceToNext() {
  const list = visibleItems.value
  const idx = list.findIndex(i => i.filename === selected.value)
  const next = idx >= 0 ? (list[idx + 1] ?? list[idx - 1]) : list[0]
  selected.value = next?.filename ?? null
}

// ── Drag-drop upload ────────────────────────────────────────────────────────
const isDragging = ref(false)
const uploading = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

async function importFiles(files: File[]) {
  const supported = files.filter(f => formatRegex.test(f.name))
  if (!supported.length) {
    push.warning({ title: 'Unsupported file', message: `Only ${formatLabel} are accepted.` })
    return
  }
  uploading.value = true
  let ok = 0
  for (const f of supported) {
    try { await uploadFit(f); ok++ }
    catch { push.error({ title: 'Upload failed', message: f.name }) }
  }
  if (ok) {
    push.success({ title: `${ok} file${ok > 1 ? 's' : ''} staged` })
    qc.invalidateQueries({ queryKey: ['inbox'] })
  }
  uploading.value = false
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  isDragging.value = false
  importFiles(Array.from(e.dataTransfer?.files ?? []))
}

function onFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  importFiles(Array.from(input.files ?? []))
  input.value = ''
}

function fmtKm(km: number | null | undefined) { return km != null ? km.toFixed(1) + ' km' : '—' }

// ── Filter + refresh ────────────────────────────────────────────────────────
const filterText = ref('')
const visibleItems = computed(() => {
  const q = filterText.value.trim().toLowerCase()
  const list = items.value ?? []
  if (!q) return list
  return list.filter(i =>
    (i.suggestedName ?? '').toLowerCase().includes(q)
    || (i.filename ?? '').toLowerCase().includes(q)
    || (i.suggestedLocation ?? '').toLowerCase().includes(q))
})

// Refresh = (1) scan the configured inbox folders (Garmin USB, cloud sync) for
// new files right now instead of waiting for the ~60s poll, then (2) recompute
// proposals (gear/type/duplicate) from current history, then refetch. Cards
// briefly show "parsing" while the warm reruns.
const refreshing = ref(false)
async function refresh() {
  refreshing.value = true
  try {
    const { copied } = await scanWatchFolders().catch(() => ({ copied: 0 }))
    await refreshInbox()
    await qc.invalidateQueries({ queryKey: ['inbox'] })
    if (copied > 0) {
      push.success({ title: `Found ${copied} new file${copied !== 1 ? 's' : ''}`, message: 'Pulled from your inbox folders. Cards update as they process.' })
    } else {
      push.success({ title: 'Up to date', message: 'No new files in your inbox folders; proposals re-processing.' })
    }
  } catch { push.error('Refresh failed') }
  finally { refreshing.value = false }
}

// Wipe — clear the whole inbox in one click (counterpart to "Import all"). Each
// file moves to the recoverable inbox-ignored area, never destroyed. Confirmed.
const wiping = ref(false)
async function wipe() {
  const n = items.value?.length ?? 0
  if (!n) return
  if (!window.confirm(`Clear all ${n} file${n !== 1 ? 's' : ''} from the inbox? They move to the recoverable "already imported" area — drag back or "Stage anyway" to restore.`)) return
  wiping.value = true
  try {
    const { cleared } = await wipeInbox()
    selected.value = null
    await qc.invalidateQueries({ queryKey: ['inbox'] })
    await qc.invalidateQueries({ queryKey: ['inbox-skipped'] })
    push.success({ title: `Wiped ${cleared} file${cleared !== 1 ? 's' : ''}`, message: 'Inbox cleared. Files are recoverable from the "already imported" area.' })
  } catch { push.error('Wipe failed') }
  finally { wiping.value = false }
}

// Bulk import — import every (filtered) pending ride using its proposed gear /
// sport / location / tags. Sequential with progress so the backend isn't hit by
// hundreds of parallel parses; skips already-imported (exact dup) files.
const importingAll = ref(false)
const importDone = ref(0)
const importTotal = ref(0)
async function importAllVisible() {
  const list = visibleItems.value.filter(i => !i.existingActivityId && i.filename)
  if (!list.length) { push.info({ title: 'Nothing to import' }); return }
  if (!window.confirm(`Import all ${list.length} pending ride${list.length > 1 ? 's' : ''} with their proposed gear, sport, location and tags?`)) return
  importingAll.value = true
  importDone.value = 0
  importTotal.value = list.length
  let ok = 0, failed = 0
  for (const it of list) {
    try {
      await importInbox(it.filename!, {
        name: it.suggestedName || undefined,
        activityType: it.activityType || undefined,
        gearId: it.suggestedGearId || undefined,
        startLocation: it.suggestedLocation || undefined,
        startCountry: it.country || undefined,
        tagNames: (it.suggestedTagNames && it.suggestedTagNames.length) ? it.suggestedTagNames : undefined,
      })
      ok++
    } catch { failed++ }
    importDone.value++
  }
  importingAll.value = false
  selected.value = null
  await qc.invalidateQueries({ queryKey: ['inbox'] })
  await qc.invalidateQueries({ queryKey: ['activities'] })
  push.success({ title: `Imported ${ok} ride${ok !== 1 ? 's' : ''}`, message: failed ? `${failed} failed` : 'All done' })
}

// Per-item AI refresh — recompute one ride's proposals.
const refreshingItem = ref<string | null>(null)
async function refreshItem(filename: string) {
  refreshingItem.value = filename
  try {
    await refreshInboxItem(filename)
    await qc.invalidateQueries({ queryKey: ['inbox'] })
  } catch { push.error('Refresh failed') }
  finally { refreshingItem.value = null }
}

// The single user-initiated remove: the file leaves the inbox (kept in
// inbox-ignored/ for undo) and won't re-stage from a keep-on-device source.
async function removeFromInbox(filename: string) {
  try {
    await discardInbox(filename)
    qc.invalidateQueries({ queryKey: ['inbox'] })
    push.info({ title: 'Removed from inbox' })
  } catch { push.error('Could not remove from inbox') }
}
</script>

<template>
  <Splitpanes class="h-full w-full">
    <!-- Pending list -->
    <Pane :size="32" :min-size="22" :max-size="50" class="flex flex-col bg-background border-r border-border overflow-hidden"
      @dragover.prevent="isDragging = true"
      @dragleave="isDragging = false"
      @drop="onDrop">
      <div class="flex items-center gap-2 px-3 py-2 border-b border-border">
        <Inbox :size="16" class="text-primary" />
        <h2 class="text-sm font-semibold">Inbox</h2>
        <span class="text-[10px] text-muted-fg ml-auto">{{ visibleItems.length }}{{ filterText.trim() ? ' / ' + (items?.length ?? 0) : '' }} pending</span>
        <button class="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded border border-border hover:border-primary hover:text-primary transition-colors shrink-0 disabled:opacity-50"
          :title="filterText.trim() ? 'Import all filtered rides with their proposals' : 'Import all pending rides with their proposals'"
          :disabled="importingAll || refreshing || visibleItems.length === 0" @click="importAllVisible">
          <Upload :size="13" :class="importingAll ? 'animate-pulse' : ''" />
          {{ importingAll ? `${importDone}/${importTotal}` : 'Import all' }}
        </button>
        <button
          class="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded border border-border hover:border-amber-500 hover:text-amber-500 transition-colors shrink-0 disabled:opacity-50"
          title="Clear the whole inbox — every file moves to the recoverable 'already imported' area (nothing is destroyed)"
          :disabled="wiping || importingAll || refreshing || (items?.length ?? 0) === 0" @click="wipe">
          <Eraser :size="13" :class="wiping ? 'animate-pulse' : ''" /> {{ wiping ? '…' : 'Wipe' }}
        </button>
        <button class="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded border border-border hover:border-primary hover:text-primary transition-colors shrink-0 disabled:opacity-50"
          title="Re-run smart proposals (gear, sport, duplicates) from your current ride history"
          :disabled="refreshing || importingAll" @click="refresh">
          <Sparkles :size="13" :class="refreshing ? 'animate-pulse' : ''" /> {{ refreshing ? '…' : 'Refresh' }}
        </button>
      </div>

      <!-- Filter -->
      <div class="px-2 pt-2">
        <div class="relative">
          <Search :size="13" class="absolute left-2 top-1/2 -translate-y-1/2 text-muted-fg" />
          <input v-model="filterText" type="text" placeholder="Filter by name / file / place…"
            class="w-full pl-7 pr-7 py-1.5 text-[12px] rounded-md border border-border bg-background focus:outline-none focus:border-primary" />
          <button v-if="filterText" class="absolute right-1.5 top-1/2 -translate-y-1/2 text-muted-fg hover:text-foreground" title="Clear" @click="filterText = ''">
            <XIcon :size="13" />
          </button>
        </div>
      </div>

      <!-- Drop zone -->
      <div class="mx-2 my-2 drop-zone flex flex-col items-center justify-center gap-1 py-4 text-center select-none cursor-pointer"
        :class="isDragging ? 'dragging' : ''"
        @click="fileInputRef?.click()">
        <Upload v-if="!uploading" :size="20" class="text-muted-fg opacity-70" />
        <svg v-else class="text-primary animate-spin" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M21 12a9 9 0 1 1-6.219-8.56" />
        </svg>
        <span class="text-[11px] text-muted-fg font-medium">
          {{ uploading ? 'Uploading…' : (isDragging ? 'Drop to stage' : `Drop ${formatLabel} or click to import`) }}
        </span>
        <input ref="fileInputRef" type="file" :accept="acceptAttr" multiple class="hidden" @change="onFileSelect" />
      </div>

      <!-- "Already imported" filter: opt-in transparency for files the last scan
           skipped because they're already in your library (so they're not staged). -->
      <button v-if="skipped && skipped.length"
        class="mx-2 mb-1 inline-flex items-center gap-1.5 px-2 py-1 text-[11px] font-medium rounded border border-border text-muted-fg hover:text-foreground hover:bg-muted/30 transition-colors"
        @click="showSkipped = !showSkipped">
        <component :is="showSkipped ? ChevronDown : ChevronRight" :size="12" />
        Already imported · {{ skipped.length }}
        <span class="ml-auto text-[10px] opacity-70">{{ showSkipped ? 'hide' : 'on your devices' }}</span>
      </button>
      <div v-if="showSkipped && skipped && skipped.length"
        class="mx-2 mb-2 rounded border border-border bg-muted/10 divide-y divide-border max-h-48 overflow-y-auto">
        <div v-for="e in skipped" :key="e.filename" class="flex items-center gap-2 px-2 py-1.5 text-[11px]">
          <Bike :size="11" class="text-muted-fg shrink-0" />
          <span class="truncate flex-1" :title="e.filename">{{ e.filename }}</span>
          <span v-if="e.sourceLabel" class="text-[9px] px-1 rounded-full bg-sky-500/15 text-sky-600 border border-sky-500/40 shrink-0">{{ e.sourceLabel }}</span>
          <RouterLink :to="`/tour/${e.activityId}`" class="text-[10px] text-primary hover:underline shrink-0">view</RouterLink>
        </div>
      </div>

      <div v-if="isPending" class="p-3 text-xs text-muted-fg animate-pulse">Loading…</div>

      <div v-else-if="!items || items.length === 0" class="p-6 text-center text-xs text-muted-fg opacity-70">
        <Inbox :size="32" class="mx-auto mb-2 opacity-30" />
        Nothing pending.<br />
        Drop a .fit file in <code class="text-[10px]">~/.tourgaze/inbox/</code> or plug in your Garmin.
      </div>

      <div v-else-if="visibleItems.length === 0" class="p-6 text-center text-xs text-muted-fg opacity-70">
        No items match “{{ filterText }}”.
      </div>

      <div v-else class="flex-1 overflow-y-auto p-2 space-y-1">
        <div
          v-for="it in visibleItems"
          :key="it.filename!"
          class="group w-full text-left rounded border px-2.5 py-2 transition-colors cursor-pointer"
          :class="selected === it.filename
            ? 'border-primary bg-primary/5'
            : 'border-border hover:bg-muted/40'"
          role="button" tabindex="0"
          @click="selected = it.filename ?? null"
          @keydown.enter="selected = it.filename ?? null"
        >
          <div class="flex items-center gap-1.5 mb-0.5">
            <Bike :size="12" class="text-muted-fg shrink-0" />
            <span class="text-xs font-medium truncate flex-1">{{ it.suggestedName }}</span>
            <span v-if="it.existingActivityId"
              class="text-[9px] px-1 rounded-full bg-amber-500/15 text-amber-600 border border-amber-500/40 shrink-0"
              title="This exact file is already in your library — nothing to import.">already imported</span>
            <span v-else-if="it.duplicateOfName"
              class="text-[9px] px-1 rounded-full bg-muted/40 text-muted-fg border border-border shrink-0"
              :title="`Looks like the same route as ${it.duplicateOfName} — importable, just a heads-up.`">similar route</span>
            <!-- Which inbox source (watch-folder) this file came from — matters with
                 multiple sources. Absent for hand-dropped files. -->
            <span v-if="it.sourceLabel"
              class="text-[9px] px-1 rounded-full bg-sky-500/15 text-sky-600 border border-sky-500/40 shrink-0 inline-flex items-center gap-0.5"
              :title="`Inbox source: ${it.sourceLabel}`">
              <FolderInput :size="9" />{{ it.sourceLabel }}
            </span>
            <!-- Inline actions (matros-style), revealed on hover. -->
            <span class="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity shrink-0">
              <button type="button" class="p-1 rounded text-muted-fg hover:text-primary hover:bg-primary/10"
                title="AI refresh — recompute this ride's proposals (gear, sport, duplicate) from your history"
                :disabled="refreshingItem === it.filename"
                @click.stop="refreshItem(it.filename!)">
                <Sparkles :size="13" :class="refreshingItem === it.filename ? 'animate-pulse' : ''" />
              </button>
              <button type="button" class="p-1 rounded text-muted-fg hover:text-red-600 hover:bg-red-500/10"
                title="Remove from inbox — un-stages this file (kept for undo, won't re-stage). Does NOT delete the ride from your library."
                @click.stop="removeFromInbox(it.filename!)">
                <Trash2 :size="13" />
              </button>
            </span>
          </div>
          <!-- Already-imported: show the matched ride's id (selectable for marking)
               + a jump icon into the track list. The id itself is not a link. -->
          <div v-if="it.existingActivityId" class="flex items-center gap-1 mb-0.5 text-[9px] text-amber-600 dark:text-amber-400">
            <span class="font-mono select-all truncate" :title="it.existingActivityId">{{ it.existingActivityId }}</span>
            <RouterLink
              :to="`/tour/${it.existingActivityId}`"
              class="p-0.5 rounded hover:bg-amber-500/15 shrink-0"
              title="Show this ride in the track list"
              @click.stop
            >
              <ArrowUpRight :size="11" />
            </RouterLink>
          </div>
          <div class="text-[10px] text-muted-fg flex flex-wrap gap-2">
            <span>{{ it.format?.toUpperCase() }}</span>
            <span v-if="it.distanceKm" class="flex items-center gap-0.5"><Ruler :size="9" />{{ fmtKm(it.distanceKm) }}</span>
            <span v-if="it.durationS" class="flex items-center gap-0.5"><Timer :size="9" />{{ fmtDuration(it.durationS) }}</span>
            <span v-if="it.activityType">{{ it.activityType }}</span>
          </div>
          <div v-if="it.startTime" class="text-[10px] text-muted-fg mt-0.5 flex items-center gap-0.5">
            <Clock :size="9" />{{ fmtDateTime(it.startTime) }}
          </div>
          <!-- Exact file already in the repository: nothing to import → a clear,
               always-visible one-click delete right on the row. -->
          <div v-if="it.existingActivityId" class="mt-1.5 flex justify-end text-[10px]">
            <button type="button"
              class="shrink-0 px-2 py-0.5 rounded border border-amber-500/40 text-amber-600 hover:bg-amber-500/10 inline-flex items-center gap-1"
              title="Remove from inbox — un-stages this file (kept for undo, won't re-stage). Does NOT delete the ride from your library."
              @click.stop="removeFromInbox(it.filename!)">
              <Trash2 :size="11" /> Remove from inbox
            </button>
          </div>
          <!-- Same route (different file): importable, just a soft heads-up. -->
          <div v-else-if="it.duplicateOfName" class="mt-1.5 text-[10px] text-muted-fg">
            Looks like the same route as “{{ it.duplicateOfName }}”.
          </div>
          <!-- Skeleton: file listed instantly, not yet parsed by the warm job (pushed → fills in). -->
          <div v-if="it.parsing" class="text-[10px] text-muted-fg/70 mt-0.5 flex items-center gap-1">
            <Loader2 :size="9" class="animate-spin" /> parsing…
          </div>
          <!-- Proposal still being computed in the background (pushed → flips to ready). -->
          <div v-else-if="it.proposalPending" class="text-[10px] text-muted-fg/70 mt-0.5 flex items-center gap-1">
            <Loader2 :size="9" class="animate-spin" /> finding place &amp; tags…
          </div>
          <!-- Proposal preview (matros-style): reverse-geocoded place + region/country. -->
          <div v-if="it.suggestedLocation" class="text-[10px] text-muted-fg mt-0.5 flex items-center gap-0.5">
            <MapPin :size="9" /><span class="truncate">{{ it.suggestedLocation }}</span>
            <span v-if="it.country" class="opacity-70 shrink-0">· {{ it.country }}</span>
          </div>
          <!-- Suggested gear + tag chips (region/country + nearby-ride tags). -->
          <div v-if="it.suggestedGearName || (it.suggestedTagNames && it.suggestedTagNames.length)"
            class="flex flex-wrap items-center gap-1 mt-1">
            <span v-if="it.suggestedGearName"
              class="text-[9px] px-1 rounded border border-border text-muted-fg inline-flex items-center gap-0.5">
              <Bike :size="8" />{{ it.suggestedGearName }}
            </span>
            <span v-for="t in (it.suggestedTagNames ?? [])" :key="t"
              class="text-[9px] px-1 rounded-full border border-primary/40 text-primary/80">{{ t }}</span>
          </div>
        </div>
      </div>
    </Pane>

    <!-- AddTour form for the selected pending item -->
    <Pane class="overflow-hidden">
      <AddTourPanel
        v-if="selectedItem"
        :item="selectedItem"
        @done="advanceToNext"
        @cancel="selected = null"
      />
      <div v-else class="h-full flex flex-col items-center justify-center text-muted-fg text-sm">
        <Inbox :size="40" class="opacity-20 mb-3" />
        <p class="opacity-60">Select a pending file to review</p>
      </div>
    </Pane>
  </Splitpanes>
</template>
