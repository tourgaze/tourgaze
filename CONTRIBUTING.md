# Contributing to TourGaze

Thanks for your interest! TourGaze is AGPL-3.0; by contributing you agree your
changes are licensed under the same terms.

## Dev setup

- **JDK 21+**, **Node 20+**.
- Backend: `cd server && mvn spring-boot:run` (http://localhost:8085).
- Frontend: `cd frontend && npm install && npm run dev` (http://localhost:5173, proxies `/api` → 8085).

## Checks before a PR

```bash
# Frontend types + lint
cd frontend && npx vue-tsc --noEmit && npm run lint
# Backend build/tests
cd server && mvn verify
# End-to-end (needs a running backend with some data)
cd frontend && npx playwright test
```

Code style is enforced by **Spotless** on the backend (`mvn spotless:apply`).

## Conventions

Full details in **[README-dev.md](README-dev.md)**. The essentials:

- **Database:** Flyway owns the schema (`V1__baseline.sql`, generated from the
  entities); Hibernate `ddl-auto=validate` only checks it. Add an `@Entity`/field
  (+`@Index`) and ship a forward `V2+` migration — never edit V1.
- **API types:** `src/types/schema.ts` + `src/enums/generated/` are generated from the
  backend OpenAPI — `cd server && mvn -Pspec verify -DskipTests`, then
  `cd frontend && npm run generate`. Don't hand-edit them.
- **DTOs only** out of controllers; entity→DTO via MapStruct mappers in `service.mapper`.
- **New ride formats:** implement `TrackFileParser` in `io.github.tourgaze.parser` and
  register it as a bean (provider pattern) — no format conditionals elsewhere.
- **Distance:** use `util.Geo` (backend) / `src/lib/geo.ts` (frontend), not haversine.
- Keep new code in the style of the surrounding code; comments explain *why*.

## Reporting bugs

Open an issue with steps to reproduce, the ride file format if relevant, and
console/server logs. Never attach files containing private location data you
don't want public.
