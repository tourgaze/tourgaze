# Backlog

Forward-looking only: remaining work + recorded decisions. **Shipped history
lives in `CHANGELOG.md` and the code** — it's not duplicated here. Design notes
for the big v2 features are in `.claude/memory/project_v2_vision.md`.

Nothing below is a committed schedule; order shifts with what becomes urgent.

---

## Status — v1 is feature-complete

The shipping app does everything v1 set out to do (plus a large slice of the
original v1.5/v2 plan). What remains is **optional polish** and **future
vision** — no release blockers.

---

## Near-term polish (nice-to-have)

- [ ] **Tag editing — drag-to-reparent & inline rename.** Reparenting and rename
      already work via `TagEditDialog` (and delete shows an affected-counts
      warning). Missing only: drag a row onto another to reparent, and
      rename-in-place on the row instead of opening the dialog. (Today's
      `draggable` is tag→activity, not tag→tag.)
- [ ] **One-click prune of orphan store folders.** The integrity check already
      *detects* orphan `store/<id>/` dirs (`GET /api/admin/integrity`); add a
      guarded "remove" action. (Supersedes the old `pruneOrphans` no-op.)
- [ ] **Video first-frame poster** (ffmpeg if on PATH). Videos are accepted and
      shown, but there's no generated poster frame yet.
- [ ] **Optional UI unlock for encryption** instead of env vars
      (`TOURGAZE_STORE_*`). Lower priority — the env-var model (matros-style)
      works and is the documented path.

---

## v1.5 — map/AI enrichment (future)

Shipped already: Nominatim reverse-geo + similar-ride tag voting, Open-Meteo
weather auto-fill, persisted start/end location, OSM Overpass passes + named
peaks. Remaining:

- [ ] **LLM-summarised ride description** — local Ollama (or Anthropic API behind
      an opt-in) over stats + weather + tags + locations → 1–2 sentence
      `description`; "Suggest description" button on EditTour.
- [ ] **Strava-style segment matching** — popular climbs auto-named.
- [ ] **Wikipedia / Wikidata landmark cards** at start/end/waypoints. Hook
      exists: `geo_feature.wikidata` is already stored.

---

## v2 — comparison-replay vision

Foundation + ghost-chase replay + media are shipped (route fingerprint,
`findSimilar`, coloured ghosts, finish-together toggle, inline race bars,
off-screen edge arrows, gallery, EXIF placement, map media markers). Remaining:

- [ ] **Hausrunde auto-detection** — cluster activities by start proximity +
      fingerprint Jaccard; banner "looks like your Hausrunde — tag them all?" on
      clusters ≥3, then auto-tag future imports once a cluster name is confirmed.
      (`PredictionService` already does Hausrunde-style start clustering for
      geocoding; the cluster-naming/banner feature is the new part.)
- [ ] **Long-segment compare (Strava-style)** — compare currently spans whole
      tracks; detect the longest contiguous overlap window and zoom the chart +
      timeline to it.

---

## v3 — LLM-curated "Hollywood" replay (vision)

Goal: replay-with-media becomes a short film — map glides, camera slows at media
points, photo/video fades in (Ken-Burns on stills), caption as subtitle. The
smooth-replay pipeline (sub-point interp + camera strategies + dead-end hold) is
already in place; this is gated on LLM curation quality.

- [ ] **LLM photo description + highlight rank** — local vision model (Ollama
      `llava`/`qwen2-vl`) preferred, Anthropic API as opt-in fallback.
- [ ] **Auto-cut shortlist** — top-N by score, spread across the ride duration.
- [ ] **Cinematic overlay during replay** — corner photo card + Ken-Burns, PiP
      for video, caption subtitle (local time via `app.timezone`).
- [ ] **Music sync** *(stretch)* — align cuts to the beat of a chosen track.
- [ ] **v3 prep columns** — `activity_media.highlight_score`, `caption_ai`,
      `media_tags`. Add when the LLM pass lands (not present yet).

**Guardrails (don't break before v3):**
- The per-activity track JSON files in `cache/` are load-bearing for the
  smooth-coords pipeline the cinematic overlay needs — don't drop them.
- Encryption must keep covering media under `store/<id>/media/`, not just tracks.
- `media.captured_at` stays UTC; render local time via `app.timezone` at display.

---

## Out of scope (decisions, not "later")

Consciously dropped — recorded so future sessions don't re-litigate.

- **Auto-tag by region / country / island on import.** Geo is derived data, not
  user intent: materialising `region`/`country` as tags polluted the hand-made
  tag space (`epic`, `commute`) with `Balearische Inseln` / `ES` noise. The facts
  already live as first-class activity fields (`startLocation`/`startCountry`/
  `endLocation`/`endCountry`), enough to browse/group by location. Import no
  longer proposes geo tags. *Hook if this ever returns:* a hierarchical
  `Location` root with rename-safe matching needs a content-derived stable key on
  `tag` (a `sourceKey`, e.g. `loc:es:illes-balears`) — the row's ULID id can't
  dedupe re-derived text, and matching by display name breaks on rename.
- **Re-encryption migration when enabling crypto on a populated install.** matros
  has none either: new files follow the current setting, old plaintext stays
  readable via fallback. Not worth building.
- **3D extruded terrain (`setTerrain`)** — blacked out the map on some GPUs and
  let the pitched replay camera clip under the mesh. Ships 2D hillshade only;
  revisit with camera-collision guards only if there's demand.
- **Dexie / IndexedDB pre-cache of all tracks** — on localhost the browser HTTP
  cache + TanStack Query already cover this. Not worth the ceremony.
- **JWT auth + user passwords** — tourgaze is a personal-local app; no auth +
  optional crypto-at-rest is the right trade-off. Revisit only if network
  exposure becomes a goal.
