<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { Globe, Save } from 'lucide-vue-next'
import { getSettings, saveSetting } from '@/api/client'

const qc = useQueryClient()
const { data: settings } = useQuery({ queryKey: ['settings'], queryFn: getSettings })

// Backend default is 'de' when the setting row doesn't exist (per PredictionService.currentLanguage).
const LANG_KEY = 'app.language'
const language = ref<string>('de')
const dirty = ref(false)

// Timezone (IANA name) lives in `app.timezone`; used to render local times.
const timezone = ref('')
watch(settings, (list) => {
  if (!list || dirty.value) return
  const s = list.find(s => s.key === LANG_KEY)
  if (s?.value) language.value = s.value
  timezone.value = list.find(s => s.key === 'app.timezone')?.value ?? ''
}, { immediate: true })

const saveTimezoneMut = useMutation({
  mutationFn: () => saveSetting('app.timezone', timezone.value.trim()),
  onSuccess: () => { qc.invalidateQueries({ queryKey: ['settings'] }); push.success({ title: 'Timezone saved' }) },
  onError: () => push.error('Could not save timezone'),
})

const OPTIONS = [
  { code: 'de', label: 'Deutsch',    example: 'Mallorca, Spanien · München, Bayern' },
  { code: 'en', label: 'English',    example: 'Majorca, Spain · Munich, Bavaria' },
  { code: 'it', label: 'Italiano',   example: 'Maiorca, Spagna · Monaco di Baviera' },
  { code: 'fr', label: 'Français',   example: 'Majorque, Espagne · Munich, Bavière' },
  { code: 'es', label: 'Español',    example: 'Mallorca, España · Múnich, Baviera' },
]

const currentExample = computed(() => OPTIONS.find(o => o.code === language.value)?.example ?? '')

const saveMut = useMutation({
  mutationFn: () => saveSetting(LANG_KEY, language.value),
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['settings'] })
    dirty.value = false
    push.success({ title: 'Language saved' })
  },
  onError: () => push.error('Could not save language'),
})

function pick(code: string) {
  language.value = code
  dirty.value = true
}
</script>

<template>
  <div class="w-full space-y-4">
    <p class="text-xs text-muted-fg flex items-start gap-1.5">
      <Globe :size="13" class="mt-0.5 text-primary shrink-0" />
      <span>
        Language used when reverse-geocoding ride start / end locations from OpenStreetMap.
        Pick <strong>Deutsch</strong> if you want place names like
        <code>Mallorca, Spanien</code> instead of <code>Majorca, Spain</code>.
        Only affects predicted text — the rest of the UI is currently English-only.
      </span>
    </p>

    <div class="space-y-2">
      <div v-for="opt in OPTIONS" :key="opt.code"
        class="flex items-center gap-3 p-2.5 rounded border cursor-pointer transition-colors"
        :class="language === opt.code
          ? 'border-primary bg-primary/5'
          : 'border-border hover:bg-muted/30'"
        @click="pick(opt.code)">
        <div class="w-4 h-4 rounded-full border-2 flex items-center justify-center shrink-0"
          :class="language === opt.code ? 'border-primary' : 'border-border'">
          <div v-if="language === opt.code" class="w-2 h-2 rounded-full bg-primary" />
        </div>
        <div class="flex-1">
          <div class="text-sm font-medium">{{ opt.label }}</div>
          <div class="text-[10px] text-muted-fg font-mono">{{ opt.example }}</div>
        </div>
        <code class="text-[10px] text-muted-fg">{{ opt.code }}</code>
      </div>
    </div>

    <div class="flex items-center justify-between border-t border-border pt-3">
      <div class="text-[11px] text-muted-fg">
        Preview: <span class="text-foreground font-mono">{{ currentExample }}</span>
      </div>
      <button
        class="inline-flex items-center gap-1.5 px-3 py-1.5 bg-primary text-primary-fg text-xs font-medium rounded hover:bg-primary/90 disabled:opacity-50"
        :disabled="!dirty || saveMut.isPending.value"
        @click="saveMut.mutate()">
        <Save :size="12" /> {{ saveMut.isPending.value ? 'Saving…' : 'Save' }}
      </button>
    </div>

    <label class="block text-sm border-t border-border pt-4">
      <span class="text-xs font-medium text-muted-fg">Timezone</span>
      <p class="text-[10px] text-muted-fg mb-1 mt-0.5">
        IANA name (e.g. <code>Europe/Berlin</code>, <code>America/New_York</code>). Used to render
        local times for your rides.
      </p>
      <div class="flex gap-2">
        <input v-model="timezone" type="text" placeholder="Europe/Berlin"
          class="flex-1 mt-1 rounded-md border border-border bg-transparent px-3 py-2 text-sm font-mono focus:border-primary focus:outline-none" />
        <button class="mt-1 inline-flex items-center gap-1 px-3 py-2 bg-primary text-primary-fg text-xs font-medium rounded hover:bg-primary/90 disabled:opacity-50"
          :disabled="saveTimezoneMut.isPending.value" @click="saveTimezoneMut.mutate()">
          <Save :size="12" /> Save
        </button>
      </div>
    </label>
  </div>
</template>
