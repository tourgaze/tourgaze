<script setup lang="ts">
import { ref, watch } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { getSettings, saveSetting } from '@/api/client'
import { FolderInput, Save, RefreshCw, Plus, Trash2 } from 'lucide-vue-next'

type InboxSource = { label: string; path: string }

const qc = useQueryClient()
const { data: settings } = useQuery({ queryKey: ['settings'], queryFn: getSettings })

// Inbox folders live in the `inbox.sources` setting as a JSON array of
// {label, path}.
const sources = ref<InboxSource[]>([])

function parseSources(list: { key?: string; value?: string }[] | undefined): InboxSource[] {
  const json = list?.find(s => s.key === 'inbox.sources')?.value
  if (json && json.trim()) {
    try {
      const parsed = JSON.parse(json)
      if (Array.isArray(parsed)) {
        return parsed
          .filter(e => e && typeof e.path === 'string')
          .map(e => ({ label: typeof e.label === 'string' ? e.label : '', path: e.path }))
      }
    } catch { /* malformed — start empty */ }
  }
  return []
}

watch(settings, (list) => {
  sources.value = parseSources(list)
}, { immediate: true })

function addSource() {
  sources.value.push({ label: '', path: '' })
}
function removeSource(i: number) {
  sources.value.splice(i, 1)
}

const saveMut = useMutation({
  mutationFn: () => {
    const cleaned = sources.value
      .map(s => ({ label: s.label.trim(), path: s.path.trim() }))
      .filter(s => s.path !== '')
    return saveSetting('inbox.sources', JSON.stringify(cleaned))
  },
  onSuccess: () => { qc.invalidateQueries({ queryKey: ['settings'] }); push.success({ title: 'Inbox folders saved' }) },
  onError: () => push.error('Could not save'),
})

const scanning = ref(false)
async function scanNow() {
  scanning.value = true
  try {
    const r = await fetch('/api/inbox/scan-watch-folders', { method: 'POST' })
    const j = await r.json()
    push.success({ title: 'Scan complete', message: `${j.copied ?? 0} new file(s) copied to the inbox` })
    qc.invalidateQueries({ queryKey: ['inbox'] })
  } catch {
    push.error('Scan failed')
  } finally {
    scanning.value = false
  }
}
</script>

<template>
  <div class="w-full space-y-4">
    <div class="text-sm">
      <span class="text-xs font-medium text-muted-fg flex items-center gap-1"><FolderInput :size="11" /> Inbox folders</span>
      <p class="text-[10px] text-muted-fg mb-2 mt-0.5">
        Folders scanned for new ride files (<code>.fit .gpx .tcx .kmz .kml</code>). New files are
        <b>copied</b> into the inbox for review — the source is never modified, so it's safe to point
        at your Garmin mount (e.g. <code>X:\garmin\activity</code>) or a Google&nbsp;Drive folder
        OpenTracks syncs to (e.g. <code>G:\OpenTracks</code>). Scanned automatically about every minute.
      </p>
    </div>

    <div class="space-y-2">
      <div v-for="(src, i) in sources" :key="i" class="flex gap-2 items-center">
        <input v-model="src.label" type="text" placeholder="Label (e.g. Garmin)"
          class="w-36 rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
        <input v-model="src.path" type="text" placeholder="X:\garmin\activity"
          class="flex-1 rounded-md border border-border bg-transparent px-3 py-2 text-sm font-mono focus:border-primary focus:outline-none" />
        <button class="btn-icon text-muted-fg hover:text-red-500" title="Remove folder" @click="removeSource(i)">
          <Trash2 :size="14" />
        </button>
      </div>
      <p v-if="sources.length === 0" class="text-[11px] text-muted-fg italic">No inbox folders yet.</p>

      <button class="inline-flex items-center gap-1 px-2 py-1.5 border border-border text-xs font-medium rounded hover:bg-muted/40"
        @click="addSource">
        <Plus :size="12" /> Add folder
      </button>
    </div>

    <div class="flex gap-2 border-t border-border pt-3">
      <button class="inline-flex items-center gap-1 px-3 py-2 bg-primary text-primary-fg text-xs font-medium rounded hover:bg-primary/90 disabled:opacity-50"
        :disabled="saveMut.isPending.value" @click="saveMut.mutate()">
        <Save :size="12" /> Save
      </button>
      <button class="inline-flex items-center gap-1 px-3 py-2 border border-border text-xs font-medium rounded hover:bg-muted/40 disabled:opacity-50"
        :disabled="scanning" @click="scanNow">
        <RefreshCw :size="12" /> {{ scanning ? 'Scanning…' : 'Scan now' }}
      </button>
    </div>
  </div>
</template>
