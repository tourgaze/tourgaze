// Curated gear glyphs. A small finite set (gear is bikes / shoes / …) so the
// same inline SVG can be reused in the picker, the gear list AND the replay
// cursor on the map (a DOM marker that needs an SVG string, not a Vue
// component). The stored value on `gear.icon` is the `key`.

export type GearIcon = { key: string; label: string; svg: string }

export const GEAR_ICONS: GearIcon[] = [
  { key: 'bike', label: 'Road bike', svg: '<circle cx="18.5" cy="17.5" r="3.5"/><circle cx="5.5" cy="17.5" r="3.5"/><circle cx="15" cy="5" r="1"/><path d="M12 17.5V14l-3-3 4-3 2 3h2"/>' },
  { key: 'mtb', label: 'MTB / trail', svg: '<path d="m8 3 4 8 5-5 5 15H2L8 3z"/>' },
  { key: 'run', label: 'Run', svg: '<circle cx="12" cy="5" r="1"/><path d="m9 20 3-6 3 6"/><path d="m6 8 6 2 6-2"/><path d="M12 10v4"/>' },
  { key: 'swim', label: 'Swim', svg: '<path d="M2 6c.6.5 1.2 1 2.5 1C7 7 7 5 9.5 5s2.5 2 5 2 2.5-2 5-2c1.3 0 1.9.5 2.5 1"/><path d="M2 12c.6.5 1.2 1 2.5 1 2.5 0 2.5-2 5-2s2.5 2 5 2 2.5-2 5-2c1.3 0 1.9.5 2.5 1"/><path d="M2 18c.6.5 1.2 1 2.5 1 2.5 0 2.5-2 5-2s2.5 2 5 2 2.5-2 5-2c1.3 0 1.9.5 2.5 1"/>' },
]

export function gearIcon(key: string | null | undefined): GearIcon | null {
  return key ? (GEAR_ICONS.find(g => g.key === key) ?? null) : null
}

/** Full inline `<svg>` for a gear glyph — for DOM markers (map) and v-html. */
export function gearIconSvg(key: string | null | undefined, size = 14, stroke = 'currentColor', width = 2): string {
  const g = gearIcon(key)
  if (!g) return ''
  return `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="${stroke}" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round">${g.svg}</svg>`
}
