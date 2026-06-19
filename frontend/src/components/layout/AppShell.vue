<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppActivityBar from '@/components/layout/AppActivityBar.vue'

const darkMode = ref(false)

onMounted(() => {
  const saved = localStorage.getItem('tourgaze.dark')
  const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
  darkMode.value = saved != null ? saved === '1' : prefersDark
  document.documentElement.classList.toggle('dark', darkMode.value)
})

function toggleDark() {
  darkMode.value = !darkMode.value
  document.documentElement.classList.toggle('dark', darkMode.value)
  localStorage.setItem('tourgaze.dark', darkMode.value ? '1' : '0')
}
</script>

<template>
  <div
    class="flex flex-col h-screen w-full overflow-hidden"
    :class="darkMode ? 'dark bg-black text-white' : 'bg-[#f8f9fa] text-gray-900'"
  >
    <AppHeader :dark="darkMode" @toggle-dark="toggleDark" />
    <div class="flex flex-1 overflow-hidden">
      <AppActivityBar />
      <div class="flex-1 overflow-hidden">
        <RouterView />
      </div>
    </div>
  </div>
</template>
