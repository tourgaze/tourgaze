<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { Users as UsersIcon, Check, X, UserPlus } from 'lucide-vue-next'
import { getUsers, type User } from '@/api/client'
import { setActiveUserId, useCurrentUser } from '@/composables/useCurrentUser'
import { useEscapeClose } from '@/composables/useEscapeClose'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()
useEscapeClose(() => props.open, () => emit('close'))

const router = useRouter()
const { data: users, isPending } = useQuery({
  queryKey: ['users'],
  queryFn: getUsers,
  enabled: computed(() => props.open),
})
const { activeId } = useCurrentUser()

function pick(u: User) {
  setActiveUserId(u.id ?? null)
  emit('close')
}

function manageUsers() {
  emit('close')
  router.push('/settings')
}

function initials(u: User): string {
  const src = (u.displayName?.trim() || u.username || '').trim()
  if (!src) return '?'
  const parts = src.split(/\s+/)
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

function formatAge(dob: string | null | undefined): string {
  if (!dob) return ''
  const ms = Date.now() - new Date(dob).getTime()
  if (!Number.isFinite(ms) || ms <= 0) return ''
  const yrs = ms / (365.25 * 24 * 3600 * 1000)
  return `${Math.floor(yrs)} y`
}
</script>

<template>
  <div v-if="open" class="fixed inset-0 z-[4000] flex items-center justify-center bg-black/50 backdrop-blur-sm p-4"
    @click.self="emit('close')">
    <div class="w-full max-w-sm bg-background border border-border rounded-xl shadow-xl overflow-hidden flex flex-col max-h-[90vh]">
      <div class="flex items-center gap-2 px-4 py-3 border-b border-border">
        <UsersIcon :size="18" class="text-primary" />
        <h2 class="text-sm font-semibold">Switch rider</h2>
        <button class="btn-icon ml-auto" @click="emit('close')"><X :size="14" /></button>
      </div>

      <div class="flex-1 overflow-y-auto p-2">
        <div v-if="isPending" class="text-xs text-muted-fg p-4 text-center">Loading…</div>
        <div v-else-if="!users?.length" class="p-6 text-center text-xs text-muted-fg">
          No riders yet. Add one in Settings.
        </div>
        <button v-for="(u, idx) in (users ?? [])" :key="u.id!"
          class="w-full flex items-center gap-3 px-3 py-2 rounded hover:bg-muted/40 text-left transition-colors"
          :class="activeId === u.id || (!activeId && idx === 0) ? 'bg-primary/5 border border-primary/40' : 'border border-transparent'"
          @click="pick(u)">
          <span class="w-9 h-9 rounded-full bg-muted/40 text-foreground font-semibold text-sm flex items-center justify-center flex-shrink-0">
            {{ initials(u) }}
          </span>
          <div class="flex-1 min-w-0">
            <div class="text-sm font-medium truncate">{{ u.displayName || u.username }}</div>
            <div class="text-[11px] text-muted-fg flex gap-2">
              <span>@{{ u.username }}</span>
              <span v-if="u.dateOfBirth">· {{ formatAge(u.dateOfBirth) }}</span>
              <span v-if="u.gender">· {{ u.gender }}</span>
            </div>
          </div>
          <Check v-if="activeId === u.id || (!activeId && idx === 0)"
            :size="14" class="text-primary flex-shrink-0" />
        </button>
      </div>

      <div class="px-3 py-2 border-t border-border bg-muted/10 flex items-center justify-between">
        <button class="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-medium text-muted-fg hover:text-foreground"
          @click="manageUsers">
          <UserPlus :size="11" /> Manage riders
        </button>
        <span class="text-[10px] text-muted-fg">Active rider drives HR zones &amp; new imports</span>
      </div>
    </div>
  </div>
</template>
