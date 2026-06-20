<script setup lang="ts">
import { WifiOff, Loader2 } from 'lucide-vue-next'
</script>

<template>
  <!-- Full-screen overlay shown while the backend is unreachable. Blocks the UI
       (which would otherwise be empty/erroring) and auto-recovers when the
       health poll succeeds again. -->
  <div class="fixed inset-0 z-[9999] flex items-center justify-center bg-background/80 backdrop-blur-sm">
    <div class="flex flex-col items-center gap-3 rounded-xl border border-border bg-background px-8 py-7 shadow-2xl text-center max-w-sm">
      <WifiOff :size="30" class="text-red-500" />
      <div class="text-sm font-semibold">Can’t reach the server</div>
      <div class="text-xs text-muted-fg">
        The TourGaze backend isn’t responding — it may be starting up or stopped.
        Reconnecting automatically…
      </div>
      <Loader2 :size="16" class="animate-spin text-primary mt-1" />
    </div>
  </div>
</template>
