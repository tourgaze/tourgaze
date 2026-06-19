<script setup lang="ts">
import { ref, watch } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { getSettings, saveSetting } from '@/api/client'
import { FolderInput, Save, RefreshCw } from 'lucide-vue-next'

const qc = useQueryClient()
const { data: settings } = useQuery({ queryKey: ['settings'], queryFn: getSettings })

// One folder per line, stored in the `inbox.watch-dirs` setting.
const dirs = ref('')
watch(settings, (list) => {
  dirs.value = list?.find(s => s.key === 'inbox.watch-dirs')?.value ?? ''
}, { immediate: true })

const saveMut = useMutation({
  mutationFn: () => saveSetting('inbox.watch-dirs', dirs.value.replace(/[ \t]+$/gm, '').trim()),
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
  <div class="w-full space-y-5">
    <label class="block text-sm">
      <span class="text-xs font-medium text-muted-fg flex items-center gap-1"><FolderInput :size="11" /> Watch folders</span>
      <p class="text-[10px] text-muted-fg mb-1 mt-0.5">
        One folder per line. New ride files (<code>.fit .gpx .tcx .kmz .kml</code>) found in these
        folders are <b>copied</b> into the inbox for review — the source is never modified, so it's
        safe to point at a Google&nbsp;Drive folder OpenTracks syncs to (mounted as a Windows drive,
        e.g. <code>G:\OpenTracks</code>). Scanned automatically about every minute.
      </p>
      <textarea v-model="dirs" rows="4" spellcheck="false"
        placeholder="G:\OpenTracks&#10;X:\rides"
        class="w-full mt-1 rounded-md border border-border bg-transparent px-3 py-2 text-sm font-mono focus:border-primary focus:outline-none"></textarea>
      <div class="flex gap-2 mt-1">
        <button class="inline-flex items-center gap-1 px-3 py-2 bg-primary text-primary-fg text-xs font-medium rounded hover:bg-primary/90 disabled:opacity-50"
          :disabled="saveMut.isPending.value" @click="saveMut.mutate()">
          <Save :size="12" /> Save
        </button>
        <button class="inline-flex items-center gap-1 px-3 py-2 border border-border text-xs font-medium rounded hover:bg-muted/40 disabled:opacity-50"
          :disabled="scanning" @click="scanNow">
          <RefreshCw :size="12" /> {{ scanning ? 'Scanning…' : 'Scan now' }}
        </button>
      </div>
    </label>
  </div>
</template>
