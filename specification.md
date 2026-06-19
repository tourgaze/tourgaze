# TourGaze - Specification and Implementation Hints

This document serves as the architectural source of truth for the project, allowing easy state recovery.

## 1. Project Concept
TourGaze is a lightweight, local-first FIT file viewer designed as a modern, unbloated alternative to MyTourbook. The architecture and UI/UX heavily mirror "matrosdms" to ensure robust patterns, file-based reliance, and a clean interface.

## 2. Tech Stack
*   **Backend**: Java 25, Spring Boot 3.4.1 (with Virtual Threads enabled)
*   **Database**: Embedded H2 (file-based) + Flyway migrations
*   **Frontend**: Vue 3 (Composition API), Vite, Tailwind CSS v4, TypeScript
*   **Frontend Libraries**: Leaflet (Maps), Splitpanes (Layout resizers), Notivue (Toasts), TanStack Vue Query (Data fetching), Lucide-Vue-Next (Icons)
*   **API Layer**: OpenAPI 3 definitions -> `openapi-typescript` -> `openapi-fetch` client

## 3. Storage Architecture (The "Slim DB" Concept)
Raw GPS track points are **never** stored in the database. The system relies on a file-based storage pipeline to keep the DB small:
*   **Base Directory**: `~/.tourgaze` (configurable via `TOURGAZE_DATA_DIR` or `tourgaze.data-dir`)
*   **`inbox/`**: Watch directory. Users/API drop `.fit` files here.
*   **`store/`**: Permanent storage. Files are atomic-moved from `inbox/` to `store/` named by their SHA-256 hash (e.g., `<sha256>.fit`).
*   **`cache/`**: JSON cache. Track points are extracted lazily.

## 4. Backend Components Workflow
*   **InboxWatcherService**: Polls `inbox/` every 2s for stable files (verifiable via read/write file locks). Computes the SHA-256 hash of the binary file, moves the file to `store/`, and triggers import.
*   **FitImportService**: Parses FIT metadata (duration, speed, distance, ascent) and saves to the `activity` table. Entity IDs are the SHA-256 string.
*   **TrackCacheService**: Triggered when the frontend requests a track. Converts FIT points to an array, saves to `cache/<sha256>.json`, and streams it back. Subsequent calls just stream the JSON file.
*   **TileController**: Proxies Map tile requests (e.g., OpenStreetMap) to avoid browser CORS/privacy warnings and cache them locally.

## 5. Database Schema (Flyway V2)
*   `app_user`: Users (simple app_user management).
*   `gear`: Bikes, shoes, etc., associated with user and activities.
*   `activity`: Stores purely summarized metadata (startTime, distance, movingTimeS). Primary Key is a `VARCHAR(255)` holding the SHA-256 hash.
*   `setting`: Key-value store. Columns are named `setting_key`, `setting_value` because `key` and `value` are inherently reserved keywords in H2 DB syntax!

## 6. Frontend Architecture
*   **Layout**: AppHeader at top, AppActivityBar (sidebar navigation mirroring matros), Splitpanes for resizable List/Map split.
*   **API Client**: Auto-generated types from the backend OpenAPI JSON using `npm.cmd run gen:api`. The client uses `openapi-fetch` exported in `client.ts`.
*   **Views**:
    *   `HomeView`: Main splitpane containing an `ActivityList` (droppable zone wrapper) and an `ActivityMap` (Leaflet wrapper).
    *   `SettingsView`: Manages global settings and Users.

## 7. Implementation Quirks & Environment Constraints
*   **Port Assignments**: Backend runs on `8085` to avoid traditional 8080 conflicts. Vite proxy maps `^/api` to `localhost:8085`.
*   **Vite Ports**: Vite prefers `5173`. When zombie node processes hold `5173`, Vite silently jumps to `5174+`. If the front-end seems "missing", kill zombie `node.exe` processes and enforce `strictPort: true` in Vite.
*   **Code Generation**: Running the API gen script (`npm run gen:api`) pulls from `http://localhost:8085/v3/api-docs` initially or the exported JSON in `spec/openapi.json`. It will completely overwrite `schema.ts`.
*   **Environment**: Built under Windows PowerShell. If `.ps1` blockages appear due to Execution Policies, bypass them by invoking the commands explicitly (e.g., `& "npm.cmd" run ...` or `& "mvn.cmd" ...`).
