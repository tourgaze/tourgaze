import { ref, type Ref } from 'vue'

// Reactive mirror of the `dark` class AppShell toggles on <html>. Components
// that build styles in plain JS (ECharts options, MapLibre paint values) can't
// use Tailwind's dark: variants — they read this instead, and re-run when the
// user flips the theme (a plain classList.contains() check is non-reactive and
// leaves them stuck in the old theme until their next unrelated rebuild).
const isDark = ref(document.documentElement.classList.contains('dark'))
new MutationObserver(() => {
  isDark.value = document.documentElement.classList.contains('dark')
}).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })

export function useDarkMode(): Ref<boolean> {
  return isDark
}
