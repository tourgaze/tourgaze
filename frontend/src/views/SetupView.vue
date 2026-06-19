<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { UserPlus, AlertCircle } from 'lucide-vue-next'
import { createUser, getUsers } from '@/api/client'

const router = useRouter()
const submitting = ref(false)
const errorMsg = ref<string | null>(null)

const username = ref('')
const displayName = ref('')
const dateOfBirth = ref<string>('')      // yyyy-mm-dd
const heightCm = ref<number | null>(null)
const weightKg = ref<number | null>(null)
const gender = ref<string>('')

onMounted(async () => {
  // If a user already exists, the setup is done — skip this screen.
  try {
    const users = await getUsers()
    if (users.length > 0) router.replace('/')
  } catch {
    // ignore — surface real errors on submit
  }
})

async function submit() {
  if (submitting.value) return
  errorMsg.value = null
  submitting.value = true
  try {
    await createUser({
      username: username.value.trim(),
      displayName: displayName.value.trim() || null,
      dateOfBirth: dateOfBirth.value || null,
      heightCm: heightCm.value,
      weightKg: weightKg.value,
      gender: gender.value || null,
    } as any)
    router.replace('/')
  } catch (e: any) {
    errorMsg.value = e?.message ?? 'Could not create profile'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-[#f8f9fa] dark:bg-black p-4">
    <form
      class="w-full max-w-md bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 rounded-xl shadow-sm p-6 space-y-4"
      @submit.prevent="submit"
    >
      <div class="text-center space-y-1">
        <div class="mx-auto w-12 h-12 rounded-full bg-emerald-50 dark:bg-emerald-950 flex items-center justify-center">
          <UserPlus :size="22" class="text-emerald-500" />
        </div>
        <h1 class="text-lg font-semibold">Set up your profile</h1>
        <p class="text-xs text-muted-fg">Used for HR zones and per-activity stats. Everything is optional except a name.</p>
      </div>

      <div v-if="errorMsg" class="flex items-start gap-2 text-xs text-red-600 bg-red-50 dark:bg-red-900/20 border border-red-200 rounded-md px-3 py-2">
        <AlertCircle :size="14" class="mt-0.5 flex-shrink-0" />
        <span>{{ errorMsg }}</span>
      </div>

      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg">Username *</span>
        <input v-model="username" type="text" required autofocus
          class="mt-1 block w-full rounded-md border border-gray-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none" />
      </label>

      <label class="block text-sm">
        <span class="text-xs font-medium text-muted-fg">Display name</span>
        <input v-model="displayName" type="text"
          class="mt-1 block w-full rounded-md border border-gray-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none" />
      </label>

      <div class="grid grid-cols-2 gap-3">
        <label class="block text-sm">
          <span class="text-xs font-medium text-muted-fg">Date of birth</span>
          <input v-model="dateOfBirth" type="date"
            class="mt-1 block w-full rounded-md border border-gray-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none" />
        </label>
        <label class="block text-sm">
          <span class="text-xs font-medium text-muted-fg">Gender</span>
          <select v-model="gender"
            class="mt-1 block w-full rounded-md border border-gray-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none">
            <option value="">—</option>
            <option value="male">Male</option>
            <option value="female">Female</option>
            <option value="other">Other</option>
          </select>
        </label>
      </div>

      <div class="grid grid-cols-2 gap-3">
        <label class="block text-sm">
          <span class="text-xs font-medium text-muted-fg">Height (cm)</span>
          <input v-model.number="heightCm" type="number" min="50" max="250"
            class="mt-1 block w-full rounded-md border border-gray-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none" />
        </label>
        <label class="block text-sm">
          <span class="text-xs font-medium text-muted-fg">Weight (kg)</span>
          <input v-model.number="weightKg" type="number" step="0.1" min="20" max="300"
            class="mt-1 block w-full rounded-md border border-gray-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none" />
        </label>
      </div>

      <p class="text-[11px] text-muted-fg text-center">
        Max HR is auto-computed from your age + gender (Tanaka / Gulati). You can override it any time in <strong>Settings → Profile</strong>.
      </p>

      <button type="submit" :disabled="submitting || !username"
        class="w-full inline-flex items-center justify-center gap-2 rounded-md bg-emerald-600 hover:bg-emerald-700 disabled:opacity-60 text-white text-sm font-medium px-3 py-2 transition-colors">
        <UserPlus :size="14" />
        {{ submitting ? 'Saving…' : 'Create profile' }}
      </button>
    </form>
  </div>
</template>
