/// <reference types="vite/client" />
// maplibre-gl 6 is ESM-only and loads its web worker from a URL it computes at
// runtime next to its own module file. No bundler can follow that: a Vite build
// doesn't emit the worker, and the dev server's pre-bundled copy has none beside
// it — so the worker 404s and every GeoJSON layer (track line, start/end pins,
// trail) silently never draws, while raster tiles still render. Have Vite bundle
// the worker itself and hand maplibre that URL before any map is created.
import { setWorkerUrl } from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'

setWorkerUrl(workerUrl)
