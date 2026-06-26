<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ActivityViewer from '@/components/ActivityViewer.vue'
import { ArrowLeft, Gauge } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const activityId = computed(() => (route.params.id as string) || null)
</script>

<template>
  <div class="h-full flex flex-col overflow-hidden">
    <div class="flex items-center gap-2 px-3 py-1.5 border-b border-border bg-background">
      <button class="btn-icon" title="Back to tours" @click="router.push('/tours')"><ArrowLeft :size="14" /></button>
      <span class="text-[11px] text-muted-fg font-mono">{{ activityId }}</span>
      <span class="flex-1" />
      <!-- Quiet escape hatch to the per-point raw data view; off to the side so it
           never competes with the map/replay. -->
      <router-link
        :to="`/tour/${activityId}/raw`"
        class="inline-flex items-center gap-1 text-[11px] text-muted-fg hover:text-primary transition-colors"
        title="Raw per-point data (elevation, HR, speed, cadence, power)"
      >
        <Gauge :size="12" />
        <span>Raw data</span>
      </router-link>
    </div>
    <div class="flex-1 min-h-0">
      <ActivityViewer :activity-id="activityId" />
    </div>
  </div>
</template>
