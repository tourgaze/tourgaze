<script setup lang="ts">
import { Notivue, Notification } from 'notivue'
import { useQueryClient } from '@tanstack/vue-query'
import { useBackendHealth } from '@/composables/useBackendHealth'
import BackendOffline from '@/components/BackendOffline.vue'

// Show a graceful overlay when the backend is unreachable; refetch on reconnect.
const qc = useQueryClient()
const { online } = useBackendHealth(() => qc.invalidateQueries())
</script>

<template>
  <RouterView />
  <BackendOffline v-if="!online" />
  <Notivue v-slot="item">
    <Notification :item="item" />
  </Notivue>
</template>
