<script setup lang="ts">
import { ref, watch } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { getSettings, saveSetting } from '@/api/client'
import { Usb, Save } from 'lucide-vue-next'

const qc = useQueryClient()
const { data: settings } = useQuery({ queryKey: ['settings'], queryFn: getSettings })

const garminPath = ref('')
const timezone = ref('')
watch(settings, (list) => {
  garminPath.value = list?.find(s => s.key === 'garmin.device.path')?.value ?? ''
  timezone.value = list?.find(s => s.key === 'app.timezone')?.value ?? ''
}, { immediate: true })

const saveGarminMut = useMutation({
  mutationFn: () => saveSetting('garmin.device.path', garminPath.value.trim()),
  onSuccess: () => { qc.invalidateQueries({ queryKey: ['settings'] }); push.success({ title: 'Garmin path saved' }) },
  onError: () => push.error('Could not save'),
})
const saveTimezoneMut = useMutation({
  mutationFn: () => saveSetting('app.timezone', timezone.value.trim()),
  onSuccess: () => { qc.invalidateQueries({ queryKey: ['settings'] }); push.success({ title: 'Timezone saved' }) },
  onError: () => push.error('Could not save'),
})
</script>

<template>
  <div class="w-full space-y-5">
    <label class="block text-sm">
      <span class="text-xs font-medium text-muted-fg flex items-center gap-1"><Usb :size="11" /> Garmin device folder</span>
      <p class="text-[10px] text-muted-fg mb-1 mt-0.5">
        When you plug your Garmin in, Windows mounts it as a drive (e.g. <code>X:</code>). Point this at <code>X:\garmin\activity</code>.
        New .fit files there get copied into the inbox — the device is never modified.
      </p>
      <div class="flex gap-2">
        <input v-model="garminPath" type="text" placeholder="X:\garmin\activity"
          class="flex-1 mt-1 rounded-md border border-border bg-transparent px-3 py-2 text-sm font-mono focus:border-primary focus:outline-none" />
        <button class="mt-1 inline-flex items-center gap-1 px-3 py-2 bg-primary text-primary-fg text-xs font-medium rounded hover:bg-primary/90 disabled:opacity-50"
          :disabled="saveGarminMut.isPending.value" @click="saveGarminMut.mutate()">
          <Save :size="12" /> Save
        </button>
      </div>
    </label>

    <label class="block text-sm">
      <span class="text-xs font-medium text-muted-fg">Timezone</span>
      <p class="text-[10px] text-muted-fg mb-1 mt-0.5">IANA name (e.g. <code>Europe/Berlin</code>, <code>America/New_York</code>).</p>
      <div class="flex gap-2">
        <input v-model="timezone" type="text"
          class="flex-1 mt-1 rounded-md border border-border bg-transparent px-3 py-2 text-sm font-mono focus:border-primary focus:outline-none" />
        <button class="mt-1 inline-flex items-center gap-1 px-3 py-2 bg-primary text-primary-fg text-xs font-medium rounded hover:bg-primary/90 disabled:opacity-50"
          :disabled="saveTimezoneMut.isPending.value" @click="saveTimezoneMut.mutate()">
          <Save :size="12" /> Save
        </button>
      </div>
    </label>
  </div>
</template>
