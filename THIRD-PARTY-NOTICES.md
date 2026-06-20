# Third-Party Notices

TourGaze itself is licensed under the GNU AGPL v3 (see [LICENSE](LICENSE)).
It is built on, and at runtime fetches data from, the third-party software and
open-data services below. Each remains under its own copyright and license; the
list is provided for attribution and to help downstream packagers comply. Where a
project's exact terms govern, those terms — not this summary — control.

## Ride-file parsing

- **Garmin FIT SDK** (`com.garmin:fit`) — © Garmin Ltd. or its subsidiaries.
  Used to decode `.fit` activity files. "FIT" and the FIT logo are trademarks of
  Garmin; the SDK is distributed under the *Flexible and Interoperable Data
  Transfer (FIT) Protocol License*. Garmin's copyright notice is retained per
  that license.
- **jpx** (`io.jenetics:jpx`) — © Franz Wilhelmstötter — Apache License 2.0.
  GPX (`.gpx`) reading/writing. KML/KMZ parsing is TourGaze's own code.

## Geo / imaging / data

- **GeographicLib-Java** (`net.sf.geographiclib`) — © Charles Karney — MIT License.
  Geodesic distance (`util.Geo`).
- **metadata-extractor** (`com.drewnoakes`) — © Drew Noakes — Apache License 2.0.
  Photo EXIF (timestamp / GPS) for media placement.
- **Apache Commons Imaging** — © The Apache Software Foundation — Apache License 2.0.
  Image rescaling / thumbnails (pure-Java, headless).

## Backend framework & infrastructure

- **Spring Boot** and Spring projects — © VMware/Broadcom & contributors — Apache 2.0.
- **H2 Database Engine** (`com.h2database:h2`) — dual MPL 2.0 / EPL 1.0.
- **springdoc-openapi** — Apache License 2.0.
- **MapStruct** (`org.mapstruct`) — Apache License 2.0. Compile-time entity→DTO mappers.
- **Caffeine** (`com.github.ben-manes.caffeine`) — © Ben Manes — Apache License 2.0.

## Frontend

- **Vue 3**, **Vite**, **@vueuse/core**, **vue-echarts**, **splitpanes**,
  **notivue**, **openapi-typescript** — MIT License.
- **TanStack Query** (`@tanstack/vue-query`) — © Tanner Linsley — MIT License.
- **Apache ECharts** (`echarts`) — © The Apache Software Foundation — Apache 2.0.
- **MapLibre GL JS** (`maplibre-gl`) — © MapLibre contributors — BSD-3-Clause.
- **Lucide** icons (`lucide-vue-next`) — ISC License.
- **Tailwind CSS** — MIT License.
- **Playwright** (dev/test) — © Microsoft — Apache License 2.0.

## Map tiles & open-data services (fetched at runtime)

These are external services proxied/queried by the app; their data is **not**
bundled. Attribution is shown in-app where the data appears.

- **OpenStreetMap** — map tiles, **Overpass** (peaks/passes highlights) and
  **Nominatim** (reverse geocoding). Map data © OpenStreetMap contributors,
  licensed under the **Open Database License (ODbL)**.
- **Terrarium elevation tiles** (AWS `elevation-tiles-prod`) — terrain hillshade.
  Terrain data © **Mapzen** and contributors (Mapzen Terrain Tiles attribution).
- **Open-Meteo** — weather auto-fill. Data under **CC-BY 4.0**.

Public OSM/Overpass/Nominatim endpoints are used politely (cached, low-frequency,
custom User-Agent). For heavier or production use, self-host or point the relevant
settings at your own instances.
