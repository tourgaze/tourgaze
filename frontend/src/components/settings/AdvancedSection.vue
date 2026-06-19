<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { getSettings } from '@/api/client'

const { data: settings, isPending } = useQuery({ queryKey: ['settings'], queryFn: getSettings })
</script>

<template>
  <div class="max-w-3xl">
    <p class="text-xs text-muted-fg mb-3">Raw key/value pairs read from the <code>setting</code> table. Edit via Profile / Device / etc. — direct edits aren't possible here.</p>
    <div v-if="isPending" class="animate-pulse flex flex-col gap-2">
      <div v-for="i in 4" :key="i" class="h-8 bg-muted/20 rounded"></div>
    </div>
    <div v-else class="space-y-1">
      <div v-for="s in settings" :key="s.key"
        class="p-2 bg-muted/10 border border-border rounded flex items-center gap-3 text-xs">
        <span class="font-mono text-muted-fg w-64 flex-shrink-0">{{ s.key }}</span>
        <span class="font-mono break-all">{{ s.value || '—' }}</span>
      </div>
    </div>
  </div>
</template>
