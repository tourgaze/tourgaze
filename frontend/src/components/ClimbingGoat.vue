<script setup lang="ts">
/**
 * Tours / ride-list nav icon — the climbing-goat brand mark from the marketing
 * site (tourgaze.github.io/site/goat.svg). A filled side profile with the
 * blue→purple TourGaze gradient, so it reads as the brand rather than a generic
 * glyph. (Unlike the stroke GoatLogo, this keeps its own gradient colours and
 * does not tint with `currentColor`.)
 */
withDefaults(defineProps<{ size?: number | string }>(), { size: 24 })
</script>

<template>
  <svg
    :width="size" :height="size" viewBox="0 0 64 64"
    aria-hidden="true"
  >
    <defs>
      <linearGradient id="tg-goat-nav" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#4f8cff" />
        <stop offset="1" stop-color="#7c5cff" />
      </linearGradient>
    </defs>

    <!-- the slope it climbs -->
    <path d="M4 60 L60 36" fill="none" stroke="url(#tg-goat-nav)" stroke-width="3"
      stroke-linecap="round" opacity="0.4" />

    <!-- legs (front legs reach up the slope, hind legs push from below) -->
    <g fill="none" stroke="url(#tg-goat-nav)" stroke-width="2.6" stroke-linecap="round">
      <path d="M26 40 L24 51" />
      <path d="M31 40 L31 47" />
      <path d="M42 35 L43 44" />
      <path d="M46 34 L48 41" />
      <!-- tail -->
      <path d="M22 38 L18 35" />
    </g>

    <!-- body + neck + head, side profile facing up-right -->
    <path fill="url(#tg-goat-nav)" d="
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

    <!-- beard -->
    <path fill="url(#tg-goat-nav)" d="M50 27 L49 33 L53 28 Z" />

    <!-- big ibex horns sweeping back over the neck -->
    <g fill="none" stroke="url(#tg-goat-nav)" stroke-width="2.6" stroke-linecap="round">
      <path d="M50 22 C 45 13 37 12 32 17" />
      <path d="M52 23 C 48 15 41 14 36 18" />
    </g>
  </svg>
</template>
