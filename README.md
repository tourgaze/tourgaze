# 🐐 TourGaze

**Local-first ride viewer — import, browse, tag, replay, compare and analyse your tours.**
TourGaze turns the FIT / GPX / TCX / KMZ files from your bike computer, watch or
phone into a searchable, taggable library of rides — with a cinematic map
replay, advanced per-ride analytics, a ghost-chase compare mode, geo-matched
photos, and a JIRA-style faceted search across everything. It all runs on your
machine; your data never leaves it.

**🌐 Overview & screenshots: [tourgaze.github.io](https://tourgaze.github.io)**

![TourGaze ghost-chase compare — racing a ride against your past self on the same route, with a live gap (metres + HR) and a Mario-Kart off-screen arrow when the ghost leaves the viewport.](doc/img/screenshot-ride-against-self.jpg)

> License: **AGPL-3.0** · No account, no cloud, no telemetry.

---

## Features

- **Import** FIT, GPX, TCX and **KMZ/KML** (OpenTracks) — drop into the inbox, or
  let the Garmin device auto-scan pick up new `.fit` files. Import pre-fills a
  sensible activity type and **guesses the gear** from your past similar rides.
- **Browse & filter** with a faceted mini-language:
  `sport:cycling year:2014..2018 gear:"Race Bike" tag:alpine dist:>50`.
- **Hierarchical tags** (a parent matches all descendants), drag-to-apply, recursive filtering.
- **Gear** (bikes / shoes / …) with per-gear stats and bulk-assign across filtered rides.
- **Cinematic replay** — drone / helicopter / follow / top-down cameras (smooth,
  anti-jitter), with a rider marker, HR/slope-coloured track, and photo fades.
- **Elite stats** — best efforts, climbing & VAM, gradient distribution, HR zones,
  training load (TRIMP), aerobic decoupling, and estimated cycling power (NP / VI).
- **Compare (ghost-chase)** — finds rides on the *same route* (GPS overlap or shared
  tag) and races two of them: a live HUD (distance / time / HR gap) and a
  Mario-Kart off-screen arrow when the ghost leaves the viewport.
- **Markers** — drop points of interest (food, viewpoint, peak, …) with categories
  and an editable description; per-ride or general (shown on every map); a
  filterable list with click-to-jump.
- **Photos & video** — drop your own (geo-matched by EXIF, shown as map pins, faded
  in during replay, in a gallery), or **auto-discover** Creative-Commons photos
  along the route from Wikimedia Commons. Public vs personal are clearly labelled;
  big uploads are downscaled (EXIF preserved).
- **Dashboard** — distance / elevation / time by year, sport and gear.
- **Custom basemaps** — raster XYZ or vector styles, managed in Settings.
- **Backup & recovery** — everything is local; per-ride `*.metadata.json` sidecars
  are written on every change, and a one-click ZIP export recreates the original
  dropped files.

## Quick start

**Prerequisites:** JDK 21+, Node 20+.

```bash
# Backend  → http://localhost:8085
cd server && mvn spring-boot:run

# Frontend → http://localhost:5173  (dev server, proxies /api → 8085)
cd frontend && npm install && npm run dev
```

Open <http://localhost:5173>, complete the first-run setup to create your rider
profile, then drop a `.fit` / `.gpx` / `.tcx` / `.kmz` into `~/.tourgaze/inbox/`
(or upload from the Inbox view) and click **Import**.

## Run with Docker

TourGaze ships as a **single container** — the API and the web UI are served
together on port `8085`. State (H2 DB, media, cache, tiles) lives under a
`/data` volume.

```bash
# from the project root: build the SPA + jar, then the image
cd frontend && npm ci && npm run build && cd ..
mvn -f server/pom.xml clean package -DskipTests
docker compose -f infra/docker-compose.yml up --build   # → http://localhost:8085
```

The published image is **`mschwehl/tourgaze`**. The compose file mounts a named
`tourgaze-data` volume (swap for a bind mount `../data:/data` to keep the DB
host-visible).

## Deploy with Helm

A production chart lives in `helm/tourgaze/` (Deployment + Service + Ingress +
persistent volume):

```bash
helm install tourgaze ./helm/tourgaze \
  --set ingress.hosts[0].host=tourgaze.example.com \
  --set persistence.storageClass=<your-storage-class>
```

Single replica only — the H2 file DB isn't horizontally scalable. The default
ingress allows 100 MB uploads (FIT/GPX/KMZ + photos). See
[README-dev.md](README-dev.md#docker--helm) for the full knobs.

## Data & privacy

Everything lives under `~/.tourgaze/` (override with `TOURGAZE_DATA_DIR`):

```
~/.tourgaze/
  db/      H2 database (ride metadata, gear, tags, markers)
  store/   original ride files + <name>_media/ photos + <name>.metadata.json sidecars
  cache/   derived track / chart JSON
  tiles/   cached map tiles
```

No accounts, no cloud, no telemetry. Map tiles are fetched once and cached locally.

## Tech stack

Vue 3 + Vite + TypeScript · Spring Boot 3 (Java 21) · H2 · MapLibre GL · ECharts ·
TanStack Query · Tailwind · Garmin FIT SDK · jpx (GPX) · GeographicLib (distance) ·
metadata-extractor + commons-imaging (EXIF) · MapStruct · Lucide.

## Attribution

Map data © OpenStreetMap contributors; basemap styles © CARTO / Esri / OpenFreeMap;
discovered photos © their authors via Wikimedia Commons (CC); weather from
Open-Meteo; reverse geocoding from Nominatim. Full credits on the in-app **About** page.

## Contributing

Developer guide: **[README-dev.md](README-dev.md)**. See also
[CONTRIBUTING.md](CONTRIBUTING.md) and [CHANGELOG.md](CHANGELOG.md).

## License

[GNU AGPL-3.0](LICENSE) © TourGaze contributors. If you run a modified version as a
network service, you must offer your users the corresponding source.
