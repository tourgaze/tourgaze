// Marker categories — drive the map pin glyph/colour and the editor picker.
// Glyphs are genuine Lucide icons (the project's free, ISC-licensed icon set);
// `svg` holds each icon's inner markup so it can be injected into a DOM marker
// element built outside Vue (MapLibre marker), where a Vue component can't go.

export type MarkerCategory = { key: string; label: string; color: string; svg: string }

export const MARKER_CATEGORIES: MarkerCategory[] = [
  { key: 'star',      label: 'Star',      color: '#eab308', svg: '<path d="M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z"/>' },
  { key: 'food',      label: 'Food',      color: '#f97316', svg: '<path d="M3 2v7c0 1.1.9 2 2 2h4a2 2 0 0 0 2-2V2"/><path d="M7 2v20"/><path d="M21 15V2a5 5 0 0 0-5 5v6c0 1.1.9 2 2 2h3Zm0 0v7"/>' },
  { key: 'viewpoint', label: 'Viewpoint', color: '#0ea5e9', svg: '<path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"/><circle cx="12" cy="12" r="3"/>' },
  { key: 'peak',      label: 'Peak',      color: '#7c3aed', svg: '<path d="m8 3 4 8 5-5 5 15H2L8 3z"/>' },
  { key: 'water',     label: 'Water',     color: '#06b6d4', svg: '<path d="M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z"/>' },
  { key: 'repair',    label: 'Repair',    color: '#64748b', svg: '<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z"/>' },
  { key: 'warning',   label: 'Warning',   color: '#dc2626', svg: '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>' },
  { key: 'home',      label: 'Home',      color: '#16a34a', svg: '<path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8"/><path d="M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>' },
]

export function markerCategory(key: string | null | undefined): MarkerCategory {
  return MARKER_CATEGORIES.find(c => c.key === key) ?? MARKER_CATEGORIES[0]
}

/** Full inline `<svg>` for a category glyph — used by map pins (DOM markers) and
 *  the editor/list icons. One place so the icon markup isn't copy-pasted. */
export function markerIconSvg(category: string | MarkerCategory, size = 14): string {
  const svg = typeof category === 'string' ? markerCategory(category).svg : category.svg
  return `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${svg}</svg>`
}
