import { type Ref } from 'vue'

interface UseListNavigationOptions {
  /** Total number of items in the list. */
  listLength: Ref<number>
  /** Currently active index (-1 = nothing highlighted). */
  activeIndex: Ref<number>
  /** Called when Enter is pressed on a highlighted item. */
  onSelect?: (index: number) => void
  /** Loop navigation (Down at the bottom wraps to the top, and vice-versa). */
  loop?: boolean
}

/**
 * Standardises arrow-key navigation for lists (search suggestions, trees, …) —
 * ported from matrosdms' useListNavigation so our widgets behave identically.
 * Handles ArrowDown/Up/Home/End/Enter and calls `e.preventDefault()` only when
 * it actually consumes the key, so callers can fall through for unhandled keys.
 */
export function useListNavigation(options: UseListNavigationOptions) {
  const { listLength, activeIndex, onSelect, loop = false } = options

  const handleKey = (e: KeyboardEvent) => {
    if (listLength.value === 0) return

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault()
        if (activeIndex.value < listLength.value - 1) activeIndex.value++
        else if (loop) activeIndex.value = 0
        break
      case 'ArrowUp':
        e.preventDefault()
        if (activeIndex.value > 0) activeIndex.value--
        else if (activeIndex.value <= 0 && loop) activeIndex.value = listLength.value - 1
        break
      case 'Home':
        e.preventDefault()
        activeIndex.value = 0
        break
      case 'End':
        e.preventDefault()
        activeIndex.value = listLength.value - 1
        break
      case 'Enter':
        if (activeIndex.value >= 0 && onSelect) {
          e.preventDefault()
          onSelect(activeIndex.value)
        }
        break
    }
  }

  return { handleKey }
}
