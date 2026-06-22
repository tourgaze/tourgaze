<script setup lang="ts">
import { computed, markRaw, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { Splitpanes, Pane } from 'splitpanes'
import { push } from 'notivue'
import {
  UserCog, Tag as TagIcon, Users, HardDrive, Sliders, Globe, Bike, Map as MapIcon,
  Inbox as InboxIcon, PanelLeftClose, PanelLeftOpen, Medal,
} from 'lucide-vue-next'

import ProfileSection from '@/components/settings/ProfileSection.vue'
import InboxSection from '@/components/settings/InboxSection.vue'
import TagTree from '@/components/TagTree.vue'
import UsersSection from '@/components/settings/UsersSection.vue'
import GearSection from '@/components/settings/GearSection.vue'
import SportsSection from '@/components/settings/SportsSection.vue'
import MapProvidersSection from '@/components/settings/MapProvidersSection.vue'
import StorageSection from '@/components/settings/StorageSection.vue'
import LocaleSection from '@/components/settings/LocaleSection.vue'
import AdvancedSection from '@/components/settings/AdvancedSection.vue'

import { SETTINGS_LAYOUT_SLOT, layoutRef, autoLayoutRef, registerLayoutSaver } from '@/composables/useLayoutState'

type CategoryId = 'profile' | 'inbox' | 'tags' | 'users' | 'gear' | 'sports' | 'maps' | 'locale' | 'storage' | 'advanced'
type Category = { id: CategoryId; label: string; icon: any; component: any; description: string }

const CATEGORIES: Category[] = [
  { id: 'profile',  label: 'Profile',        icon: UserCog,   component: markRaw(ProfileSection),  description: 'Your rider profile, used for HR zones and per-activity stats.' },
  { id: 'inbox',    label: 'Inbox folders',  icon: InboxIcon, component: markRaw(InboxSection),    description: 'Folders (Garmin mount, Google Drive OpenTracks sync, …) scanned for ride files.' },
  { id: 'tags',     label: 'Tags',           icon: TagIcon,   component: markRaw(TagTree),         description: 'Hierarchical labels you can apply to tours.' },
  { id: 'users',    label: 'Users',          icon: Users,     component: markRaw(UsersSection),    description: 'Riders sharing this install. Activities link to one.' },
  { id: 'gear',     label: 'Gear',           icon: Bike,      component: markRaw(GearSection),     description: 'Bikes, shoes and other gear you can attach to a tour.' },
  { id: 'sports',   label: 'Sports',         icon: Medal,     component: markRaw(SportsSection),   description: 'Activity types (Garmin-aligned). Add the sports you do, hide the rest.' },
  { id: 'maps',     label: 'Map providers',  icon: MapIcon,   component: markRaw(MapProvidersSection), description: 'Custom basemaps (raster XYZ or vector style) shown in the map picker.' },
  { id: 'locale',   label: 'Language',       icon: Globe,     component: markRaw(LocaleSection),   description: 'Reverse-geocoding language for place names, plus your timezone.' },
  { id: 'storage',  label: 'Storage',        icon: HardDrive, component: markRaw(StorageSection),  description: 'Disk usage breakdown + cache maintenance.' },
  { id: 'advanced', label: 'Advanced',       icon: Sliders,   component: markRaw(AdvancedSection), description: 'Raw key/value settings as stored in the DB.' },
]

const activeId = layoutRef<CategoryId>(SETTINGS_LAYOUT_SLOT, 'activeCategory', 'profile')

// Deep-link support: /settings?cat=gear opens that category (used by the
// AddTour empty-state "add gear" link). Falls back to the persisted choice.
const route = useRoute()
const requestedCat = route.query.cat
if (typeof requestedCat === 'string' && CATEGORIES.some(c => c.id === requestedCat)) {
  activeId.value = requestedCat as CategoryId
}
// auto-persist the sidebar separator / collapse (Save-layout still snapshots them).
const sidebarSize = autoLayoutRef<number>(SETTINGS_LAYOUT_SLOT, 'sidebarSize', 22)
const sidebarCollapsed = autoLayoutRef<boolean>(SETTINGS_LAYOUT_SLOT, 'sidebarCollapsed', false)

const active = computed<Category>(() =>
  CATEGORIES.find(c => c.id === activeId.value) ?? CATEGORIES[0],
)

// splitpanes v4 emits { panes: [...] }; older builds emit the array directly.
type ResizePayload = { panes?: Array<{ size: number }> }
function onResize(e: ResizePayload | Array<{ size: number }>) {
  const panes = Array.isArray(e) ? e : (e?.panes ?? [])
  if (!sidebarCollapsed.value && panes[0]?.size != null) sidebarSize.value = panes[0].size
}

function toggleSidebar() { sidebarCollapsed.value = !sidebarCollapsed.value }

function saveLayout() {
  SETTINGS_LAYOUT_SLOT.save({
    sidebarSize: sidebarSize.value,
    sidebarCollapsed: sidebarCollapsed.value,
    activeCategory: activeId.value,
  })
  push.success({ title: 'Settings layout saved' })
}

// Hoist saveLayout to the AppHeader. Registered on mount, cleared on unmount.
let unregisterSaver: (() => void) | null = null
onMounted(() => { unregisterSaver = registerLayoutSaver('Settings', saveLayout) })
onUnmounted(() => { unregisterSaver?.(); unregisterSaver = null })

const effectiveSidebarSize = computed(() => sidebarCollapsed.value ? 2.4 : sidebarSize.value)
</script>

<template>
  <Splitpanes class="h-full w-full" @resize="onResize">

    <!-- ── Sidebar: category nav ────────────────────────────────────────── -->
    <Pane :size="effectiveSidebarSize" :min-size="2.4" :max-size="40"
      class="flex flex-col bg-background border-r border-border overflow-hidden">

      <button v-if="sidebarCollapsed"
        class="h-full w-full flex items-start justify-center pt-3 text-muted-fg hover:text-foreground"
        title="Show categories"
        @click="toggleSidebar">
        <PanelLeftOpen :size="16" />
      </button>

      <template v-else>
        <div class="flex items-center gap-1 px-2 py-1.5 border-b border-border">
          <button class="btn-icon" title="Hide categories" @click="toggleSidebar"><PanelLeftClose :size="14" /></button>
          <span class="text-[10px] text-muted-fg font-semibold uppercase tracking-wide ml-1">Settings</span>
        </div>

        <nav class="flex-1 overflow-y-auto py-1">
          <button v-for="c in CATEGORIES" :key="c.id" type="button"
            class="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-muted/40 transition-colors text-left"
            :class="activeId === c.id ? 'bg-primary/10 text-primary border-l-2 border-primary' : 'text-foreground border-l-2 border-transparent'"
            @click="activeId = c.id">
            <component :is="c.icon" :size="16" :class="activeId === c.id ? 'text-primary' : 'text-muted-fg'" />
            <span class="font-medium">{{ c.label }}</span>
          </button>
        </nav>
      </template>
    </Pane>

    <!-- ── Detail pane ──────────────────────────────────────────────────── -->
    <!-- Explicit size so collapsing the sidebar reclaims the full width. -->
    <Pane :size="100 - effectiveSidebarSize" class="overflow-hidden flex flex-col">
      <div class="px-4 py-2.5 border-b border-border flex items-center gap-3 bg-muted/10">
        <component :is="active.icon" :size="18" class="text-primary" />
        <div>
          <h2 class="text-sm font-semibold leading-tight">{{ active.label }}</h2>
          <p class="text-[10px] text-muted-fg leading-tight">{{ active.description }}</p>
        </div>
      </div>
      <div class="flex-1 overflow-y-auto p-4">
        <component :is="active.component" />
      </div>
    </Pane>
  </Splitpanes>
</template>
