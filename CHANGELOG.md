# Changelog

All notable changes to TourGaze are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions follow SemVer.

## [Unreleased]

_Nothing yet._

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
