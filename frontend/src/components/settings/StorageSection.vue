<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { getDiskUsage, purgeCache, getSettings, saveSetting, openRepositoryFolder, checkIntegrity, type IntegrityReport } from '@/api/client'
import { FolderOpen, ShieldCheck } from 'lucide-vue-next'

const qc = useQueryClient()
const { data: disk, refetch } = useQuery({ queryKey: ['disk'], queryFn: getDiskUsage })

// "Reduce uploaded images" — downscale big photos on upload (default on; absent = on).
const { data: settings } = useQuery({ queryKey: ['settings'], queryFn: getSettings })
const reduceImages = ref(true)
watch(settings, (list) => {
  reduceImages.value = list?.find(s => s.key === 'media.reduceImages')?.value !== 'false'
}, { immediate: true })
const reduceMut = useMutation({
  mutationFn: (on: boolean) => saveSetting('media.reduceImages', on ? 'true' : 'false'),
  onSuccess: () => { qc.invalidateQueries({ queryKey: ['settings'] }) },
  onError: () => push.error('Could not save setting'),
})
function toggleReduce() {
  reduceImages.value = !reduceImages.value
  reduceMut.mutate(reduceImages.value)
}
const adminUnsupported = computed(() =>
  disk.value?.storeBytes === 0 && disk.value?.cacheBytes === 0
  && disk.value?.tilesBytes === 0 && disk.value?.totalBytes === 0,
)

const purgeMut = useMutation({
  mutationFn: purgeCache,
  onSuccess: (r) => {
    push.success({ title: 'Cache purged', message: `${r.deleted} file${r.deleted !== 1 ? 's' : ''} removed` })
    refetch()
  },
  onError: (e: any) => {
    if (String(e?.message ?? '').includes('ADMIN_ENDPOINT_UNSUPPORTED')) {
      push.warning({ title: 'Unavailable on current backend' })
      return
    }
    push.error('Failed to purge cache')
  },
})

// Download a ZIP recreating every ride's original dropped file (name + content)
// + its metadata sidecar — a plaintext escape hatch (handy with encryption) and
// a re-importable backup (unzip into the inbox).
function exportRides() {
  window.location.href = '/api/admin/export'
}

// DB ↔ filesystem integrity check (missing files, bit-rot, orphan folders).
const integrity = ref<IntegrityReport | null>(null)
// Generated arrays are optional → normalise for the template.
const rep = computed(() => integrity.value ? {
  total: integrity.value.totalActivities ?? 0,
  missing: integrity.value.missing ?? [],
  corrupt: integrity.value.corrupt ?? [],
  orphans: integrity.value.orphanFolders ?? [],
} : null)
const integrityMut = useMutation({
  mutationFn: checkIntegrity,
  onSuccess: (r) => {
    integrity.value = r
    const issues = (r.missing?.length ?? 0) + (r.corrupt?.length ?? 0) + (r.orphanFolders?.length ?? 0)
    if (issues === 0) push.success({ title: 'All good', message: `${r.totalActivities ?? 0} rides, no problems` })
    else push.warning({ title: `${issues} issue${issues !== 1 ? 's' : ''} found`, message: 'See the report below' })
  },
  onError: () => push.error('Integrity check failed'),
})

// Open the repository folder in the OS file manager (server-side; only works when
// the app runs on your own machine, not a remote/container backend).
const openFolderMut = useMutation({
  mutationFn: openRepositoryFolder,
  onSuccess: (r) => push.success({ title: 'Opening folder', message: r.path }),
  onError: (e: any) => push.warning({ title: "Couldn't open folder", message: String(e?.message ?? '') }),
})

function fmtBytes(b: number | undefined): string {
  if (b == null) return '—'
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
}
</script>

<template>
  <div class="w-full space-y-4">
    <div class="flex items-start justify-between gap-3 p-4 rounded border border-border bg-muted/10">
      <div>
        <p class="text-sm font-medium">Repository folder</p>
        <p class="text-[11px] text-muted-fg mt-0.5">
          Your precious, cloud-syncable library (<code>store/</code> + <code>db-backup/</code>).
          Opens in your file manager — only works when the app runs on this machine.
        </p>
      </div>
      <button class="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded border border-border hover:border-primary hover:text-primary transition-colors shrink-0 disabled:opacity-50"
        :disabled="openFolderMut.isPending.value" @click="openFolderMut.mutate()">
        <FolderOpen :size="14" /> Open folder
      </button>
    </div>

    <div class="grid grid-cols-2 gap-2 text-sm p-4 rounded border border-border bg-muted/10">
      <div class="text-muted-fg">FIT files (store)</div>
      <div class="font-mono text-right">{{ fmtBytes(disk?.storeBytes) }}</div>
      <div class="text-muted-fg">Track cache</div>
      <div class="font-mono text-right">{{ fmtBytes(disk?.cacheBytes) }}</div>
      <div class="text-muted-fg">Tile cache</div>
      <div class="font-mono text-right">{{ fmtBytes(disk?.tilesBytes) }}</div>
      <div class="text-foreground font-medium border-t border-border pt-2 mt-1 col-span-1">Total</div>
      <div class="font-mono text-right font-medium border-t border-border pt-2 mt-1">{{ fmtBytes(disk?.totalBytes) }}</div>
    </div>

    <div class="flex items-start justify-between gap-3 p-4 rounded border border-border bg-muted/10">
      <div>
        <p class="text-sm font-medium">Reduce uploaded images</p>
        <p class="text-[11px] text-muted-fg mt-0.5">
          Downscale large photos to ~2048&nbsp;px on upload (no need to keep full 5k originals).
          Videos and small images are stored untouched.
        </p>
      </div>
      <button type="button" role="switch" :aria-checked="reduceImages"
        class="relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors"
        :class="reduceImages ? 'bg-primary' : 'bg-muted-fg/40'"
        @click="toggleReduce">
        <span class="inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform"
          :class="reduceImages ? 'translate-x-4' : 'translate-x-0.5'" />
      </button>
    </div>

    <div class="flex items-start justify-between gap-3 p-4 rounded border border-border bg-muted/10">
      <div>
        <p class="text-sm font-medium">Purge track cache</p>
        <p class="text-[11px] text-muted-fg mt-0.5">
          {{ adminUnsupported
            ? 'Not available on current backend build. Update/restart backend to enable this action.'
            : 'Deletes cached JSON files. They are rebuilt automatically on next view.' }}
        </p>
      </div>
      <button class="px-3 py-1.5 text-sm font-medium rounded border border-red-300 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors disabled:opacity-50"
        :disabled="purgeMut.isPending.value || adminUnsupported" @click="purgeMut.mutate()">
        {{ purgeMut.isPending.value ? 'Purging…' : 'Purge' }}
      </button>
    </div>

    <div class="flex items-start justify-between gap-3 p-4 rounded border border-border bg-muted/10">
      <div>
        <p class="text-sm font-medium">Export all rides (ZIP)</p>
        <p class="text-[11px] text-muted-fg mt-0.5">
          Recreates each ride's original file (name + content) plus a
          <code>.metadata.json</code> sidecar. A plaintext backup you can re-import
          by dropping into the inbox — handy if storage encryption is on.
        </p>
      </div>
      <button class="px-3 py-1.5 text-sm font-medium rounded border border-border hover:border-primary hover:text-primary transition-colors shrink-0"
        @click="exportRides">
        Download
      </button>
    </div>

    <!-- DB ↔ filesystem integrity check -->
    <div class="p-4 rounded border border-border bg-muted/10 space-y-2">
      <div class="flex items-start justify-between gap-3">
        <div>
          <p class="text-sm font-medium flex items-center gap-1.5"><ShieldCheck :size="14" /> Check integrity</p>
          <p class="text-[11px] text-muted-fg mt-0.5">
            Verifies every ride's file is present and its content still matches the
            recorded hash (catches bit-rot / cloud-sync damage), and finds orphaned
            <code>store/&lt;id&gt;/</code> folders. Read-only — nothing is changed.
          </p>
        </div>
        <button class="px-3 py-1.5 text-sm font-medium rounded border border-border hover:border-primary hover:text-primary transition-colors shrink-0 disabled:opacity-50"
          :disabled="integrityMut.isPending.value" @click="integrityMut.mutate()">
          {{ integrityMut.isPending.value ? 'Checking…' : 'Run check' }}
        </button>
      </div>
      <div v-if="rep" class="text-[11px]">
        <div class="flex flex-wrap gap-x-4 gap-y-1 font-mono">
          <span>{{ rep.total }} rides</span>
          <span :class="rep.missing.length ? 'text-red-600' : 'text-muted-fg'">{{ rep.missing.length }} missing</span>
          <span :class="rep.corrupt.length ? 'text-red-600' : 'text-muted-fg'">{{ rep.corrupt.length }} corrupt</span>
          <span :class="rep.orphans.length ? 'text-amber-600' : 'text-muted-fg'">{{ rep.orphans.length }} orphan folders</span>
        </div>
        <ul v-if="rep.missing.length || rep.corrupt.length || rep.orphans.length"
          class="mt-1.5 space-y-0.5 text-muted-fg max-h-40 overflow-y-auto">
          <li v-for="m in rep.missing" :key="'m' + m.id" class="text-red-600">⚠ missing file — {{ m.name || m.id }}</li>
          <li v-for="c in rep.corrupt" :key="'c' + c.id" class="text-red-600">⚠ content changed — {{ c.name || c.id }}</li>
          <li v-for="o in rep.orphans" :key="'o' + o" class="text-amber-600">⌁ orphan folder — {{ o }}</li>
        </ul>
        <p v-else class="mt-1 text-emerald-600">✓ everything in sync</p>
      </div>
    </div>
  </div>
</template>
