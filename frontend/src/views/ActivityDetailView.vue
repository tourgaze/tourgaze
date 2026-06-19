<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ActivityViewer from '@/components/ActivityViewer.vue'
import { ArrowLeft } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const activityId = computed(() => (route.params.id as string) || null)
</script>

<template>
  <div class="h-full flex flex-col overflow-hidden">
    <div class="flex items-center gap-2 px-3 py-1.5 border-b border-border bg-background">
      <button class="btn-icon" title="Back to tours" @click="router.push('/tours')"><ArrowLeft :size="14" /></button>
      <span class="text-[11px] text-muted-fg font-mono">{{ activityId }}</span>
    </div>
    <div class="flex-1 min-h-0">
      <ActivityViewer :activity-id="activityId" />
    </div>
  </div>
</template>
