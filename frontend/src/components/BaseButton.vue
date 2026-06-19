<script setup lang="ts">
/**
 * The shared button. One place for hover / active / focus-visible / disabled
 * feedback so every button behaves consistently (the styles live in style.css:
 * `.btn` + `.btn-{variant}`). Prefer this over hand-rolled `<button class="…">`.
 *
 *   <BaseButton variant="primary" @click="save">Save</BaseButton>
 *   <BaseButton variant="danger" :disabled="busy">Delete</BaseButton>
 */
withDefaults(defineProps<{
  variant?: 'primary' | 'ghost' | 'outline' | 'danger'
  type?: 'button' | 'submit'
  disabled?: boolean
}>(), { variant: 'ghost', type: 'button', disabled: false })
</script>

<template>
  <button :type="type" :disabled="disabled" class="btn" :class="`btn-${variant}`">
    <slot />
  </button>
</template>
