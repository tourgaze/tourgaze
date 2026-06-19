<script setup lang="ts">
/**
 * TourGaze brand mark — a streamlined ibex head over a mountain ridge
 * (gaze ↔ goat ↔ mountains). Kept deliberately minimal so it reads cleanly at
 * favicon size: bold back-swept horns, one clean head silhouette, two eyes, a
 * single ridge — no ears/beard clutter. Stroke-style like Lucide so it tints
 * with `currentColor` and sits beside the other icons.
 *
 * NOTE: keep the paths in sync with `public/goat.svg` (the filled favicon /
 * boot-splash badge) — same geometry, different presentation.
 */
withDefaults(defineProps<{ size?: number | string }>(), { size: 24 })
</script>

<template>
  <svg
    :width="size" :height="size" viewBox="0 0 24 24"
    fill="none" stroke="currentColor" stroke-width="1.9"
    stroke-linecap="round" stroke-linejoin="round"
    aria-hidden="true"
  >
    <!-- Back-swept ibex horns -->
    <path d="M9.2 8.4 C 7 4.5 4.6 3.5 3 5.1" />
    <path d="M14.8 8.4 C 17 4.5 19.4 3.5 21 5.1" />
    <!-- Head silhouette (brow → cheeks → chin), sitting just above the ridge -->
    <path d="M9.2 7.8 Q 12 6.0 14.8 7.8 C 15 10.8 14 13.2 12 14.8 C 10 13.2 9 10.8 9.2 7.8 Z" />
    <!-- Eyes -->
    <circle cx="10.7" cy="9.6" r="0.6" fill="currentColor" stroke="none" />
    <circle cx="13.3" cy="9.6" r="0.6" fill="currentColor" stroke="none" />
    <!-- Mountain ridge -->
    <path d="M3.5 21 L 8.5 17.4 L 12 20.2 L 15.5 17.4 L 20.5 21" />
  </svg>
</template>
