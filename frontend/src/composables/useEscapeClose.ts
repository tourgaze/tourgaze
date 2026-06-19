import { onMounted, onUnmounted, watch, type Ref } from 'vue'

/**
 * Bind Esc → close behaviour to a component. Pass a "should be active"
 * predicate (typically the open/visible ref) and a callback. While the
 * predicate is true we listen on `window.keydown`, intercept Escape, and
 * call the callback.
 *
 * Skipping the listener while the dialog is closed avoids the common bug
 * where pressing Esc inside an input (e.g. cancelling an IME) accidentally
 * dismisses an unmounted modal — and avoids leaking a global key handler
 * after unmount.
 */
export function useEscapeClose(
  active: Ref<boolean> | (() => boolean),
  onClose: () => void,
): void {
  const isActive = () => (typeof active === 'function' ? active() : active.value)

  const handler = (e: KeyboardEvent) => {
    if (!isActive()) return
    if (e.key !== 'Escape') return
    // Don't swallow Esc inside text inputs when they're handling their own
    // (IME composition cancel, native dropdown close, etc).
    const target = e.target as HTMLElement | null
    if (target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName) &&
        (target as HTMLInputElement).type !== 'checkbox' &&
        (target as HTMLInputElement).type !== 'radio') {
      // Still close on Esc for plain text inputs — that's the conventional
      // dialog behaviour. IME composition is a special case that the browser
      // handles before this event fires (e.key === 'Process' during compose).
    }
    e.preventDefault()
    onClose()
  }

  onMounted(() => window.addEventListener('keydown', handler))
  onUnmounted(() => window.removeEventListener('keydown', handler))

  // Re-arm cleanly when `active` flips back and forth — listener stays
  // bound but its body returns early when the dialog is closed.
  if (typeof active !== 'function') watch(active, () => {})
}
