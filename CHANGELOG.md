# Changelog

All notable changes to TourGaze are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions follow SemVer.

## [Unreleased]

### Added
- **Disaster recovery from sidecars** — rebuild the whole DB from the store alone (`POST /api/admin/recover`): re-parses each ride's track for the recomputable fields and reapplies the sidecar metadata (gear/tags/rider by name, events, weight). Dedups byte-identical duplicate folders and is idempotent.
- **Library sidecar** (`store/library.metadata.json`) — rider profiles + the full gear list, so recovery restores the user and gear not attached to any ride (the per-ride sidecars can't capture those).
- **Gear weight (kg)** — added to the rider's body weight as the system mass in the cycling-power estimate.
- **`event:` Tours facet** — filter rides by ride-event type/label (e.g. `event:puncture`), with autocomplete from the event-type masterdata + events on loaded rides.
- **Photos tab upload** — drag-and-drop or pick photos/videos straight onto a ride (no longer have to open the Edit panel).
- **Settings → Storage**: "Back up metadata now" (on-demand full sidecar export) and "Remove orphan folders" (reclaim leftover `store/<id>/` dirs).

### Changed
- **Leaner sidecar export** — per-ride sidecars are written on change only; the small library sidecar on change + startup; the bulk startup and nightly full sweeps are gone (a full reconcile is on demand via the Settings button).
- **Compare (ghost-chase)** — shows the GPS-overlap % per candidate again; stricter matching so rides sharing only the hometown corridor no longer match.
- **Migrations squashed** — folded the description-widen + gear-weight migrations into the V1 baseline; fresh installs apply V1–V3.
- **Inbox** — an already-imported (byte-identical) file stays as an "already imported" card with a "Remove duplicate" button instead of silently disappearing.

### Fixed
- `pruneOrphans` scanned only the store top level — it never pruned real per-ride orphans and wrongly deleted the library sidecar; it now walks ride folders and skips the library sidecar.

## [1.0.0] — 2026-06-17

First public release.

### Added
- FIT and GPX import (provider-pattern parsers) with inbox staging + Garmin auto-scan.
- Faceted Tours search (JIRA-style): `sport:`, `year:` (incl. `2014+`, `2014-`, `2014..2018`), `tag:`, `gear:`, `loc:`, `country:`, numeric `dist:`/`elev:`/`hr:`/`speed:`, free text; named saved presets.
- Hierarchical tags (recursive matching), reusable tree widget for browse + filter; per-tag Lucide icons with a searchable icon window.
- Gear management (bikes/shoes/…), assign on import/edit, bulk-assign across filtered rides, per-gear dashboard stats, `gear:` filter.
- Cinematic replay cameras (chase / drone / hollywood / helicopter / follow / top-down).
- Dashboard: distance/elevation/time by year, sport and gear.
- User-managed custom map providers (raster XYZ + vector style), merged into the basemap picker.
- Backup & recovery: per-ride `*.metadata.json` sidecars written on every change (after-commit, async) + startup/nightly reconcile; `RideMetadata` API + ZIP export that recreates the original dropped files.
- Two foldable areas in the Tours pane (Filter & grouping / Results), persisted layout, auto-open + preview-first on filter change.
- About page (FOSS + credits); goat brand mark & favicon.

### Changed
- Single JPA-generated Flyway baseline (consolidated the former V1–V7) with indexes for fast loads.
- Dropped redundant `map.tile.*` settings (superseded by the provider system).

### Fixed
- Replay camera could freeze / fling the rider off-screen (dead-end hold + pitched-projection); tuned zoom/pitch/look-ahead.
- White tile seams during fast map movement (background layer + zero fade).
- Saved-preset grouping (incl. ad-hoc tag-children) now round-trips correctly.
