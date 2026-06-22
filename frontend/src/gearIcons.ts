// Gear glyphs come from the full Lucide icon set (same set tags use, via
// IconPicker). `gear.icon` stores the Lucide icon NAME (PascalCase, e.g.
// "Bike", "Mountain", "Footprints").
//
// Most places render the icon as a Vue component (IconPicker / DynamicIcon), but
// the map replay cursor is a plain DOM marker that needs an inline SVG *string*.
// lucide-vue-next doesn't expose raw path data, so we render the component into a
// detached element once and read its SVG markup.

import { h, render } from 'vue'
import * as LucideIcons from 'lucide-vue-next'

// Back-compat: older gear records stored a tiny curated key, not a Lucide name.
const LEGACY: Record<string, string> = {
  bike: 'Bike', race: 'Bike', gravel: 'Bike', mtb: 'Mountain',
  run: 'Footprints', swim: 'Waves',
}

/** Resolve a stored gear.icon value to a Lucide component name (or null). */
export function gearIconName(value: string | null | undefined): string | null {
  if (!value) return null
  return LEGACY[value] ?? value
}

/** Inline `<svg>` string for a gear glyph — for DOM markers (map) and v-html. */
export function gearIconSvg(value: string | null | undefined, size = 14, stroke = 'currentColor', width = 2): string {
  const name = gearIconName(value)
  if (!name) return ''
  const Comp = (LucideIcons as Record<string, unknown>)[name]
  if (!Comp) return ''
  const tmp = document.createElement('div')
  try {
    render(h(Comp as never, { size, color: stroke, 'stroke-width': width }), tmp)
    return tmp.querySelector('svg')?.outerHTML ?? ''
  } finally {
    render(null, tmp) // unmount, free the vnode
  }
}
