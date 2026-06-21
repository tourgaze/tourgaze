# Backlog

Outstanding work organized by target release. Items here are not committed
schedule — order shifts based on what becomes urgent first.

See `.claude/memory/project_v2_vision.md` for design notes on the v2 features.

---

## ✅ Shipped (in v1)

A large slice of the original v1.1 / v1.5 / v2 plan already landed. Recorded
here so we don't re-plan finished work; details stay in their sections below
with `[x]`.

- **Multiple inbox / watch sources** — extra watch folders (Google-Drive-safe,
  copy-never-move) + an Inbox masterdata section.
- **Route fingerprint + similarity** — `route_geocells` at import, Jaccard
  `findSimilar` (projection-based, no N+1).
- **Similar-rides + ghost-chase race** — in-viewer multi-rider race (coloured
  ghosts, inline gap/HR bars, real-vs-normalized timing, picker), off-screen
  Mario-Kart edge arrows, and a stats compare-detail page.
- **Media** — table + SHA-dedup storage, upload (incl. KMZ embedded photos),
  EXIF auto-placement, on-import rescale, gallery, map markers with jump-to.
- **OSM Overpass landmark highlights** — auto-detected passes crossed + named
  peaks near a ride, lazy per-region cache, map markers (the v1.5 "Overpass
  nearby landmarks" item).

---

## v1.1

- [ ] **Good tag editing (matrosdms-style)** — *partially shipped* (filter,
      icons, edit dialog, cascading delete + e2e exist via `TagTree.vue` /
      `TagEditDialog.vue`). **Remaining:** drag-to-reparent, rename-in-place on
      the row, and an explicit "N children / N rides affected" delete warning.

- [x] **Multiple inbox / watch sources** — ✓ shipped. A single `inbox.sources`
      setting holds a JSON list of `{label, path}` folders (kept in one
      key/value row, no `watch_source` table — simpler, same behaviour),
      scanned by `WatchFolderScanService` (scheduled, SHA-256 dedup,
      copy-never-move). The Garmin device folder is just another entry now (the
      old standalone `GarminScanService` was removed). A `tourgaze.inbox.sources`
      yaml list provides an optional deployment default; the DB setting wins
      when present. Edited in Settings → Inbox folders; timezone moved to
      Settings → Language and the old "Device & app" section was removed.

- [~] **Port matrosdms storage + optional AES-GCM crypto** *(was task #28)* —
  *mostly shipped.*
  - ✓ `EncryptionService` (AES-256-GCM, 12-byte IV prefix + 128-bit tag,
    streaming) + `EncryptionConfig` (Argon2id KDF, t=3/64MB/p=1, key in memory,
    wiped on shutdown). BouncyCastle 1.80.
  - ✓ Opt-in, **env-driven** (matros model): `TOURGAZE_STORE_CRYPTOR` /
    `_PASSWORD` / `_SALT`; off by default (plaintext, no `.enc`). Fails fast if
    on without a password.
  - ✓ **Per-ride folder layout** `store/<rideId>/` (track + `media/` + sidecar);
    track files written `*.enc` and transparently decrypted on every read
    (`StorageService.moveIntoStore`/`readStoreBytes`/`openStore`); import, track
    cache, peaks, similarity, wikimedia, export all routed through it. Sidecar
    kept plaintext (recovery metadata).
  - ✓ **Media encrypted too** — photos/videos written `*.enc` in
    `store/<id>/media/`, served decrypted, EXIF/manifest read through
    `StorageService.openEncrypted`/`writeEncrypted`/`listLogicalNames`. The
    `media.json` manifest stays plaintext (metadata only).
  - **Remaining:** (b) re-encryption migration when enabling on a populated
    install (matros has none either — new files follow the current setting, old
    plaintext stays readable via fallback); (c) optional UI unlock instead of env
    vars; (d) `pruneOrphans` is now a no-op with nested folders — rewrite to
    sweep orphan `store/<id>/` dirs.

---

## v1.5 — AI / map-API enrichment

Already shipped: `PredictionService` (Nominatim reverse geo + similar-activity
tag voting), weather auto-fill from Open-Meteo, persisted `start_location` /
`end_location`, and OSM Overpass landmark highlights (below). Remaining:

- [ ] **Auto-tag by region / country / island** on import.
  - We surface `country` / `region` on the PredictionDto but don't materialise
    them as Tags. Probably flat at the root (auto-tags coexist with the
    user's "Pass / Gamsenpass" ontology). For each `(place, region, country)`:
    case-insensitive find-or-create (deterministic colour from name hash),
    apply, mark the tag `auto: true` so the UI can distinguish.
- [ ] **LLM-summarised ride description** — local Ollama (or Anthropic API
      behind a `crypto.enabled`-style opt-in) over stats + weather + tags +
      locations → 1–2 sentence `description`; "Suggest description" button on
      EditTour.
- [ ] **Public map API enrichment**
  - [ ] Strava-style segment matching (popular climbs auto-named).
  - [ ] Wikipedia / Wikidata landmark cards at start/end/waypoints. (We now
        store `wikidata` on `geo_feature` — the hook is there.)
  - [x] **OSM Overpass nearby landmarks** — ✓ shipped as auto-detected
        passes + named peaks: lazy per-geohash-cell fetch, cached in
        `geo_feature`/`geo_region`, matched to the ride, shown as map markers
        with localized names. Viewpoints/refuges + optional auto-tag are the
        natural follow-ups.
- [ ] **AI photo curation** *(meta — see v3 Hollywood-replay block)*.

---

## v2 — vision

The big idea: comparison-replay between rides. See
`.claude/memory/project_v2_vision.md` for the full design.

### Foundation work — ✅ done

- [x] **Route fingerprint column on `activity`** — ✓ `route_geocells`
      (space-separated geohash-7 cells) computed in `InboxService.importItem`
      via `RouteSimilarityService.fingerprint`.
- [x] **Similarity helper** — ✓ `findSimilar` (Jaccard over cell sets + shared
      tags), now backed by a scalar projection (no full `findAll`, no tag N+1).

### Similar-tour detection — ✅ done

- [x] **Tag-based "similar rides" panel** — ✓ in the viewer's Compare tab.
- [x] **Auto-suggested similar rides** from the fingerprint — ✓ overlap %
      surfaced; tag-only matches get a base score.

### Hausrunde auto-detection — pending

- [ ] **Cluster activities by `start_geohash` + fingerprint Jaccard** — banner
      "looks like your Hausrunde — tag them all?" on clusters ≥3.
- [ ] **Auto-tag future imports** once a cluster name is confirmed.

### Ghost-chase replay — ✅ done

- [x] **Second marker on the map** — ✓ coloured ghost cursors per compared ride.
- [x] **Time-alignment toggle** — ✓ real-time vs "finish together" (normalized).
- [x] **HUD / deltas** — ✓ evolved into inline per-row race bars (lead/behind by
      metres, HR-tinted) + the gap readout, rather than a single top strip.
- [x] **Picker UI** — ✓ tick rides in the Compare tab (sorted by overlap).
- [ ] **Long-segment compare (Strava-style)** — still compares whole tracks;
      detecting the longest contiguous overlap window (and zooming the chart +
      timeline to it) is not done.

### Off-screen ghost marker — ✅ done

- [x] **Edge-of-viewport indicator** — ✓ Mario-Kart arrow clamped to the map
      edge pointing at the off-screen ghost, recomputed on every camera move.

### Media on activities — ✅ done

- [x] **Media table + storage** — ✓ SHA-256-deduped files under
      `~/.tourgaze/media/`, manifest-driven.
- [x] **Upload UI** — ✓ AddTour/EditTour drop + KMZ embedded-photo extraction.
- [x] **EXIF auto-placement** — ✓ `metadata-extractor`; closest track point by
      time, GPS-distance sanity filter; embedded KML coords win over EXIF.
- [x] **Thumbnails / rescale** — ✓ on-import downscale of oversized images
      (via commons-imaging rather than the originally-planned Thumbnailator).
- [ ] **Video first-frame poster** (ffmpeg if on PATH) — videos accepted but no
      generated poster yet.
- [x] **Gallery component** — ✓ Photos tab under the elevation chart, lightbox.
- [x] **Media markers on the map** — ✓ camera pins; click jumps the playback head.

### v3 prep columns — pending

- [ ] `activity_media.highlight_score`, `caption_ai`, `media_tags` — reserved
      for the v3 LLM pass; add when that lands.

---

## v3 — vision

### LLM-curated "Hollywood" replay

Goal: Play on a ride with media becomes a short film — the map glides, at media
points the camera slows, a photo/video fades in (Ken-Burns on stills), caption
as subtitle, crossfade out. The smooth-replay pipeline (sub-point interp +
camera strategies + dead-end hold) is already in place; this is gated on LLM
curation quality.

- [ ] **LLM photo description + highlight rank** (`caption_ai`,
      `highlight_score`, `media_tags`). Local vision model (Ollama `llava` /
      `qwen2-vl`) preferred; Anthropic API as opt-in fallback.
- [ ] **Auto-cut shortlist** — top-N by score, spread across the ride duration.
- [ ] **Cinematic overlay during replay** — corner photo card + Ken-Burns,
      PiP for video, LLM caption subtitle (local time via `app.timezone`).
- [ ] **Music sync** *(stretch)* — align cuts to the beat of a chosen track.

### v3 prep checklist (don't break before then)

- The per-activity track JSON files in `cache/` are load-bearing for the
  smooth-coords pipeline that the cinematic overlay needs. Don't drop them.
- Whatever encryption lands as part of the crypto port (#28 above) must
  also cover `~/.tourgaze/media/`, not just `store/`.
- `media.captured_at` stays in UTC. The LLM caption pass renders local time
  using the `app.timezone` setting; don't bake a timezone into the column.

---

## Captured ideas (unsorted)

- [x] **Body-weight trend chart in Settings → Profile** — ✓ shipped. Plots each
      ride's recorded `weight_kg` over time (the activity history is the weight
      log). Resting-HR trend was dropped — it's a single profile value, not a
      time series, and not important enough to warrant a `health_log` table.
- [ ] **Custom gear icon, usable in replay** — let a gear entry carry an icon
      (road / MTB / gravel / run / …) and use it as the moving marker during map
      playback instead of the generic dot, so the replay reflects what you rode.

---

## Out of scope (decisions, not "later")

These came up during v1 and were consciously dropped. Record here so future
sessions don't re-litigate.

- **3D extruded terrain (`setTerrain`) on the hillshade toggle** — blacked out
  the map on some GPUs and let the pitched replay camera clip under the mesh.
  v1 ships the reliable 2D hillshade relief only; revisit 3D with proper
  camera-collision guards if there's demand.
- **Dexie / IndexedDB pre-cache of all tracks** — on localhost, the browser's
  HTTP cache + TanStack Query's in-memory cache already cover this. Not worth
  the ceremony for a local-first app.
- **JWT auth + user passwords** — ripped out in v1; tourgaze is a personal-local
  app. The current shape (no auth, optional crypto at rest) is the right
  trade-off. Revisit only if network exposure becomes a goal.
