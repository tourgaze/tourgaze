<script setup lang="ts">
import { fmtDuration, fmtDateTime } from '@/lib/format'
import { computed, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { Splitpanes, Pane } from 'splitpanes'
import { push } from 'notivue'
import { Inbox, Bike, MapPin, Timer, Ruler, Upload } from 'lucide-vue-next'
import { getInbox, uploadFit, SOURCE_FORMATS, type InboxItem } from '@/api/client'
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
  refetchInterval: 5000,
})

const selected = ref<string | null>(null)
const selectedItem = computed<InboxItem | null>(() =>
  items.value?.find(i => i.filename === selected.value) ?? null,
)

// Auto-select the first item once data arrives.
watch(items, (list) => {
  if (!selected.value && list && list.length > 0) {
    selected.value = list[0].filename ?? null
  }
  if (selected.value && list && !list.some(i => i.filename === selected.value)) {
    selected.value = list[0]?.filename ?? null
  }
}, { immediate: true })

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
        <span class="text-[10px] text-muted-fg ml-auto">{{ items?.length ?? 0 }} pending</span>
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

      <div v-if="isPending" class="p-3 text-xs text-muted-fg animate-pulse">Loading…</div>

      <div v-else-if="!items || items.length === 0" class="p-6 text-center text-xs text-muted-fg opacity-70">
        <Inbox :size="32" class="mx-auto mb-2 opacity-30" />
        Nothing pending.<br />
        Drop a .fit file in <code class="text-[10px]">~/.tourgaze/inbox/</code> or plug in your Garmin.
      </div>

      <div v-else class="flex-1 overflow-y-auto p-2 space-y-1">
        <button
          v-for="it in items"
          :key="it.filename!"
          class="w-full text-left rounded border px-2.5 py-2 transition-colors"
          :class="selected === it.filename
            ? 'border-primary bg-primary/5'
            : 'border-border hover:bg-muted/40'"
          @click="selected = it.filename ?? null"
        >
          <div class="flex items-center gap-1.5 mb-0.5">
            <Bike :size="12" class="text-muted-fg" />
            <span class="text-xs font-medium truncate flex-1">{{ it.suggestedName }}</span>
            <span v-if="it.existingActivityId" class="text-[9px] text-amber-600">dup</span>
          </div>
          <div class="text-[10px] text-muted-fg flex flex-wrap gap-2">
            <span>{{ it.format?.toUpperCase() }}</span>
            <span v-if="it.distanceKm" class="flex items-center gap-0.5"><Ruler :size="9" />{{ fmtKm(it.distanceKm) }}</span>
            <span v-if="it.durationS" class="flex items-center gap-0.5"><Timer :size="9" />{{ fmtDuration(it.durationS) }}</span>
            <span v-if="it.activityType">{{ it.activityType }}</span>
          </div>
          <div v-if="it.startTime" class="text-[10px] text-muted-fg mt-0.5 flex items-center gap-0.5">
            <MapPin :size="9" />{{ fmtDateTime(it.startTime) }}
          </div>
        </button>
      </div>
    </Pane>

    <!-- AddTour form for the selected pending item -->
    <Pane class="overflow-hidden">
      <AddTourPanel
        v-if="selectedItem"
        :item="selectedItem"
        @done="selected = null"
        @cancel="selected = null"
      />
      <div v-else class="h-full flex flex-col items-center justify-center text-muted-fg text-sm">
        <Inbox :size="40" class="opacity-20 mb-3" />
        <p class="opacity-60">Select a pending file to review</p>
      </div>
    </Pane>
  </Splitpanes>
</template>
