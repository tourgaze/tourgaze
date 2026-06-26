<script setup lang="ts">
/**
 * Climbing-goat nav icon — the marketing-site goat (tourgaze.github.io/site/
 * goat.svg) redrawn as a stroke-only line icon (no fill) so it matches the
 * outline lucide nav set, tinting with `currentColor` (muted idle / primary
 * active) inside the hover-background button.
 */
withDefaults(defineProps<{ size?: number | string }>(), { size: 24 })
</script>

<template>
  <svg
    :width="size" :height="size" viewBox="0 0 64 64"
    fill="none" stroke="currentColor"
    stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"
    aria-hidden="true"
  >
    <!-- the slope it climbs -->
    <path d="M4 60 L60 36" opacity="0.4" />

    <!-- legs + tail -->
    <path d="M26 40 L24 51" />
    <path d="M31 40 L31 47" />
    <path d="M42 35 L43 44" />
    <path d="M46 34 L48 41" />
    <path d="M22 38 L18 35" />

    <!-- body + neck + head, side profile facing up-right (outline) -->
    <path d="
      M22 41
      C 20 35 25 32 30 31
      L 43 28
      L 46 23
      L 51 21
      L 58 22
      C 59 23 58 25 56 25
      L 51 27
      L 47 31
      L 45 33
      L 30 39
      C 26 41 23 42 22 41 Z" />

    <!-- ibex horns sweeping back over the neck -->
    <path d="M50 22 C 45 13 37 12 32 17" />
    <path d="M52 23 C 48 15 41 14 36 18" />
  </svg>
</template>
