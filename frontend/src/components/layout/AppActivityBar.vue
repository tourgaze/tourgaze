<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { Inbox, BarChart3, Settings, Info, MapPin } from 'lucide-vue-next'
import ClimbingGoat from '@/components/ClimbingGoat.vue'
import { getInbox } from '@/api/client'

const route = useRoute()
const router = useRouter()

const active = computed(() => {
  if (route.path.startsWith('/inbox')) return 'inbox'
  if (route.path.startsWith('/dashboard')) return 'dashboard'
  if (route.path.startsWith('/markers')) return 'markers'
  if (route.path.startsWith('/settings')) return 'settings'
  if (route.path.startsWith('/about')) return 'about'
  // /, /tours, and /tour/:id all land under the Tours nav button.
  return 'tours'
})

// Tiny badge with pending-inbox count.
const { data: inboxItems } = useQuery({
  queryKey: ['inbox'],
  queryFn: getInbox,
  refetchInterval: 5000,
})
const pendingCount = computed(() => inboxItems.value?.length ?? 0)

function navigate(to: 'inbox' | 'tours' | 'dashboard' | 'markers' | 'settings' | 'about') {
  router.push('/' + to)
}
</script>

<template>
  <aside class="w-14 flex-shrink-0 flex flex-col items-center py-3 justify-between z-30 border-r border-border bg-background shadow-sm h-full">
    <div class="flex flex-col items-center gap-1 w-full px-1">
      <button class="btn-icon w-10 h-10" :class="active === 'tours' ? 'nav-active' : ''" title="Tours (browse + filter + group)" @click="navigate('tours')">
        <ClimbingGoat :size="22" />
      </button>

      <button class="btn-icon w-10 h-10 relative" :class="active === 'inbox' ? 'nav-active' : ''" title="Inbox (pending review)" @click="navigate('inbox')">
        <Inbox :size="20" />
        <span v-if="pendingCount > 0"
          class="absolute -top-0.5 -right-0.5 min-w-[16px] h-[16px] px-1 rounded-full bg-amber-500 text-white text-[9px] font-bold leading-[16px] text-center">
          {{ pendingCount }}
        </span>
      </button>

      <button class="btn-icon w-10 h-10" :class="active === 'dashboard' ? 'nav-active' : ''" title="Dashboard (stats)" @click="navigate('dashboard')">
        <BarChart3 :size="20" />
      </button>

      <button class="btn-icon w-10 h-10" :class="active === 'markers' ? 'nav-active' : ''" title="Markers (all points of interest)" @click="navigate('markers')">
        <MapPin :size="20" />
      </button>
    </div>

    <div class="flex flex-col items-center gap-1 w-full px-1">
      <button class="btn-icon w-10 h-10" :class="active === 'about' ? 'nav-active' : ''" title="About TourGaze" @click="navigate('about')">
        <Info :size="20" />
      </button>
      <button class="btn-icon w-10 h-10" :class="active === 'settings' ? 'nav-active' : ''" title="Settings" @click="navigate('settings')">
        <Settings :size="20" />
      </button>
    </div>
  </aside>
</template>
