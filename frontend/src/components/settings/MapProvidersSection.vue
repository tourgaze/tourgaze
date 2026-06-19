<script setup lang="ts">
import { ref } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { getMapProviders, createMapProvider, updateMapProvider, deleteMapProvider, type MapProvider } from '@/api/client'
import { Trash2, Map as MapIcon, Pencil, Check, X, Plus } from 'lucide-vue-next'

const qc = useQueryClient()
const { data: providers, isPending } = useQuery({ queryKey: ['map-providers'], queryFn: getMapProviders })

function blank(): MapProvider {
  return { name: '', type: 'raster', urlTemplate: '', styleUrl: '', attribution: '', maxZoom: 19, dark: false }
}
const draft = ref<MapProvider>(blank())
const editingId = ref<string | null>(null)
const formOpen = ref(false)

function openAdd() { draft.value = blank(); editingId.value = null; formOpen.value = true }
function openEdit(p: MapProvider) { draft.value = { ...p }; editingId.value = p.id ?? null; formOpen.value = true }
function cancel() { formOpen.value = false; editingId.value = null }

function invalidate() { qc.invalidateQueries({ queryKey: ['map-providers'] }); qc.invalidateQueries({ queryKey: ['tile-providers'] }) }

const saveMut = useMutation({
  mutationFn: () => {
    const d = draft.value
    const body: MapProvider = {
      name: d.name.trim(), description: d.description?.trim() || null, type: d.type,
      urlTemplate: d.type === 'raster' ? (d.urlTemplate?.trim() || null) : null,
      styleUrl: d.type === 'vector' ? (d.styleUrl?.trim() || null) : null,
      maxZoom: d.maxZoom ?? 19, attribution: d.attribution?.trim() || null, dark: !!d.dark,
    }
    return editingId.value ? updateMapProvider(editingId.value, body) : createMapProvider(body)
  },
  onSuccess: () => { invalidate(); push.success({ title: editingId.value ? 'Provider updated' : 'Provider added' }); formOpen.value = false; editingId.value = null },
  onError: () => push.error('Save failed — raster needs a tile URL, vector needs a style URL'),
})
const deleteMut = useMutation({
  mutationFn: (id: string) => deleteMapProvider(id),
  onSuccess: () => { invalidate(); push.success({ title: 'Provider deleted' }) },
  onError: () => push.error('Delete failed'),
})

const canSave = () => !!draft.value.name.trim() &&
  (draft.value.type === 'raster' ? !!draft.value.urlTemplate?.trim() : !!draft.value.styleUrl?.trim())
</script>

<template>
  <div class="w-full space-y-4">
    <p class="text-[11px] text-muted-fg">
      Add your own basemaps. <strong>Raster</strong> needs an XYZ tile URL like
      <code>https://tile.example.org/{z}/{x}/{y}.png</code> (loaded through the caching proxy).
      <strong>Vector</strong> needs a MapLibre style JSON URL. They appear in the map's basemap picker.
    </p>

    <div v-if="isPending" class="animate-pulse flex flex-col gap-2">
      <div v-for="i in 2" :key="i" class="h-10 bg-muted/20 rounded"></div>
    </div>
    <div v-else-if="!providers || providers.length === 0" class="text-sm text-muted-fg p-4 border border-dashed border-border rounded text-center">
      No custom providers yet. The built-in basemaps (OSM, Carto, ESRI, …) are always available.
    </div>
    <div v-else class="space-y-2">
      <div v-for="p in providers" :key="p.id!" class="flex items-center justify-between gap-2 p-3 bg-muted/10 border border-border rounded text-sm">
        <div class="flex items-center gap-2.5 min-w-0">
          <MapIcon :size="15" class="text-muted-fg shrink-0" />
          <div class="min-w-0">
            <div class="font-medium text-foreground truncate">{{ p.name }}</div>
            <div class="text-[11px] text-muted-fg truncate">
              <span class="uppercase tracking-wide">{{ p.type }}</span>
              · {{ p.type === 'raster' ? p.urlTemplate : p.styleUrl }}
            </div>
          </div>
        </div>
        <div class="flex items-center gap-0.5 shrink-0">
          <button class="btn-icon" title="Edit" @click="openEdit(p)"><Pencil :size="14" /></button>
          <button class="btn-icon btn-icon-danger" title="Delete" @click="deleteMut.mutate(p.id!)"><Trash2 :size="14" /></button>
        </div>
      </div>
    </div>

    <button v-if="!formOpen" class="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium rounded border border-border hover:border-primary hover:text-primary" @click="openAdd">
      <Plus :size="14" /> Add map provider
    </button>

    <form v-else @submit.prevent="saveMut.mutate()" class="p-4 border border-border bg-background rounded space-y-2">
      <h3 class="font-medium text-sm">{{ editingId ? 'Edit' : 'Add' }} map provider</h3>
      <div class="grid grid-cols-2 gap-3">
        <input v-model="draft.name" required placeholder="Name (e.g. OpenTopoMap)"
          class="px-3 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary" />
        <select v-model="draft.type"
          class="px-3 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary">
          <option value="raster">raster (XYZ tiles)</option>
          <option value="vector">vector (style JSON)</option>
        </select>
      </div>
      <input v-if="draft.type === 'raster'" v-model="draft.urlTemplate" placeholder="https://…/{z}/{x}/{y}.png"
        class="w-full px-3 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary font-mono text-[12px]" />
      <input v-else v-model="draft.styleUrl" placeholder="https://…/style.json"
        class="w-full px-3 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary font-mono text-[12px]" />
      <input v-model="draft.attribution" placeholder="Attribution (HTML allowed, optional)"
        class="w-full px-3 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary" />
      <div class="flex items-center gap-3">
        <label class="flex items-center gap-1.5 text-xs text-muted-fg">
          max zoom
          <input v-model.number="draft.maxZoom" type="number" min="1" max="22"
            class="w-16 px-2 py-1 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary" />
        </label>
        <label class="flex items-center gap-1.5 text-xs text-muted-fg">
          <input v-model="draft.dark" type="checkbox" class="accent-primary" /> dark basemap
        </label>
      </div>
      <div class="flex items-center gap-1.5">
        <button type="submit" :disabled="saveMut.isPending.value || !canSave()"
          class="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium rounded bg-primary text-primary-fg hover:bg-primary/90 disabled:opacity-50">
          <Check :size="13" /> {{ editingId ? 'Save' : 'Add' }}
        </button>
        <button type="button" class="inline-flex items-center gap-1 px-3 py-1.5 text-sm rounded border border-border text-muted-fg hover:text-foreground" @click="cancel">
          <X :size="13" /> Cancel
        </button>
      </div>
    </form>
  </div>
</template>
