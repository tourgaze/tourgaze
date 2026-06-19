<script setup lang="ts">
import { Notivue, Notification } from 'notivue'
</script>

<template>
  <RouterView />
  <Notivue v-slot="item">
    <Notification :item="item" />
  </Notivue>
</template>
