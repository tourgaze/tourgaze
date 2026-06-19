<script setup lang="ts">
import { ref } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { getUsers, createUser, updateUser, deleteUser, type User } from '@/api/client'
import { Trash2, Pencil, Check, X } from 'lucide-vue-next'

const qc = useQueryClient()
const { data: users, isPending } = useQuery({ queryKey: ['users'], queryFn: getUsers })

const newUsername = ref('')
const newDisplayName = ref('')

const createMut = useMutation({
  mutationFn: () => createUser({ username: newUsername.value, displayName: newDisplayName.value } as any),
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['users'] })
    push.success({ title: 'User created' })
    newUsername.value = ''
    newDisplayName.value = ''
  },
  onError: () => push.error('Failed to create user'),
})

// ── Inline edit (username + display name; profile fields live in Profile) ─────
const editingId = ref<string | null>(null)
const editUsername = ref('')
const editDisplayName = ref('')

function startEdit(u: User) {
  editingId.value = u.id!
  editUsername.value = u.username ?? ''
  editDisplayName.value = u.displayName ?? ''
}
function cancelEdit() { editingId.value = null }

const updateMut = useMutation({
  mutationFn: () => {
    // Spread the original so the PUT preserves DOB / height / weight / HR /
    // gender — the backend overwrites every column from the DTO.
    const orig = (users.value ?? []).find(u => u.id === editingId.value)
    return updateUser(editingId.value!, {
      ...(orig as User),
      username: editUsername.value.trim(),
      displayName: editDisplayName.value.trim() || null,
    } as any)
  },
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['users'] })
    push.success({ title: 'User updated' })
    editingId.value = null
  },
  onError: () => push.error('Failed to update user'),
})

const deleteMut = useMutation({
  mutationFn: (id: string) => deleteUser(id),
  onSuccess: () => { qc.invalidateQueries({ queryKey: ['users'] }); push.success({ title: 'User deleted' }) },
  onError: () => push.error('Failed to delete user'),
})
</script>

<template>
  <div class="w-full space-y-4">
    <div v-if="isPending" class="animate-pulse flex flex-col gap-2">
      <div v-for="i in 2" :key="i" class="h-10 bg-muted/20 rounded"></div>
    </div>
    <div v-else class="space-y-2">
      <div v-for="u in users" :key="u.id!" class="p-3 bg-muted/10 border border-border rounded text-sm">
        <!-- Display row -->
        <div v-if="editingId !== u.id" class="flex items-center justify-between gap-2">
          <div class="min-w-0">
            <div class="font-medium text-foreground truncate">{{ u.displayName || u.username }}</div>
            <div class="text-[11px] text-muted-fg truncate">@{{ u.username }}</div>
          </div>
          <div class="flex items-center gap-0.5 shrink-0">
            <button class="btn-icon" title="Edit" @click="startEdit(u)"><Pencil :size="14" /></button>
            <button class="btn-icon btn-icon-danger" title="Delete" @click="deleteMut.mutate(u.id!)"><Trash2 :size="14" /></button>
          </div>
        </div>

        <!-- Edit row -->
        <form v-else class="space-y-2" @submit.prevent="updateMut.mutate()">
          <div class="grid grid-cols-2 gap-2">
            <input v-model="editUsername" required placeholder="username"
              class="px-2.5 py-1.5 text-sm rounded border border-border bg-background focus:outline-none focus:border-primary" />
            <input v-model="editDisplayName" placeholder="Display name"
              class="px-2.5 py-1.5 text-sm rounded border border-border bg-background focus:outline-none focus:border-primary" />
          </div>
          <div class="flex items-center gap-1.5">
            <button type="submit" :disabled="updateMut.isPending.value || !editUsername.trim()"
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
      <h3 class="font-medium text-sm">Add user</h3>
      <div class="grid grid-cols-2 gap-3">
        <input v-model="newUsername" required placeholder="username"
          class="px-3 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary" />
        <input v-model="newDisplayName" placeholder="Display name"
          class="px-3 py-1.5 text-sm rounded border border-border bg-transparent focus:outline-none focus:border-primary" />
      </div>
      <button type="submit" :disabled="createMut.isPending.value || !newUsername"
        class="px-4 py-1.5 bg-primary text-primary-fg text-sm font-medium rounded hover:bg-primary/90 disabled:opacity-50">
        Create
      </button>
    </form>
  </div>
</template>
