<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { push } from 'notivue'
import { getUsers, updateUser, getActivities, type User } from '@/api/client'
import { estimateMaxHr, computeZones } from '@/composables/useHrZones'
import { Save, Heart, RefreshCw, Scale } from 'lucide-vue-next'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent])

const qc = useQueryClient()
const { data: users } = useQuery({ queryKey: ['users'], queryFn: getUsers })

// Body-weight trend: each ride records the rider's weight at import, so the
// activity history IS the weight log. Plot those points over time.
const { data: activities } = useQuery({ queryKey: ['activities'], queryFn: getActivities })
const weightPoints = computed<[number, number][]>(() => {
  const out: [number, number][] = []
  for (const a of activities.value ?? []) {
    if (a.weightKg != null && a.startTime) {
      const t = new Date(a.startTime).getTime()
      if (Number.isFinite(t)) out.push([t, a.weightKg])
    }
  }
  return out.sort((x, y) => x[0] - y[0])
})
const weightOption = computed(() => ({
  grid: { top: 10, left: 38, right: 12, bottom: 20 },
  xAxis: {
    type: 'time', axisLabel: { fontSize: 9, color: '#9ca3af' },
    axisLine: { show: false }, axisTick: { show: false },
  },
  yAxis: {
    type: 'value',
    // Hug the data range (just ±1 kg of headroom) instead of rounding out to
    // 0/100, and drop the distracting horizontal gridlines.
    min: (v: { min: number }) => Math.floor(v.min - 1),
    max: (v: { max: number }) => Math.ceil(v.max + 1),
    axisLabel: { fontSize: 9, color: '#9ca3af', formatter: '{value}' },
    axisLine: { show: false }, axisTick: { show: false },
    splitLine: { show: false },
  },
  tooltip: {
    trigger: 'axis',
    formatter: (p: any) => {
      const it = p[0]
      return `${new Date(it.value[0]).toLocaleDateString()}<br/><b>${it.value[1]} kg</b>`
    },
    backgroundColor: 'rgba(255,255,255,0.96)', borderColor: '#e5e7eb',
    textStyle: { color: '#374151', fontSize: 11 },
  },
  series: [{
    type: 'line', data: weightPoints.value, smooth: 0.2, symbol: 'circle', symbolSize: 4,
    lineStyle: { color: '#22c55e', width: 1.5 }, itemStyle: { color: '#22c55e' },
    areaStyle: {
      color: {
        type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [{ offset: 0, color: 'rgba(34,197,94,0.25)' }, { offset: 1, color: 'rgba(34,197,94,0.02)' }],
      },
    },
  }],
}))

const profile = ref<User>({} as User)
const dirty = ref(false)

watch(users, (list) => {
  if (list && list.length > 0 && !dirty.value) profile.value = { ...list[0] }
}, { immediate: true })

function markDirty() { dirty.value = true }

// ── Live-derived heart-rate info ───────────────────────────────────────────
// estimateMaxHr respects an explicit override on profile.maxHr — but for the
// "auto" display we want the value the formula *would* give, ignoring any
// override. Same for zones: we want to preview them in real time as DOB /
// gender / etc. change, even if the user's currently overriding maxHr.
const profileForAuto = computed<User>(() => ({ ...profile.value, maxHr: undefined } as unknown as User))
const autoMaxHr = computed(() => estimateMaxHr(profileForAuto.value))

const effectiveMaxHr = computed(() => estimateMaxHr(profile.value))
const previewZones = computed(() => computeZones(profile.value))

const ageYears = computed(() => {
  const dob = profile.value.dateOfBirth
  if (!dob) return null
  const ms = Date.now() - new Date(dob).getTime()
  if (!Number.isFinite(ms) || ms <= 0) return null
  return Math.floor(ms / (365.25 * 24 * 3600 * 1000))
})

// Which formula are we showing? Mirrors the priority order in useHrZones.
const formulaLabel = computed(() => {
  if (!profile.value.dateOfBirth) return 'fallback (no DOB)'
  if (profile.value.gender === 'female') return 'Gulati (female)'
  return 'Tanaka'
})

function useAutoMaxHr() {
  // Backend's PUT /api/users/{id} unconditionally writes whatever it gets,
  // so JSON `null` actually clears the column. The schema's TS type only
  // says `number | undefined`, hence the cast — see backend UserController.
  ;(profile.value as Record<string, unknown>).maxHr = null
  markDirty()
}

const isOverriding = computed(() => profile.value.maxHr != null && profile.value.maxHr !== autoMaxHr.value)

const saveMut = useMutation({
  mutationFn: () => updateUser(profile.value.id!, profile.value),
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['users'] })
    dirty.value = false
    push.success({ title: 'Profile saved' })
  },
  onError: () => push.error('Could not save profile'),
})

function fmtZoneRange(lo: number, hi: number) {
  return `${lo}–${hi}`
}
</script>

<template>
  <div v-if="profile.id" class="w-full space-y-4">
    <p class="text-xs text-muted-fg">Your rider profile drives HR-zone calculations and per-activity stats.</p>

    <!-- ── Master data ────────────────────────────────────────────────────── -->
    <div class="grid grid-cols-2 gap-3">
      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg">Display name</span>
        <input v-model="profile.displayName" @input="markDirty"
          class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
      </label>
      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg">Date of birth</span>
        <input v-model="profile.dateOfBirth" type="date" @input="markDirty"
          class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
      </label>
    </div>

    <div class="grid grid-cols-3 gap-3">
      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg">Gender</span>
        <select v-model="profile.gender" @change="markDirty"
          class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm">
          <option :value="null">—</option>
          <option value="male">Male</option>
          <option value="female">Female</option>
          <option value="other">Other</option>
        </select>
      </label>
      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg">Height (cm)</span>
        <input v-model.number="profile.heightCm" type="number" min="50" max="250" @input="markDirty"
          class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
      </label>
      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg">Weight (kg)</span>
        <input v-model.number="profile.weightKg" type="number" step="0.1" min="20" max="300" @input="markDirty"
          class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
      </label>
    </div>

    <!-- ── Body-weight trend ──────────────────────────────────────────────── -->
    <div class="border-t border-border pt-3">
      <div class="text-[11px] text-muted-fg flex items-center gap-1 mb-1.5">
        <Scale :size="11" class="text-emerald-500" />
        <span class="font-medium text-foreground">Body-weight trend</span>
        <span class="opacity-70">· from each ride's recorded weight</span>
      </div>
      <div v-if="weightPoints.length >= 2" class="h-32 w-full">
        <VChart class="w-full h-full" :option="weightOption" :autoresize="true" />
      </div>
      <div v-else class="text-[11px] text-muted-fg opacity-70 py-2">
        Not enough data yet — weight is recorded per ride, so the trend fills in as you import.
      </div>
    </div>

    <!-- ── Heart rate ─────────────────────────────────────────────────────── -->
    <div class="border-t border-border pt-3 space-y-3">
      <div class="text-[11px] text-muted-fg flex items-center gap-1">
        <Heart :size="11" class="text-red-500" />
        <span class="font-medium text-foreground">Heart rate</span>
        <span class="opacity-70">· auto-estimated from age + gender (Tanaka / Gulati). Override only if you have measured values.</span>
      </div>

      <!-- Live-derived auto value, visible whether or not the user has overridden it. -->
      <div class="flex items-center gap-3 p-3 rounded border border-border bg-muted/10">
        <div class="flex-1">
          <div class="text-[10px] text-muted-fg uppercase tracking-wide">Estimated max HR</div>
          <div class="text-lg font-semibold tabular-nums">
            {{ autoMaxHr }} <span class="text-xs font-normal text-muted-fg">bpm</span>
          </div>
          <div class="text-[10px] text-muted-fg">
            {{ formulaLabel }}{{ ageYears != null ? ` · age ${ageYears}` : '' }}
          </div>
        </div>
        <div v-if="profile.maxHr != null" class="flex-1 border-l border-border pl-3">
          <div class="text-[10px] text-muted-fg uppercase tracking-wide">Currently using</div>
          <div class="text-lg font-semibold tabular-nums" :class="isOverriding ? 'text-amber-500' : 'text-foreground'">
            {{ effectiveMaxHr }} <span class="text-xs font-normal text-muted-fg">bpm</span>
          </div>
          <div class="text-[10px]" :class="isOverriding ? 'text-amber-500' : 'text-muted-fg'">
            {{ isOverriding ? 'your override' : 'matches estimate' }}
          </div>
        </div>
        <button v-if="profile.maxHr != null"
          class="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded border border-border text-muted-fg hover:text-foreground self-start"
          title="Clear the override and use the auto-estimated value"
          @click="useAutoMaxHr">
          <RefreshCw :size="11" /> Use auto
        </button>
      </div>

      <div class="grid grid-cols-2 gap-3">
        <label class="block text-sm">
          <span class="text-xs font-medium text-muted-fg">Override max HR (bpm)</span>
          <input v-model.number="profile.maxHr" type="number" min="100" max="230" @input="markDirty"
            :placeholder="`${autoMaxHr} (auto)`"
            class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
        </label>
        <label class="block text-sm">
          <span class="text-xs font-medium text-muted-fg">Resting HR (bpm)</span>
          <input v-model.number="profile.restingHr" type="number" min="30" max="120" @input="markDirty"
            placeholder="optional — unlocks Karvonen zones"
            class="mt-1 block w-full rounded-md border border-border bg-transparent px-3 py-2 text-sm focus:border-primary focus:outline-none" />
        </label>
      </div>

      <!-- Live zone preview, recomputed as master data changes -->
      <div>
        <div class="text-[10px] text-muted-fg uppercase tracking-wide mb-1">Preview · 5 zones (live)</div>
        <div class="flex rounded overflow-hidden border border-border h-7 text-[10px]">
          <div v-for="z in previewZones" :key="z.index"
            class="flex-1 flex items-center justify-center text-white font-medium"
            :style="{ backgroundColor: z.color }"
            :title="`${z.name} · ${z.lo}–${z.hi} bpm`">
            Z{{ z.index }}
          </div>
        </div>
        <div class="grid grid-cols-5 text-[10px] text-muted-fg mt-1 text-center tabular-nums">
          <div v-for="z in previewZones" :key="z.index">{{ fmtZoneRange(z.lo, z.hi) }}</div>
        </div>
      </div>
    </div>

    <!-- ── Save ───────────────────────────────────────────────────────────── -->
    <div class="flex justify-end">
      <button class="inline-flex items-center gap-1.5 px-3 py-1.5 bg-primary text-primary-fg text-xs font-medium rounded hover:bg-primary/90 disabled:opacity-50"
        :disabled="!dirty || saveMut.isPending.value" @click="saveMut.mutate()">
        <Save :size="12" /> {{ saveMut.isPending.value ? 'Saving…' : 'Save profile' }}
      </button>
    </div>
  </div>
  <div v-else class="text-sm text-muted-fg opacity-70">Loading…</div>
</template>
