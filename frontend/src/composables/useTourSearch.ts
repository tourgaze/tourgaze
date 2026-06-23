import { computed, ref, type Ref } from 'vue'
import type { ActivitySummary, Tag } from '@/api/client'

/**
 * JIRA / Lucene-style faceted search for the Tours list — ported in spirit from
 * matrosdms' GlobalSearch (`useSearch.ts`). The difference: TourGaze filters
 * the already-loaded activity list client-side, so there is no backend search.
 * This composable parses the query into typed filter chips + free text and
 * exposes a `matches()` predicate the view runs over every activity.
 *
 * Mini-language:
 *   title:mallo            ride-title substring (case-insensitive, name only)
 *   sport:cycling          field equality (case-insensitive)
 *   year:2020              ride start year
 *   tag:"alpine climb"     tag by name (quoted if it contains spaces)
 *   loc:brixen             start/end location substring
 *   country:it             ISO-3166-1 alpha-2 of start/end
 *   dist:>50  elev:<=1200  numeric fields with > < >= <= = (default =)
 *   sunday alpine          anything else → free-text over name + description
 *
 * Committed filters live in {@link activeFilters} (rendered as chips); the
 * trailing residual stays in {@link query} as free text. {@link serialize}
 * round-trips the whole thing to the string a saved preset stores.
 */

export type Op = '=' | '>' | '<' | '>=' | '<=' | 'between'

export interface TourFilter {
  /** Canonical field key (e.g. 'sport', 'year', 'tag', 'num:dist'). */
  field: string
  op: Op
  value: string
  /** Upper bound for op === 'between' (e.g. `year:2014..2018`). */
  value2?: string
  /** Human chip text, e.g. `sport:cycling` or `year:2014+`. */
  label: string
}

type FieldKind = 'enum' | 'year' | 'tag' | 'gear' | 'loc' | 'country' | 'num' | 'text' | 'geo' | 'event'

interface FieldDef {
  key: string
  aliases: string[]
  kind: FieldKind
  /** numeric accessor for kind === 'num' */
  num?: (a: ActivitySummary) => number | null | undefined
}

const FIELDS: FieldDef[] = [
  { key: 'title', aliases: ['title', 'name'], kind: 'text' },
  { key: 'sport', aliases: ['sport', 'activity'], kind: 'enum' },
  { key: 'year', aliases: ['year'], kind: 'year' },
  { key: 'tag', aliases: ['tag'], kind: 'tag' },
  { key: 'gear', aliases: ['gear', 'bike'], kind: 'gear' },
  // `event:puncture` — keep rides that have a ride event whose type or label
  // matches. Substring + case-insensitive, so `event:rain` hits WEATHER_RAIN
  // ("Rainfall"). Events ride along on the summary, so this stays client-side.
  { key: 'event', aliases: ['event'], kind: 'event' },
  { key: 'loc', aliases: ['loc', 'location', 'place'], kind: 'loc' },
  // `near:mallorca` — geographic filter: geocode the place, keep rides whose
  // start falls inside its real extent (Nominatim bounding box). Unlike `loc:`
  // (text on the place name) this catches rides whether their start_location
  // reads "Palma", "Andratx", … as long as they're on the island.
  { key: 'near', aliases: ['near', 'around', 'in'], kind: 'geo' },
  { key: 'country', aliases: ['country'], kind: 'country' },
  { key: 'dist', aliases: ['dist', 'distance', 'km'], kind: 'num', num: a => a.distanceKm },
  { key: 'elev', aliases: ['elev', 'elevation', 'climb', 'ascent'], kind: 'num', num: a => a.elevationGainM },
  { key: 'hr', aliases: ['hr', 'heartrate'], kind: 'num', num: a => a.avgHr },
  { key: 'speed', aliases: ['speed', 'kmh'], kind: 'num', num: a => a.avgSpeedKmh },
]

const ALIAS_TO_FIELD = new Map<string, FieldDef>()
for (const f of FIELDS) for (const a of f.aliases) ALIAS_TO_FIELD.set(a, f)

/** All keys the autocomplete can complete to, longest list first for nicer UX. */
const ALL_KEYS = FIELDS.flatMap(f => f.aliases)

/**
 * Parse a scalar (year / numeric) filter value into an op + bound(s). Supports
 * three surface syntaxes so users can pick whichever reads best:
 *   2014        exact            2014..2018   inclusive between
 *   2014+       >= (and later)   >2014 / >=2014  classic prefix ops
 *   2014-       <= (and earlier) <2014 / <=2014
 */
function parseRange(raw: string): { op: Op; value: string; value2?: string } {
  const s = raw.trim()
  const between = s.match(/^(-?\d+(?:\.\d+)?)\.\.(-?\d+(?:\.\d+)?)$/)
  if (between) return { op: 'between', value: between[1], value2: between[2] }
  if (/^\d[\d.]*\+$/.test(s)) return { op: '>=', value: s.slice(0, -1) }
  if (/^\d[\d.]*-$/.test(s)) return { op: '<=', value: s.slice(0, -1) }
  if (s.startsWith('>=')) return { op: '>=', value: s.slice(2) }
  if (s.startsWith('<=')) return { op: '<=', value: s.slice(2) }
  if (s.startsWith('>')) return { op: '>', value: s.slice(1) }
  if (s.startsWith('<')) return { op: '<', value: s.slice(1) }
  if (s.startsWith('=')) return { op: '=', value: s.slice(1) }
  return { op: '=', value: s }
}

/** Quote a value for serialization only when it contains whitespace. */
function quoteIfNeeded(v: string): string {
  return /\s/.test(v) ? `"${v}"` : v
}

function chipLabel(aliasOrKey: string, op: Op, value: string, value2?: string): string {
  switch (op) {
    case 'between': return `${aliasOrKey}:${value}..${value2}`
    case '>=': return `${aliasOrKey}:${value}+`
    case '<=': return `${aliasOrKey}:${value}-`
    case '>': return `${aliasOrKey}:>${value}`
    case '<': return `${aliasOrKey}:<${value}`
    default: return `${aliasOrKey}:${quoteIfNeeded(value)}`
  }
}

/**
 * Tokenize a raw query string into committed filters + leftover free-text
 * words. Used to load a saved preset back into the bar.
 */
function tokenize(input: string): { filters: TourFilter[]; freeText: string } {
  const filters: TourFilter[] = []
  const free: string[] = []
  // key:"quoted value" | key:value | "quoted" | bareword
  const re = /(\w+):"([^"]*)"|(\w+):(\S+)|"([^"]*)"|(\S+)/g
  let m: RegExpExecArray | null
  while ((m = re.exec(input)) !== null) {
    const key = (m[1] ?? m[3])?.toLowerCase()
    const rawVal = m[2] ?? m[4]
    if (key && ALIAS_TO_FIELD.has(key)) {
      const def = ALIAS_TO_FIELD.get(key)!
      const { op, value, value2 } = (def.kind === 'num' || def.kind === 'year')
        ? parseRange(rawVal!)
        : { op: '=' as Op, value: rawVal!, value2: undefined }
      if (value !== '') {
        filters.push({ field: def.key, op, value, value2, label: chipLabel(def.key, op, value, value2) })
        continue
      }
    }
    // Not a recognized facet → free text (strip surrounding quotes from m[5]).
    free.push(m[5] ?? m[6] ?? m[0])
  }
  return { filters, freeText: free.join(' ') }
}

/** Compare a numeric value against a filter's op + bound(s). */
function rangeMatch(actual: number, f: TourFilter): boolean {
  const lo = Number(f.value)
  if (f.op === 'between') {
    const hi = Number(f.value2)
    if (Number.isNaN(lo) || Number.isNaN(hi)) return false
    return actual >= lo && actual <= hi
  }
  if (Number.isNaN(lo)) return false
  switch (f.op) {
    case '>': return actual > lo
    case '<': return actual < lo
    case '>=': return actual >= lo
    case '<=': return actual <= lo
    default: return actual === lo
  }
}

/** A place's geographic extent, resolved by the consumer via forward geocode. */
export type GeoBox = { south: number; north: number; west: number; east: number }

export interface TourSearchSources {
  activities: Ref<ActivitySummary[] | undefined>
  tags: Ref<Tag[]>
  /** Resolved `near:` boxes keyed by lowercased place. undefined = still
   *  geocoding (fail-open, show all), null = no result (can't filter). */
  geoBoxes?: Ref<Map<string, GeoBox | null>>
}

export function useTourSearch(sources: TourSearchSources) {
  // Residual free-text the user is still typing (committed facets move to chips).
  const query = ref('')
  const activeFilters = ref<TourFilter[]>([])

  const tagsByLowerName = computed(() => {
    const m = new Map<string, string[]>() // lower name → tag ids (dupes possible)
    for (const t of sources.tags.value) {
      if (!t.name || !t.id) continue
      const k = t.name.toLowerCase()
      const arr = m.get(k) ?? []
      arr.push(t.id)
      m.set(k, arr)
    }
    return m
  })

  // parent id → child ids, used so a tag filter matches the whole subtree:
  // selecting "Pass" implicitly matches rides tagged jaufenpass, gamsenpass, …
  const childrenByParent = computed(() => {
    const m = new Map<string, string[]>()
    for (const t of sources.tags.value) {
      if (!t.parentId || !t.id) continue
      const arr = m.get(t.parentId) ?? []
      arr.push(t.id)
      m.set(t.parentId, arr)
    }
    return m
  })

  function withDescendants(ids: string[]): Set<string> {
    const out = new Set<string>()
    const stack = [...ids]
    while (stack.length) {
      const id = stack.pop()!
      if (out.has(id)) continue
      out.add(id)
      for (const c of childrenByParent.value.get(id) ?? []) stack.push(c)
    }
    return out
  }

  // ── Evaluation ─────────────────────────────────────────────────────────────
  function filterMatches(f: TourFilter, a: ActivitySummary): boolean {
    const def = FIELDS.find(d => d.key === f.field)
    if (!def) return true
    switch (def.kind) {
      case 'text':
        // Case-insensitive substring on the ride title only (free text also
        // searches the description; this restricts to the name).
        return (a.name ?? '').toLowerCase().includes(f.value.toLowerCase())
      case 'enum':
        return (a.activityType ?? '').toLowerCase() === f.value.toLowerCase()
      case 'year': {
        if (!a.startTime) return false
        return rangeMatch(new Date(a.startTime).getFullYear(), f)
      }
      case 'tag': {
        const ids = tagsByLowerName.value.get(f.value.toLowerCase())
        if (!ids?.length) return false
        // Match the named tag OR any of its descendants (hierarchy is is-a).
        const wanted = withDescendants(ids)
        return (a.tagIds ?? []).some(id => wanted.has(id))
      }
      case 'gear':
        // Substring, case-insensitive — `gear:endurace` matches "Canyon Endurace".
        return (a.gearName ?? '').toLowerCase().includes(f.value.toLowerCase())
      case 'event': {
        // Substring over both the event type and its display label, so either
        // `event:puncture` or `event:rain` works regardless of how it's keyed.
        const v = f.value.toLowerCase()
        return (a.events ?? []).some(e =>
          (e.type ?? '').toLowerCase().includes(v) || (e.label ?? '').toLowerCase().includes(v))
      }
      case 'loc': {
        const hay = `${a.startLocation ?? ''} ${a.endLocation ?? ''}`.toLowerCase()
        return hay.includes(f.value.toLowerCase())
      }
      case 'country': {
        const v = f.value.toLowerCase()
        return (a.startCountry ?? '').toLowerCase() === v || (a.endCountry ?? '').toLowerCase() === v
      }
      case 'geo': {
        const box = sources.geoBoxes?.value.get(f.value.toLowerCase())
        // Still geocoding (undefined) or no result (null) → don't hide rides;
        // the chip stays so the user sees the filter is pending / unresolved.
        if (box == null) return true
        if (a.startLat == null || a.startLon == null) return false
        return a.startLat >= box.south && a.startLat <= box.north
          && a.startLon >= box.west && a.startLon <= box.east
      }
      case 'num': {
        const actual = def.num?.(a)
        if (actual == null) return false
        return rangeMatch(actual, f)
      }
      default:
        return true
    }
  }

  /** Predicate combining all committed filters (AND) plus residual free text. */
  const matches = computed(() => {
    const filters = activeFilters.value
    const terms = query.value.toLowerCase().split(/\s+/).map(t => t.trim()).filter(Boolean)
    // Touch geoBoxes so the predicate re-derives when a `near:` place resolves
    // (the consumer swaps in a new Map identity on each geocode result).
    void sources.geoBoxes?.value
    return (a: ActivitySummary): boolean => {
      for (const f of filters) if (!filterMatches(f, a)) return false
      if (terms.length) {
        const hay = `${a.name ?? ''} ${a.description ?? ''}`.toLowerCase()
        for (const t of terms) if (!hay.includes(t)) return false
      }
      return true
    }
  })

  // ── Autocomplete ─────────────────────────────────────────────────────────────
  // The "trigger" is the trailing `key:partial` the caret sits on.
  const triggerRegex = new RegExp(`(?:^|\\s)(${ALL_KEYS.join('|')}):([^\\s]*)$`, 'i')

  const currentTrigger = computed(() => {
    const m = query.value.match(triggerRegex)
    if (!m) return null
    return { key: m[1].toLowerCase(), partial: m[2], raw: m[0] }
  })

  function distinct(getter: (a: ActivitySummary) => string | null | undefined): string[] {
    const set = new Set<string>()
    for (const a of sources.activities.value ?? []) {
      const v = getter(a)
      if (v) set.add(v)
    }
    return Array.from(set).sort()
  }

  /** Suggestions for the current caret position: field keys, or values for a facet. */
  const suggestions = computed<string[]>(() => {
    const trig = currentTrigger.value
    if (trig) {
      const def = ALIAS_TO_FIELD.get(trig.key)
      if (!def) return []
      const partial = parseRange(trig.partial).value.toLowerCase()
      let values: string[] = []
      switch (def.kind) {
        case 'enum': values = distinct(a => a.activityType); break
        case 'year': values = distinct(a => a.startTime ? new Date(a.startTime).getFullYear().toString() : null).reverse(); break
        case 'tag': values = Array.from(new Set(sources.tags.value.map(t => t.name).filter(Boolean) as string[])).sort(); break
        case 'gear': values = distinct(a => a.gearName); break
        case 'event': {
          // Suggest the labels (or type keys) actually present on loaded rides.
          const set = new Set<string>()
          for (const a of sources.activities.value ?? [])
            for (const e of a.events ?? []) { const v = e.label || e.type; if (v) set.add(v) }
          values = Array.from(set).sort()
          break
        }
        case 'loc': values = Array.from(new Set([...distinct(a => a.startLocation), ...distinct(a => a.endLocation)])).sort(); break
        case 'country': values = Array.from(new Set([...distinct(a => a.startCountry), ...distinct(a => a.endCountry)])).sort(); break
        case 'num': return [] // free-form numeric, nothing to suggest
      }
      return values.filter(v => v.toLowerCase().includes(partial)).slice(0, 12)
    }
    // No facet trigger → complete the trailing bare word into a field key.
    const m = query.value.match(/(?:^|\s)([^\s:]*)$/)
    const token = (m ? m[1] : '').toLowerCase()
    if (!token) return []
    return ALL_KEYS.filter(k => k.startsWith(token)).map(k => `${k}:`).slice(0, 8)
  })

  // ── Mutations ──────────────────────────────────────────────────────────────
  function commitFilter(key: string, rawValue: string) {
    const def = ALIAS_TO_FIELD.get(key.toLowerCase())
    if (!def) return
    const { op, value, value2 } = (def.kind === 'num' || def.kind === 'year')
      ? parseRange(rawValue)
      : { op: '=' as Op, value: rawValue, value2: undefined }
    if (value === '') return
    activeFilters.value.push({ field: def.key, op, value, value2, label: chipLabel(def.key, op, value, value2) })
  }

  /** Apply a dropdown suggestion (either a `key:` stub or a value for the open facet). */
  function applySuggestion(val: string) {
    if (val.endsWith(':')) {
      // Replace the trailing bare word with `key:`.
      query.value = query.value.replace(/(?:^|\s)([^\s:]*)$/, (whole, tok) =>
        whole.slice(0, whole.length - tok.length) + val)
      return
    }
    const trig = currentTrigger.value
    if (!trig) return
    // Preserve any classic prefix operator the user already typed (e.g. elev:>…).
    // Suffix / between forms are typed-and-committed directly, not dropdown-picked.
    const { op } = parseRange(trig.partial)
    const prefix = (op === '>' || op === '<' || op === '>=' || op === '<=') ? op : ''
    commitFilter(trig.key, prefix + val)
    query.value = query.value.slice(0, query.value.length - trig.raw.length).trimEnd()
    if (query.value) query.value += ' '
  }

  /** Commit whatever facet the caret is on (Enter without a dropdown pick). */
  function commitTrigger(): boolean {
    const trig = currentTrigger.value
    if (!trig || trig.partial === '') return false
    commitFilter(trig.key, trig.partial)
    query.value = query.value.slice(0, query.value.length - trig.raw.length).trimEnd()
    if (query.value) query.value += ' '
    return true
  }

  /**
   * Add a `tag:<name>` WHERE condition (used by the tag-tree double-click /
   * Enter). Matches by name like a typed `tag:` facet, so evaluation goes
   * through the same transitive path (parent matches all descendants). No-op
   * when an identical tag filter is already committed.
   */
  function addTagFilter(name: string) {
    const v = (name ?? '').trim()
    if (!v) return
    const dup = activeFilters.value.some(
      f => f.field === 'tag' && f.value.toLowerCase() === v.toLowerCase(),
    )
    if (dup) return
    activeFilters.value.push({ field: 'tag', op: '=', value: v, label: chipLabel('tag', '=', v) })
  }

  /**
   * Add a `gear:<name>` WHERE condition (used by the Gear dock picker). Matches
   * gearName by substring like a typed `gear:` facet — does NOT touch ride
   * metadata. No-op when an identical gear filter is already committed.
   */
  function addGearFilter(name: string) {
    const v = (name ?? '').trim()
    if (!v) return
    const dup = activeFilters.value.some(
      f => f.field === 'gear' && f.value.toLowerCase() === v.toLowerCase(),
    )
    if (dup) return
    activeFilters.value.push({ field: 'gear', op: '=', value: v, label: chipLabel('gear', '=', v) })
  }

  /** Add a `sport:<key>` filter (used by the sport-icon facets). No-op if dup. */
  function addSportFilter(key: string) {
    const v = (key ?? '').trim()
    if (!v) return
    if (activeFilters.value.some(f => f.field === 'sport' && f.value.toLowerCase() === v.toLowerCase())) return
    activeFilters.value.push({ field: 'sport', op: '=', value: v, label: chipLabel('sport', '=', v) })
  }

  function removeFilter(idx: number) {
    activeFilters.value.splice(idx, 1)
  }

  function popLastFilter() {
    activeFilters.value.pop()
  }

  function clearAll() {
    query.value = ''
    activeFilters.value = []
  }

  // ── Serialization (preset round-trip) ───────────────────────────────────────
  function serialize(): string {
    const facetStr = activeFilters.value.map(f => f.label).join(' ')
    return [facetStr, query.value.trim()].filter(Boolean).join(' ')
  }

  function load(queryString: string | null | undefined) {
    const { filters, freeText } = tokenize(queryString ?? '')
    activeFilters.value = filters
    query.value = freeText
  }

  /** True when nothing is filtering the list. */
  const isEmpty = computed(() => activeFilters.value.length === 0 && query.value.trim() === '')

  // Distinct places referenced by committed `near:` filters — the consumer
  // geocodes these and populates `sources.geoBoxes`.
  const nearTerms = computed(() =>
    Array.from(new Set(activeFilters.value.filter(f => f.field === 'near').map(f => f.value.toLowerCase()))))

  return {
    query,
    activeFilters,
    suggestions,
    currentTrigger,
    matches,
    nearTerms,
    isEmpty,
    applySuggestion,
    commitTrigger,
    addTagFilter,
    addGearFilter,
    addSportFilter,
    removeFilter,
    popLastFilter,
    clearAll,
    serialize,
    load,
  }
}
