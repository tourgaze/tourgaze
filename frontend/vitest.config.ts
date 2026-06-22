import { defineConfig } from 'vitest/config'
import { fileURLToPath, URL } from 'node:url'

// Lightweight, plugin-free test config (no vue/tailwind needed for the headless
// replay sim + pure composables). Shares the '@' alias with vite.config.ts.
export default defineConfig({
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts'],
  },
})
