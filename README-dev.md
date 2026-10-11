# TourGaze — Developer Guide

Architecture, conventions and workflows for hacking on TourGaze. For the
product overview see [README.md](README.md).

## Layout

A monorepo with three top-level pieces:

```
server/     Spring Boot 3 (Java 25) — REST API + parsing + storage
frontend/   Vue 3 + Vite + TypeScript — the SPA
spec/       openapi.json — generated from the backend, consumed by the frontend
```

### Backend packages (`io.github.tourgaze`)

| Package | Responsibility |
|---|---|
| `controller` | REST endpoints. **Return DTOs only — never entities.** |
| `dto` | Wire shapes (records). |
| `entity` | JPA entities (the domain model). `Weather` is an `@Embeddable`. |
| `enums` | Domain enums (`ActivityType`) — `@Schema(enumAsRef)` + `@JsonValue`. |
| `parser` | Ride-file parsing — `TrackParser` registry + `TrackFileParser` providers (`FitParser`, `GpxParser`, `KmzParser`), `ParseResult`, `TrackPoint`, `SourceFormat`. |
| `service` | Business logic (import, media, similarity, weather, proposals, …). |
| `service.mapper` | MapStruct entity→DTO mappers. |
| `repository` | Spring Data JPA repositories. |
| `store` | `StorageService` — paths under `~/.tourgaze/`. |
| `util` | `Geo` (distance), `GeoHash`, `ShortId`. |

### Frontend (`frontend/src`)

`views/` (routed pages) · `components/` (incl. `ActivityMap`, `MapRenderer`,
`EliteStats`, panels) · `composables/` (track data, HR zones, replay cameras) ·
`api/client.ts` (typed fetch wrappers) · `types/schema.ts` (generated) ·
`enums/generated/` (generated) · `lib/geo.ts` (distance) · `workers/` (off-thread
segment builder).

## Running

**Prerequisites:** JDK 25+, Node 20+.

```bash
cd server   && mvn spring-boot:run          # → http://localhost:18085
cd frontend && npm install && npm run dev    # → http://localhost:5173 (proxies /api → 18085)
```

The Vite dev server proxies **`/api/**`** only — hit the backend directly on
`:18085` for `/v3/api-docs`, etc.

## Database & schema

- H2 file DB at `~/.tourgaze/db`. **Flyway owns the schema**;
  `spring.jpa.hibernate.ddl-auto=validate` (Hibernate only checks the live schema
  matches the entities — it never alters it).
- `src/main/resources/db/migration/V1__baseline.sql` is the full schema,
  **generated from the entities via Hibernate schema export** so `validate`
  accepts it. Pre-1.0 you may regenerate V1 the same way; **post-release add
  forward `V2__*.sql` migrations and never edit V1.** Add `@Index` for new lookups.
- Regenerate V1: run on a throwaway in-mem DB with
  `--spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create`
  `--…scripts.create-target=./target/V1__baseline.sql`, then copy into `db/migration/`.
- For a clean wipe: delete `~/.tourgaze/db/` and restart (Flyway rebuilds from V1).
- ⚠️ The dev DB holds real rides — never bulk-delete rows.

## OpenAPI → TypeScript (the type pipeline)

The backend is the source of truth for API types. Regenerate after any DTO/enum/
endpoint change — **don't hand-edit `schema.ts`**:

```bash
# 1. Spec — boots the app on a throwaway in-memory DB (port 18089), pulls
#    /v3/api-docs, writes spec/openapi.json, stops. Doesn't touch :18085 or real data.
cd server && mvn -Pspec verify -DskipTests

#    (manual fallback: curl -s http://localhost:18085/v3/api-docs -o ../spec/openapi.json)

# 2. Types — schema.ts (openapi-typescript) + enums/generated/* (per OpenAPI enum)
cd frontend && npm run generate          # = gen:api + gen:enums
```

## Conventions

- **DTOs at the boundary.** Controllers expose DTOs, never entities (avoids
  lazy-loading leaks / over-posting). Straight entity→DTO projections go through a
  **MapStruct** mapper in `service.mapper` (`ActivitySummaryMapper`, `MarkerMapper`).
  *Computed/aggregate* DTOs stay hand-built in their service (`SimilarRideDto`,
  `InboxItemDto`, `RideMetadataDto`) — don't force those through MapStruct.
- **Enums** are Java enums with a lowercase `@JsonValue` wire value (matches the DB
  column, so no migration) + a lenient `@JsonCreator` (unknown → `OTHER`/null) +
  `@Schema(enumAsRef)`. They surface in the frontend as generated unions and as
  `enums/generated/*` (enum + List + Labels).
- **New ride formats:** implement `TrackFileParser` in `io.github.tourgaze.parser`
  and register it as a `@Component`, then add the extension to `SourceFormat`. No
  format conditionals anywhere else (provider pattern).
- **Distance:** use `util.Geo.distanceM` / `distanceKm` (GeographicLib) on the
  backend and `src/lib/geo.ts` on the frontend. Don't re-roll haversine.
- **vue-query keys** must be reactive *values*, not getters:
  `queryKey: computed(() => ['media', id])`. Invalidate with the same value;
  use `refetchType: 'all'` when a list query may be inactive.
- Match the surrounding code's style; comments explain *why*, not *what*.

## Checks before a PR

```bash
cd frontend && npx vue-tsc --noEmit && npm run lint
cd server   && mvn verify
cd frontend && npx playwright test     # e2e — needs a running backend with data
```

Backend formatting is enforced by **Spotless** (`mvn spotless:apply`).

## Build & package

```bash
cd server && mvn clean package                    # fat jar in server/target
cd server && mvn -Pwindows-portable package       # jpackage app-image (Windows)
```

The frontend builds with `npm run build`; the `with-frontend`/portable profiles
bundle it into the distributable.

### Faster startup for `java -jar` (AOT cache)

A locally built jpackage app-image (`server/pom.xml`) builds & uses a JDK **AOT cache**
(JEP 483/514) that memory-maps the app's classes + linked state instead of re-loading
them — worth ~1s off cold start (≈3.0s vs ≈4.1s here). To get the same for a plain
`java -jar` run, generate a cache once per jar build, then launch with it:

```bash
cd server
# 1. Train — one boot that exits on context refresh, dumping target/app.aot
java -XX:AOTCacheOutput=target/app.aot -Dspring.context.exit=onRefresh \
     -jar target/tourgaze-server-*.jar --app.start-browser=false --app.backup.on-startup=false
# 2. Run with the cache
java -XX:AOTCache=target/app.aot -jar target/tourgaze-server-*.jar
```

Regenerate `app.aot` whenever the jar or the JDK changes; a stale cache is ignored with a
warning. Requires JDK 24+. **Only use a cache on the machine (CPU) that trained it:** the
1.4.2 Docker image shipped one trained on a CI runner, and on CPUs without that runner's
instruction set the JVM died with `SIGILL` instead of ignoring it — so the image ships none.

## Docker & Helm

TourGaze ships as a **single container** (matros convention): the fat JAR already
bundles the built SPA under `/static`, so one image serves the REST API **and**
the UI on port `8085`. Everything stateful (H2 DB, media, cache, tiles) lives
under `TOURGAZE_DATA_DIR` (`/data` in the container) — mount it as a volume.

### Docker

Build the JAR first, then the image (`infra/Dockerfile` copies `server/target/*.jar`):

```bash
cd frontend && npm ci && npm run build
mvn -f server/pom.xml clean package -DskipTests
docker compose -f infra/docker-compose.yml up --build      # → http://localhost:8085
```

The image is published as **`mschwehl/tourgaze:latest`**. `infra/docker-compose.yml`
wires a named `tourgaze-data` volume (swap for a bind mount `../data:/data` to keep
the DB host-visible). The entrypoint runs headless and passes `--app.start-browser=false`.

### Helm

A production chart lives in `helm/tourgaze/` (Deployment + Service + Ingress + PVC):

```bash
helm install tourgaze ./helm/tourgaze \
  --set ingress.hosts[0].host=tourgaze.example.com
```

Key `values.yaml` knobs: `image.repository`/`tag`, `persistence.{enabled,size,storageClass}`
(the `/data` PVC — **set a real `storageClass` in production**), `service.port`, and
`ingress.{enabled,className,hosts,tls}`. The default ingress sets a 100 MB body limit
so FIT/GPX/KMZ + photo uploads aren't rejected. Single replica only — H2 is a local
file DB, so `replicaCount` must stay `1` (no horizontal scaling without swapping the
datastore).

## Tech stack

Spring Boot 3 · Java 25 · H2 · MapStruct · Garmin FIT SDK · jpx · GeographicLib ·
metadata-extractor · commons-imaging · springdoc-openapi · Caffeine ·
Vue 3 · Vite · TypeScript · TanStack Query · MapLibre GL · ECharts · Tailwind ·
openapi-typescript · Playwright.

Per-dependency copyrights & licenses (incl. the Garmin FIT SDK and the runtime
map/open-data services — OSM, Overpass, Mapzen terrain, Open-Meteo) are listed in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
