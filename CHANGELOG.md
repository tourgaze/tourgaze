# Changelog

All notable changes to TourGaze are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions follow SemVer.

## [Unreleased]

### Added
- **Year totals in the Tours list** — the grouped results now end with a running summary row (total distance + ride count) pinned under the last group, so the library's totals stay in view while you scroll.
- **Desktop app: system tray & no console window** — the portable app-image now launches as a proper windowed app instead of a console: a brief startup splash, the browser opens automatically, and it lives in the system tray with **Open TourGaze**, **Show Logs** (a live tail-follow log viewer — the console replacement) and **Quit** (a clean, graceful shutdown). The TourGaze goat now brands the tray, splash, window and taskbar/exe icon. Best-effort on Linux (shown on KDE / XFCE / MATE / Cinnamon; on GNOME or headless it self-disables and the app runs on). Toggle with `app.system-tray`.

### Changed
- **Java 25 runtime** — the backend now builds and runs on Java 25 (LTS). The portable app-images bundle it; the headless JAR now needs a Java 25 JRE.
- **~3× faster cold start (~10 s → ~3 s)** — three fixes: dropped H2's `AUTO_SERVER` mode, which stalled the first DB connection ~5 s on Windows (an external tool can still open the file DB when the app is stopped); deferred the Hibernate bootstrap so it initialises in parallel with the rest of startup; and bundled a JDK AOT class-data cache in the Docker image and the jpackage app-image so classes are memory-mapped instead of re-loaded. A plain `java -jar` starts in ~4 s.

### Fixed
- **GPX elevation gain** — long rides from dense GPX (points a few metres apart) reported near-zero or no total ascent: climb was gated per point at 1 m, so a genuine but gradual sub-metre-per-point ascent was discarded entirely as GPS noise. Ascent/descent are now measured against a moving anchor with a cumulative hysteresis band, so steady climbs accumulate in full (FIT was unaffected — it uses the device's own recorded total). Existing GPX rides were recomputed.

### Security
- **js-yaml DoS (GHSA-h67p-54hq-rp68, moderate)** — forced the transitive `js-yaml` up to 4.3.0. It entered only through the build-time OpenAPI type generator (not shipped to users), but the fix clears the advisory.

## [1.2.0] — 2026-07-02

### Added
- **Replay "Free" mode keeps the rider findable** — when the camera is parked and the replay marker rides off the visible map, a red arrow pins to the nearest edge pointing at it; click to recenter without leaving Free mode (mirrors the compare-rider off-screen indicators).
- **Viewer zoom/angle persists while Following** — your own zoom / pitch / rotate gestures during a cinematic follow are kept as a persistent offset on the camera path instead of being snapped back on the next frame.
- **Periodic database flush** — H2 is checkpointed to disk on a fixed interval (`tourgaze.db.checkpoint-ms`, default 60 s), plus a final flush on shutdown, so a hard kill (force-closed console window, power loss) can't drop the last few seconds of just-saved metadata. A normal Ctrl-C was already safe via graceful shutdown.

### Changed
- **Refuse to start against a newer database** — if the local DB was already migrated by a newer build, the app now fails fast with a plain-language "application failed to start" message (and keeps the console window open so it can be read) rather than running against a schema it was never built for.
- **Faster track loading** — per-activity JSON track/chart caches are now stored gzip-compressed on disk and streamed to the browser with `Content-Encoding: gzip` (compressed once when the cache is built, not re-compressed per request), and tagged with a strong `ETag` + long-lived private `Cache-Control` so a revisited ride re-fetches nothing (304 Not Modified). Cache building takes a per-activity lock instead of a single global monitor, so opening two different rides builds them concurrently rather than serialising behind one mutex.

### Fixed
- **Ride times are stored in UTC** — imports previously persisted timestamps shifted by the server's local offset: Hibernate binds `java.time.Instant` to the DB's `timestamp with time zone` columns using the JVM default zone, so on e.g. Europe/Berlin every imported FIT/GPX ride landed 1–2 h early (`hibernate.jdbc.time_zone` does not cover this path). The app now pins the JVM to UTC at startup, so an import persists the exact GPS instant; the frontend still renders each time in the viewer's own local zone.
- **Tour switches no longer flood the browser with tile requests** — opening a ride pre-warmed its route tiles by firing every fetch at once (~1500 tiles × up to two providers), tripping `net::ERR_INSUFFICIENT_RESOURCES` and stalling the map for several seconds on every switch — and, because the flood never let the browser's immutable tile cache serve, switching back to a region got no faster. The prefetch now drains through a small bounded pool (only a handful of fetches in flight at once), and selecting a new ride abandons the previous ride's in-flight warm instead of stacking on top of it. A brief loading spinner now covers the map while the newly-selected ride's track loads.
- **Near-instant tour switching** — switching rides was blocked for seconds by Vue reactivity, not by real work: the replay-camera bearing precompute looped over the whole track reading `point.lat`/`point.lon` through reactive proxies, so millions of property reads each paid the proxy get-trap tax (~8 s in a trace, while the actual distance maths was ~200 ms). Track point arrays are now kept out of deep reactivity (`markRaw`), the hot bearing/hold-hint/direction loops read from plain `Float64Array`s, and the whole-track replay-camera simulation is deferred to the first scrub/Play instead of running on every switch. Combined with a snappy 350 ms camera ease, switching is now instant.
- **GPS pre-lock clock garbage** — tracks whose first standstill samples carry a sentinel clock (~1999, or the firmware epoch) before the GPS fix locks — which made the parse report a start decades before the end and a multi-year "duration" — are now repaired centrally on parse, so import, the track cache and export all agree. Clean files, including continuous multi-day tours, are left untouched.

## [1.0.0] — 2026-06-26

First public release.

### Import & inbox
- FIT and GPX import (provider-pattern parsers) with inbox staging + Garmin auto-scan.
- **Consistent inbox model** — every ride file ends up in the repository; identity is the exact content hash (instant, no parse); a file already in the library shows as an "already imported" card. Two terminal actions only: **Import** (copy into the library, delete the device source only after the copy succeeds) and **Dismiss** (never re-stage). Works the same for watch-folders and browser drag-drop/upload (the only path in Docker/LAN).
- **`event:` Tours facet** — filter rides by ride-event type/label (e.g. `event:puncture`), with autocomplete from the event-type masterdata + events on loaded rides.
- **Photos tab upload** — drag-and-drop or pick photos/videos straight onto a ride (no longer have to open the Edit panel).

### Ride data & analysis
- **Per-point raw data page** (`/tour/:id/raw`, reachable from a quiet "Raw" link in the Tours viewer): per-channel line charts, a virtualised table of every sample, and a summary strip of ride aggregates (distance, moving/total time, elevation ↑/↓, calories, work, avg+max HR/power/speed/cadence).
- **Raw data API** (`GET /api/activities/{id}/raw`) — columnar per-point channels served from the reduced ride sidecar (the primary source) or, on demand, the full-resolution track; `channels=` selects which columns.
- **Typed sensor channels** per ride (`SensorType`: heart-rate, cadence, power, speed, altitude, temperature, barometer, radar, GPS) — detected on import, surfaced as a published OpenAPI enum and as sensor chips on the raw page.
- **Calorie estimation** — mechanical work for power-equipped cycling, otherwise the Keytel (2005) heart-rate regression; computed on import and backfillable for existing rides. Gear weight (kg) is added to the rider's body weight as the system mass in the power-based estimate.
- **Rider FTP (W)** added to the profile, alongside elevation-loss capture for new imports.
- Faceted Tours search (JIRA-style): `sport:`, `year:` (incl. `2014+`, `2014-`, `2014..2018`), `tag:`, `gear:`, `loc:`, `country:`, numeric `dist:`/`elev:`/`hr:`/`speed:`, free text; named saved presets.
- Hierarchical tags (recursive matching), reusable tree widget for browse + filter; per-tag Lucide icons with a searchable icon window.
- Gear management (bikes/shoes/…), assign on import/edit, bulk-assign across filtered rides, per-gear dashboard stats, `gear:` filter.
- Cinematic replay cameras (chase / drone / hollywood / helicopter / follow / top-down).
- Dashboard: distance/elevation/time by year, sport and gear.
- Compare (ghost-chase) with per-candidate GPS-overlap %.

### Maps, layout & shell
- User-managed custom map providers (raster XYZ + vector style), merged into the basemap picker.
- Two foldable areas in the Tours pane (Filter & grouping / Results), persisted layout, auto-open + preview-first on filter change.
- About page (FOSS + credits); goat brand mark & favicon.

### Backup & recovery
- Per-ride `*.metadata.json` sidecars written on change (after-commit, async); `RideMetadata` API + ZIP export that recreates the original dropped files.
- **Disaster recovery from sidecars** — rebuild the whole DB from the store alone (`POST /api/admin/recover`): re-parses each ride's track for the recomputable fields and reapplies the sidecar metadata (gear/tags/rider by name, events, weight). Dedups byte-identical duplicate folders and is idempotent.
- **Library sidecar** (`store/library.metadata.json`) — rider profiles + the full gear list, so recovery restores users and gear not attached to any ride.
- **Settings → Storage**: "Back up metadata now" (on-demand full sidecar export) and "Remove orphan folders" (reclaim leftover `store/<id>/` dirs).

### Platform
- Single JPA-generated Flyway baseline (the V1 baseline includes `elevation_loss_m`, `calories`, `ftp_w` so fresh installs need no day-2 migration).
- Single-container deployment (SPA bundled into the Spring Boot jar); H2 (file) for local, PostgreSQL for production; Docker image + Helm chart.

### Known limitations
- Garmin Varia radar ("cars passed") is modelled in the sensor enum but not yet parsed from FIT — needs device/developer-field support.
- Rides imported before elevation-loss capture store `elevation_loss_m = 0`; the raw page derives a gain/loss pair from the altitude samples for display.
