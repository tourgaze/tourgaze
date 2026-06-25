<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useStorage } from '@vueuse/core'
import { X, ArrowRight, Bike, MountainSnow } from 'lucide-vue-next'

// First-run onboarding, matros-style: a small overlay shown once per browser
// that points the user at the first thing worth setting up — their gear.
// Bumped the version suffix to re-show after a meaningful copy/flow change.
const seen = useStorage('tourgaze.welcome.seen.v1', false)
const router = useRouter()

const isOpen = ref(false)
const step = ref(1)

const steps = [
  {
    icon: MountainSnow,
    title: 'Welcome to TourGaze',
    text: 'Drop your FIT files in the inbox and TourGaze turns them into rich, replayable tours. Let’s get you set up.',
  },
  {
    icon: Bike,
    title: 'Add your gear first',
    text: 'Create a bike, pair of shoes or wetsuit so you can attach it to every tour. Your gear lives in the library and is part of your backup — so it’s worth setting up now.',
  },
]

onMounted(() => {
  if (!seen.value) setTimeout(() => { isOpen.value = true }, 1200)
})

function dismiss() {
  isOpen.value = false
  seen.value = true
}
function next() { step.value++ }
function goToGear() {
  dismiss()
  router.push('/settings?cat=gear')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="isOpen"
      class="fixed inset-0 z-[300] flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div class="relative w-full max-w-sm rounded-xl border border-border bg-background shadow-2xl overflow-hidden">
        <button class="absolute top-3 right-3 p-1 rounded-full text-muted-fg hover:text-foreground hover:bg-muted/40 transition-colors"
          title="Dismiss" @click="dismiss">
          <X :size="18" />
        </button>

        <div class="p-8 text-center">
          <div class="flex justify-center mb-5 text-primary">
            <component :is="steps[step - 1].icon" :size="44" :stroke-width="1.5" />
          </div>
          <h2 class="text-lg font-semibold text-foreground mb-2">{{ steps[step - 1].title }}</h2>
          <p class="text-sm text-muted-fg leading-relaxed">{{ steps[step - 1].text }}</p>
        </div>

        <div class="flex items-center justify-between gap-3 p-4 border-t border-border bg-muted/10">
          <div class="flex gap-1.5">
            <span v-for="i in steps.length" :key="i"
              class="w-2 h-2 rounded-full transition-colors"
              :class="step === i ? 'bg-primary' : 'bg-muted-fg/30'" />
          </div>

          <button v-if="step < steps.length" @click="next"
            class="inline-flex items-center gap-1.5 px-4 py-2 text-sm font-medium rounded-lg bg-primary text-primary-fg hover:bg-primary/90 transition-colors">
            Next <ArrowRight :size="15" />
          </button>
          <button v-else @click="goToGear"
            class="inline-flex items-center gap-1.5 px-4 py-2 text-sm font-medium rounded-lg bg-primary text-primary-fg hover:bg-primary/90 transition-colors">
            <Bike :size="15" /> Add gear
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
