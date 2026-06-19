# Changelog

All notable changes to TourGaze are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions follow SemVer.

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
