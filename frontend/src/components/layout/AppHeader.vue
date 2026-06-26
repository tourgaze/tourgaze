<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { push } from 'notivue'
import { onClickOutside, refDebounced } from '@vueuse/core'
import {
  Moon, Sun, UserCog, Users as UsersIcon, ChevronDown, LogOut, Save, RotateCcw, Search, MapPin,
} from 'lucide-vue-next'
import { useCurrentUser, setActiveUserId } from '@/composables/useCurrentUser'
import { useCurrentLayoutSaver, resetAllLayouts } from '@/composables/useLayoutState'
import { getActivities, getTags, type ActivitySummary, type Tag } from '@/api/client'
import UserSwitchDialog from '@/components/UserSwitchDialog.vue'
import GoatLogo from '@/components/GoatLogo.vue'

defineProps<{ dark: boolean }>()
defineEmits<{ toggleDark: [] }>()

const router = useRouter()
const route = useRoute()
const { user } = useCurrentUser()

// Global save / restore layout. Save is always available: when the active view
// registered a saver (Tours, Settings) we commit its gated state (filters,
// sections, pane sizes); on any other view there's no view-specific state to
// persist — pane folds/sizes there auto-save as you change them — so Save just
// confirms. Either way the button responds, never greys out. Restore wipes
// every persisted `tourgaze.layout.*` key (matrosdms convention: native confirm
// dialog so the user can't fat-finger it; reload picks up the defaults). They
// share a visual group so the destructive action sits next to the constructive.
const currentSaver = useCurrentLayoutSaver()
function runSave() {
  const saver = currentSaver.value
  if (saver) {
    saver.fn()
    push.success({ title: 'Layout saved', message: `${saver.label} pane sizes, filters and section remembered.` })
  } else {
    push.success({ title: 'Layout saved', message: 'This view has no extra layout to store — pane changes here save automatically.' })
  }
}
function runRestore() {
  if (!confirm('Reset layout to defaults? This clears saved pane sizes, sidebar widths and view-specific state across all perspectives.')) return
  resetAllLayouts()
  push.info({ title: 'Layout reset', message: 'Reload to apply.' })
}

// ── Global search ──────────────────────────────────────────────────────────
// Activities + tags in one place — match name, original filename, start /
// end location, sport, and tag name. Results live in a dropdown below the
// input so the user never leaves the header. Debounced so typing fast feels
// snappy.
const searchInput = ref<HTMLInputElement | null>(null)
const searchTerm = ref('')
const searchTermDebounced = refDebounced(searchTerm, 120)
const searchOpen = ref(false)
const searchBoxRef = ref<HTMLDivElement | null>(null)
onClickOutside(searchBoxRef, () => { searchOpen.value = false })

// Close the search dropdown on any route change. Without this, navigating
// from a search hit unmounts the route's components while the dropdown is
// still in the DOM — Vue's vnode reconciliation occasionally hits a stale
// parentNode in that race and throws the unmount errors we saw in the
// console ("Cannot read properties of null (reading 'parentNode')").
watch(() => route.fullPath, () => {
  searchOpen.value = false
  searchTerm.value = ''
})

const { data: searchActivities } = useQuery({
  queryKey: ['activities'],
  queryFn: getActivities,
  staleTime: 60_000,
})
const { data: searchTags } = useQuery({
  queryKey: ['tags'],
  queryFn: getTags,
  staleTime: 60_000,
})
type SearchHit =
  | { kind: 'activity'; activity: ActivitySummary; matched: string }
  | { kind: 'tag'; tag: Tag; activityCount: number }

const searchResults = computed<SearchHit[]>(() => {
  const q = searchTermDebounced.value.trim().toLowerCase()
  if (q.length < 2) return []
  const hits: SearchHit[] = []
  for (const a of searchActivities.value ?? []) {
    const buckets = [
      { val: a.name, label: a.name },
      { val: a.originalFilename, label: a.originalFilename },
      { val: a.startLocation, label: a.startLocation },
      { val: a.endLocation, label: a.endLocation },
      { val: a.activityType, label: a.activityType },
    ]
    for (const b of buckets) {
      if (b.val && String(b.val).toLowerCase().includes(q)) {
        hits.push({ kind: 'activity', activity: a, matched: b.label as string })
        break
      }
    }
  }
  for (const t of searchTags.value ?? []) {
    if (t.name && t.name.toLowerCase().includes(q)) {
      const count = (searchActivities.value ?? []).filter(a => (a.tagIds ?? []).includes(t.id!)).length
      hits.push({ kind: 'tag', tag: t, activityCount: count })
    }
  }
  return hits.slice(0, 30)
})

function gotoActivity(id?: string | null) {
  if (!id) return
  searchOpen.value = false
  searchTerm.value = ''
  router.push(`/tour/${id}`)
}
function gotoTag(tagId?: string | null) {
  if (!tagId) return
  searchOpen.value = false
  searchTerm.value = ''
  // Tours view supports a tag-filter via query string. Even if it doesn't
  // yet, this hands the user to the right page with the tag pre-populated.
  router.push({ path: '/tours', query: { tag: tagId } })
}
function onSearchKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    searchOpen.value = false
    searchInput.value?.blur()
  } else if (e.key === 'Enter' && searchResults.value.length) {
    const first = searchResults.value[0]
    if (first.kind === 'activity') gotoActivity(first.activity.id)
    else gotoTag(first.tag.id)
  }
}

const menuOpen = ref(false)
const menuRef = ref<HTMLDivElement | null>(null)
onClickOutside(menuRef, () => { menuOpen.value = false })

const switchOpen = ref(false)
function openSwitch() { menuOpen.value = false; switchOpen.value = true }
function editProfile() { menuOpen.value = false; router.push('/settings?cat=profile') }
function signOut() {
  menuOpen.value = false
  setActiveUserId(null)
  switchOpen.value = true
}
</script>

<template>
  <header class="flex items-center min-h-[52px] px-4 gap-3 bg-background border-b border-border shadow-sm flex-shrink-0 z-20">
    <div class="flex items-center gap-2 select-none">
      <GoatLogo :size="24" class="text-primary" />
      <div class="leading-tight">
        <div class="text-base font-bold tracking-tight text-foreground">TourGaze</div>
        <div class="text-[10px] text-muted-fg hidden sm:block">io.github.tourgaze</div>
      </div>
    </div>

    <!-- ── Global search ──────────────────────────────────────────────── -->
    <!-- Anchored to the left side of the header's slack space (not flex-1,
         so it doesn't grow to the right and centre the rest of the controls).
         The explicit spacer below the search pushes the layout group + user
         menu hard to the right edge. -->
    <div ref="searchBoxRef" class="relative w-full max-w-md">
      <div class="relative">
        <Search :size="13" class="absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-fg pointer-events-none" />
        <input
          ref="searchInput"
          v-model="searchTerm"
          type="text"
          placeholder="Search tours, places, tags…"
          class="w-full bg-muted/30 border border-transparent focus:border-primary focus:bg-background rounded-md pl-7 pr-3 py-1 text-xs outline-none transition-colors"
          @focus="searchOpen = true"
          @keydown="onSearchKey"
        />
      </div>

      <!-- Explicit `key` on both branches so Vue treats them as distinct
           nodes and won't try to patch-in-place between results and the
           empty-state — which was contributing to the unmount race when
           navigation fired right as the dropdown re-rendered. -->
      <div v-if="searchOpen && searchTermDebounced.trim().length >= 2 && searchResults.length"
        key="search-results"
        class="absolute top-full left-0 right-0 mt-1 rounded-md border border-border bg-background shadow-lg z-[2000] py-1 text-xs max-h-[60vh] overflow-y-auto">
        <button v-for="hit in searchResults"
          :key="hit.kind === 'activity' ? `a:${hit.activity.id}` : `t:${hit.tag.id}`"
          type="button"
          class="w-full flex items-start gap-2 px-3 py-1.5 text-left hover:bg-muted/40 transition-colors"
          @click="hit.kind === 'activity' ? gotoActivity(hit.activity.id) : gotoTag(hit.tag.id)">
          <MapPin v-if="hit.kind === 'activity'" :size="12" class="text-primary mt-0.5 shrink-0" />
          <span v-else class="w-2.5 h-2.5 rounded-sm mt-1 shrink-0"
            :style="{ backgroundColor: hit.tag.color || '#9ca3af' }" />

          <div class="flex-1 min-w-0">
            <template v-if="hit.kind === 'activity'">
              <div class="truncate font-medium text-foreground">{{ hit.activity.name ?? hit.matched }}</div>
              <div class="text-[10px] text-muted-fg flex gap-1.5 truncate">
                <span v-if="hit.activity.startLocation">{{ hit.activity.startLocation }}</span>
                <span v-if="hit.activity.activityType">· {{ hit.activity.activityType }}</span>
                <span v-if="hit.activity.distanceKm">· {{ hit.activity.distanceKm.toFixed(1) }} km</span>
              </div>
            </template>
            <template v-else>
              <div class="truncate font-medium text-foreground">{{ hit.tag.name }}</div>
              <div class="text-[10px] text-muted-fg">tag · {{ hit.activityCount }} ride{{ hit.activityCount === 1 ? '' : 's' }}</div>
            </template>
          </div>
        </button>
      </div>
      <div v-else-if="searchOpen && searchTermDebounced.trim().length >= 2 && !searchResults.length"
        key="search-empty"
        class="absolute top-full left-0 right-0 mt-1 rounded-md border border-border bg-background shadow-lg z-[2000] py-3 px-3 text-[11px] text-muted-fg text-center">
        No matches for &ldquo;{{ searchTermDebounced }}&rdquo;
      </div>
    </div>

    <!-- Spacer that pushes everything from here onward to the right edge. -->
    <div class="flex-1" />

    <!-- Layout group: Save + Restore as one control group. Save is always
         enabled — it commits the active view's layout when there is one, and
         otherwise just confirms (pane state on those views auto-saves). -->
    <div class="inline-flex items-center rounded border border-border overflow-hidden">
      <button
        class="inline-flex items-center gap-1 px-2 py-1 text-xs font-medium text-muted-fg hover:text-foreground hover:bg-muted/40 transition-colors border-r border-border"
        :title="currentSaver
          ? `Save ${currentSaver.label} layout (pane sizes, filters, current section)`
          : 'Save the current layout'"
        @click="runSave"
      >
        <Save :size="13" />
        <span class="hidden md:inline">Save</span>
      </button>
      <button
        class="inline-flex items-center gap-1 px-2 py-1 text-xs font-medium text-muted-fg hover:text-amber-500 hover:bg-amber-500/10 transition-colors"
        title="Reset all layout state (pane sizes, sidebar widths, view sections) to defaults. Reload required."
        @click="runRestore"
      >
        <RotateCcw :size="13" />
        <span class="hidden md:inline">Restore</span>
      </button>
    </div>

    <!-- Current rider — click to open menu (switch / edit profile / sign out) -->
    <div v-if="user" ref="menuRef" class="relative">
      <button
        class="inline-flex items-center gap-1.5 px-2 py-1 rounded text-xs font-medium text-foreground hover:bg-muted/40 transition-colors"
        :class="menuOpen ? 'bg-muted/40' : ''"
        :title="user.username ?? ''"
        @click="menuOpen = !menuOpen"
      >
        <UserCog :size="14" class="text-muted-fg" />
        <span>{{ user.displayName || user.username }}</span>
        <ChevronDown :size="11" class="opacity-60" />
      </button>

      <div v-if="menuOpen"
        class="absolute right-0 mt-1 w-52 rounded-md border border-border bg-background shadow-lg z-[2000] py-1 text-sm"
        @click.stop
      >
        <div class="px-3 py-2 text-[10px] text-muted-fg border-b border-border uppercase tracking-wide">
          Signed in as <span class="text-foreground font-medium normal-case">{{ user.username }}</span>
        </div>
        <button class="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-muted/50" @click="openSwitch">
          <UsersIcon :size="14" /> <span>Switch rider…</span>
        </button>
        <button class="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-muted/50" @click="editProfile">
          <UserCog :size="14" /> <span>Edit profile</span>
        </button>
        <div class="border-t border-border my-1" />
        <button class="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-muted/50 text-muted-fg" @click="signOut">
          <LogOut :size="14" /> <span>Sign out</span>
        </button>
      </div>
    </div>

    <button
      class="btn-icon"
      :title="dark ? 'Switch to light mode' : 'Switch to dark mode'"
      @click="$emit('toggleDark')"
    >
      <Moon v-if="!dark" :size="18" />
      <Sun v-else :size="18" />
    </button>
  </header>

  <UserSwitchDialog :open="switchOpen" @close="switchOpen = false" />
</template>
