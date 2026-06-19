// Shared display formatters — one source instead of the same helpers copy-pasted
// into every view. (Genuinely one-off / divergent formats stay local to their
// component: sub-km distances, byte sizes, coordinates, HR-zone ranges, etc.)

/** Ride duration as hours+minutes: "1h 23m" / "23m" / "0m". Null → "—". */
export function fmtDuration(s: number | null | undefined): string {
  if (s == null) return '—'
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60)
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}

/** Clock style: "h:mm:ss" (with hours) or "m:ss". Null → "—". */
export function fmtClock(sec: number | null | undefined): string {
  if (sec == null) return '—'
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = Math.floor(sec % 60)
  const mm = String(m).padStart(2, '0'), ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`
}

/** Date + time, locale-aware: "05 Jun 2024, 14:30". Null → "—". */
export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}
