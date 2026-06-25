import { ref, watch, type Ref } from 'vue'

/**
 * Tiny matrosdms-style layout persistence.
 *
 * Each view owns its own namespaced "layout slot" in localStorage and decides
 * which pieces of state to put in. The "Save layout" button calls
 * {@link LayoutSlot.save} with the current snapshot; on mount, callers read
 * {@link LayoutSlot.load} and seed their refs.
 *
 * Persistence is opt-in (user clicks Save) — we never auto-write on every
 * mutation. That mirrors how matrosdms "Save perspective" works: the user
 * adjusts panes/filters freely, then commits when they like the layout.
 */
export class LayoutSlot<T extends Record<string, unknown>> {
  constructor(private readonly key: string) {}

  load(): Partial<T> | null {
    try {
      const raw = localStorage.getItem(this.key)
      return raw ? (JSON.parse(raw) as Partial<T>) : null
    } catch {
      return null
    }
  }

  save(snapshot: T): void {
    try {
      localStorage.setItem(this.key, JSON.stringify(snapshot))
    } catch {
      // quota / disabled storage — silently drop
    }
  }

  clear(): void {
    try { localStorage.removeItem(this.key) } catch {}
  }
}

/**
 * Convenience helper for the very common "ref backed by localStorage slot".
 * Reads once on creation; subsequent changes don't auto-write — call save()
 * explicitly from the consumer.
 */
export function layoutRef<T>(slot: LayoutSlot<Record<string, unknown>>, field: string, initial: T): Ref<T> {
  const loaded = slot.load()
  const start = loaded && field in loaded ? (loaded[field] as T) : initial
  return ref(start) as Ref<T>
}

// Pre-built slot names so view files agree on keys.
export const TOURS_LAYOUT_SLOT = new LayoutSlot<{
  leftCollapsed: boolean
  leftSize: number
  groupBy: string
  groupTagId: string
  tagDockOpen: boolean
  gearDockOpen: boolean
  // Serialized faceted-search string (e.g. `sport:cycling year:2020 alpine`).
  // The discrete sport/year/tag/text fields were replaced by this single query.
  query: string
}>('tourgaze.layout.tours')

/** Snapshot writer signature is exposed so a view can wire a single button. */
export type SaveLayoutFn = () => void

/**
 * Global "current view layout saver" registry.
 *
 * Views with persistable explicit state (Tours, Settings) register a closure
 * here from `onMounted` and clear it on `onUnmounted`. The header's Save
 * Layout button calls whatever's registered.
 *
 * This intentionally avoids Pinia / a context-provided injection — the data
 * is a single function pointer, scoped to the active route, and Vue's reactive
 * ref does the right thing.
 */
const _currentSaver = ref<{ fn: SaveLayoutFn; label: string } | null>(null)

export function registerLayoutSaver(label: string, fn: SaveLayoutFn): () => void {
  _currentSaver.value = { fn, label }
  return () => {
    if (_currentSaver.value?.fn === fn) _currentSaver.value = null
  }
}

export function useCurrentLayoutSaver() {
  return _currentSaver
}

export const SETTINGS_LAYOUT_SLOT = new LayoutSlot<{
  sidebarSize: number
  sidebarCollapsed: boolean
  activeCategory: string
}>('tourgaze.layout.settings')

export const VIEWER_LAYOUT_SLOT = new LayoutSlot<{
  chartCollapsed: boolean
  chartSize: number
}>('tourgaze.layout.viewer')

export const INBOX_LAYOUT_SLOT = new LayoutSlot<{
  // Whether the import panel's "Start location" route card is expanded — a
  // single shared preference so it stays open as you move between rides.
  mapExpanded: boolean
}>('tourgaze.layout.inbox')

/**
 * Variant of {@link layoutRef} that **auto-persists** every write back to the
 * slot. Use for tiny ergonomic state (a single pane fold flag, a remembered
 * size) where there is no other natural moment to call save() — it'd be
 * obnoxious to require a Save button for "I dragged the divider".
 *
 * Don't use this for filter/group selections — those should still be
 * gated behind an explicit Save so dragging filters around doesn't write a
 * dozen times.
 */
export function autoLayoutRef<T>(slot: LayoutSlot<Record<string, unknown>>, field: string, initial: T): Ref<T> {
  const r = layoutRef(slot, field, initial)
  watch(r, (v) => {
    const current = slot.load() ?? {}
    slot.save({ ...current, [field]: v as unknown })
  })
  return r
}

/**
 * Wipe every persisted layout slot — used by the Restore-layout button in
 * AppHeader. Matrosdms-style: one click + a native confirm pops, then ALL
 * views reset to defaults at once (per-view restore would be busywork —
 * users either trust the current layout or want a clean slate). Components
 * that hold refs from `layoutRef` / `autoLayoutRef` keep their in-memory
 * values until reload; the next browser reload picks up the fresh defaults.
 */
export function resetAllLayouts(): void {
  // Iterate prefix so any future slot added with the `tourgaze.layout.` namespace
  // is swept too without having to update this list.
  const prefix = 'tourgaze.layout.'
  const toRemove: string[] = []
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i)
    if (k && k.startsWith(prefix)) toRemove.push(k)
  }
  for (const k of toRemove) localStorage.removeItem(k)
}
