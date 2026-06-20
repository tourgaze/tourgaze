import { ref } from 'vue'

/**
 * Reactive backend reachability, polled in the background (shared singleton).
 * Drives the global "can't reach the server" overlay so the app degrades
 * gracefully when the backend is down/restarting instead of showing blank panes
 * and a flood of failed-query toasts (matros-style offline handling).
 *
 * Pings `/api/version` — cheap (build info, no DB) and routed through the Vite
 * `/api` dev proxy, so it works in dev and in the packaged jar alike.
 */
const online = ref(true)
let polling = false

async function ping(): Promise<boolean> {
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 4000)
    const r = await fetch('/api/version', { signal: ctrl.signal, cache: 'no-store' })
    clearTimeout(timer)
    return r.ok
  } catch {
    return false
  }
}

export function useBackendHealth(onReconnect?: () => void) {
  if (!polling) {
    polling = true
    const loop = async () => {
      const ok = await ping()
      const wasOnline = online.value
      online.value = ok
      if (ok && !wasOnline) onReconnect?.() // came back → refresh data
      // Poll slower while healthy, faster while down so recovery feels instant.
      setTimeout(loop, ok ? 8000 : 3000)
    }
    loop()
  }
  return { online }
}
