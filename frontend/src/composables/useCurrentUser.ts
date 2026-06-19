import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { getUsers, type User } from '@/api/client'

/**
 * Multi-rider state. There is no auth — "active user" is just whoever's
 * profile feeds HR zones, gets attached to imported activities, etc. The id is
 * persisted in localStorage so the choice survives reloads, and the ref is
 * **module-level** so every `useCurrentUser()` call sees the same value
 * (calling `setActiveUserId` from the switch dialog updates HrZones, the
 * header, the AddTour modal, all at once).
 */
const ACTIVE_USER_KEY = 'tourgaze.activeUserId'
const _activeId = ref<string | null>(localStorage.getItem(ACTIVE_USER_KEY))

export function setActiveUserId(id: string | null): void {
  _activeId.value = id
  if (id) localStorage.setItem(ACTIVE_USER_KEY, id)
  else localStorage.removeItem(ACTIVE_USER_KEY)
}

export function getActiveUserId(): string | null { return _activeId.value }

export function useCurrentUser() {
  const q = useQuery({
    queryKey: ['users'],
    queryFn: getUsers,
    staleTime: 60_000,
  })
  // Pick the active user if set + still exists; otherwise fall back to the
  // first user. Returning `null` is reserved for "DB has no users" — that
  // case is handled upstream by the /setup redirect.
  const user = computed<User | null>(() => {
    const list = q.data.value ?? []
    if (_activeId.value) {
      const found = list.find(u => u.id === _activeId.value)
      if (found) return found
    }
    return list[0] ?? null
  })
  const activeId = computed<string | null>(() => _activeId.value)
  return { user, activeId, isLoading: q.isPending, isError: q.isError }
}
