<script setup lang="ts">
import { ref } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { getGear, createGear, updateGear, deleteGear, type Gear } from '@/api/client'
import { Trash2, Bike, Pencil, Check, X } from 'lucide-vue-next'
import { GEAR_ICONS, gearIconSvg } from '@/gearIcons'

// Free-form on the backend; these are the common ones we suggest in the form.
const GEAR_TYPES = ['bike', 'shoes', 'wetsuit', 'other'] as const

const qc = useQueryClient()
const { data: gear, isPending } = useQuery({ queryKey: ['gear'], queryFn: () => getGear() })

const newName = ref('')
const newType = ref<string>('bike')
const newDescription = ref('')
const newIcon = ref<string>('')

const createMut = useMutation({
  mutationFn: () => createGear({
    name: newName.value.trim(),
    type: newType.value || null,
    description: newDescription.value.trim() || null,
    icon: newIcon.value || null,
  }),
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['gear'] })
    push.success({ title: 'Gear added' })
    newName.value = ''
    newType.value = 'bike'
    newDescription.value = ''
    newIcon.value = ''
  },
  onError: () => push.error('Failed to add gear'),
})

// ── Inline edit ──────────────────────────────────────────────────────────────
const editingId = ref<string | null>(null)
const editName = ref('')
const editType = ref<string>('bike')
const editDescription = ref('')
const editIcon = ref<string>('')

function startEdit(g: Gear) {
  editingId.value = g.id!
  editName.value = g.name ?? ''
  editType.value = g.type ?? 'other'
  editDescription.value = g.description ?? ''
  editIcon.value = g.icon ?? ''
}
function cancelEdit() { editingId.value = null }

const updateMut = useMutation({
  mutationFn: () => {
    const orig = (gear.value ?? []).find(g => g.id === editingId.value)
    return updateGear(editingId.value!, {
      name: editName.value.trim(),
      type: editType.value || null,
      description: editDescription.value.trim() || null,
      icon: editIcon.value || null,
      // Preserve fields the form doesn't expose so the PUT doesn't wipe them.
      userId: orig?.userId ?? null,
      retiredAt: orig?.retiredAt ?? null,
    })
  },
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['gear'] })
    push.success({ title: 'Gear updated' })
    editingId.value = null
  },
  onError: () => push.error('Failed to update gear'),
})

const deleteMut = useMutation({
  mutationFn: (id: string) => deleteGear(id),
  onSuccess: () => { qc.invalidateQueries({ queryKey: ['gear'] }); push.success({ title: 'Gear deleted' }) },
  onError: () => push.error('Failed to delete gear'),
})
</script>

<template>
  <div class="w-full space-y-4">
    <div v-if="isPending" class="animate-pulse flex flex-col gap-2">
      <div v-for="i in 2" :key="i" class="h-10 bg-muted/20 rounded"></div>
    </div>
    <div v-else-if="!gear || gear.length === 0" class="text-sm text-muted-fg p-4 border border-dashed border-border rounded text-center">
      No gear yet. Add a bike or pair of shoes below — it'll show up when adding a tour.
    </div>
    <div v-else class="space-y-2">
      <div v-for="g in gear" :key="g.id!" class="p-3 bg-muted/10 border border-border rounded text-sm">
        <!-- Display row -->
        <div v-if="editingId !== g.id" class="flex items-center justify-between gap-2">
          <div class="flex items-center gap-2.5 min-w-0">
            <span v-if="g.icon" class="text-primary shrink-0 inline-flex" v-html="gearIconSvg(g.icon, 16)"></span>
            <Bike v-else :size="15" class="text-muted-fg shrink-0" />
            <div class="min-w-0">
              <div class="font-medium text-foreground truncate">{{ g.name }}</div>
              <div class="text-[11px] text-muted-fg truncate">
                <span v-if="g.type" class="uppercase tracking-wide">{{ g.type }}</span>
                <span v-if="g.type && g.description"> · </span>
                <span v-if="g.description">{{ g.description }}</span>
              </div>
            </div>
          </div>
          <div class="flex items-center gap-0.5 shrink-0">
            <button class="btn-icon" title="Edit" @click="startEdit(g)"><Pencil :size="14" /></button>
            <button class="btn-icon btn-icon-danger" title="Delete" @click="deleteMut.mutate(g.id!)"><Trash2 :size="14" /></button>
          </div>
        </div>

        <!-- Edit row -->
        <form v-else class="space-y-2" @submit.prevent="updateMut.mutate()">
          <div class="grid grid-cols-2 gap-2">
            <input v-model="editName" required placeholder="Name"
              class="px-2.5 py-1.5 text-sm rounded border border-border bg-background focus:outline-none focus:border-primary" />
            <select v-model="editType"
              class="px-2.5 py-1.5 text-sm rounded border border-border bg-background focus:outline-none focus:border-primary">
              <option v-for="t in GEAR_TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
          <input v-model="editDescription" placeholder="Description (optional)"
            class="w-full px-2.5 py-1.5 text-sm rounded border border-border bg-background focus:outline-none focus:border-primary" />
          <div class="flex items-center gap-1.5 flex-wrap">
            <span class="text-[11px] text-muted-fg mr-0.5">Icon</span>
            <button type="button" title="None" @click="editIcon = ''"
              class="w-7 h-7 inline-flex items-center justify-center rounded border text-[11px] text-muted-fg"
              :class="editIcon === '' ? 'border-primary text-primary bg-primary/10' : 'border-border'">—</button>
            <button v-for="gi in GEAR_ICONS" :key="gi.key" type="button" :title="gi.label" @click="editIcon = gi.key"
              class="w-7 h-7 inline-flex items-center justify-center rounded border"
              :class="editIcon === gi.key ? 'border-primary text-primary bg-primary/10' : 'border-border text-muted-fg'"
              v-html="gearIconSvg(gi.key, 16)"></button>
          </div>
          <div class="flex items-center gap-1.5">
            <button type="submit" :disabled="updateMut.isPending.value || !editName.trim()"
              class="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium rounded bg-primary text-primary-fg hover:bg-primary/90 disabled:opacity-50">
              <Check :size="13" /> Save
            </button>
            <button type="button" class="inline-flex items-center gap-1 px-3 py-1.5 text-sm rounded border border-border text-muted-fg hover:text-foreground"
              @click="cancelEdit">
              <X :size="13" /> Cancel
            </button>
          </div>
        </form>
      </div>
    </div>

    <form @submit.prevent="createMut.mutate()" class="p-4 border border-border bg-background rounded space-y-2">
      <h3 class="font-medium text-sm">Add gear</h3>
      <div class="grid grid-cols-2 gap-3">
        <input v-model="newName" required placeholder="e.g. Canyon Endurace"
          class="px-3 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary" />
        <select v-model="newType"
          class="px-3 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary">
          <option v-for="t in GEAR_TYPES" :key="t" :value="t">{{ t }}</option>
        </select>
      </div>
      <input v-model="newDescription" placeholder="Description (optional)"
        class="w-full px-3 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary" />
      <div class="flex items-center gap-1.5 flex-wrap">
        <span class="text-[11px] text-muted-fg mr-0.5">Icon</span>
        <button type="button" title="None" @click="newIcon = ''"
          class="w-7 h-7 inline-flex items-center justify-center rounded border text-[11px] text-muted-fg"
          :class="newIcon === '' ? 'border-primary text-primary bg-primary/10' : 'border-border'">—</button>
        <button v-for="gi in GEAR_ICONS" :key="gi.key" type="button" :title="gi.label" @click="newIcon = gi.key"
          class="w-7 h-7 inline-flex items-center justify-center rounded border"
          :class="newIcon === gi.key ? 'border-primary text-primary bg-primary/10' : 'border-border text-muted-fg'"
          v-html="gearIconSvg(gi.key, 16)"></button>
      </div>
      <button type="submit" :disabled="createMut.isPending.value || !newName.trim()"
        class="px-4 py-1.5 bg-primary text-primary-fg text-sm font-medium rounded hover:bg-primary/90 disabled:opacity-50">
        Add
      </button>
    </form>
  </div>
</template>
