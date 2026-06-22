<script setup lang="ts">
import { Github, HeartHandshake, Lock, Map as MapIcon, CloudSun, Database, Code2 } from 'lucide-vue-next'
import { useQuery } from '@tanstack/vue-query'
import GoatLogo from '@/components/GoatLogo.vue'
import { getVersion } from '@/api/client'

// Build + DB-schema version, fetched from the backend so bug reports can quote
// the exact app version, Flyway schema version and database.
const { data: version } = useQuery({ queryKey: ['version'], queryFn: getVersion, staleTime: Infinity })

// Open-source building blocks we lean on — credited here, and their licenses
// ship with their packages.
const libs: { name: string; what: string }[] = [
  { name: 'Vue 3 + Vite', what: 'Frontend framework & build' },
  { name: 'Spring Boot', what: 'Backend (Java)' },
  { name: 'MapLibre GL', what: 'Vector/raster map rendering' },
  { name: 'ECharts', what: 'Charts & dashboards' },
  { name: 'H2 + Flyway', what: 'Embedded database & migrations' },
  { name: 'TanStack Query', what: 'Data fetching/cache' },
  { name: 'Lucide', what: 'Icon set' },
  { name: 'Garmin FIT SDK', what: 'FIT file parsing' },
  { name: 'jpx', what: 'GPX file parsing' },
  { name: 'Tailwind CSS', what: 'Styling' },
]

// Map tiles, geocoding and weather — attribution required by their terms.
const data: { name: string; what: string; href: string }[] = [
  { name: 'OpenStreetMap', what: 'Map data © OpenStreetMap contributors', href: 'https://www.openstreetmap.org/copyright' },
  { name: 'CARTO / Esri', what: 'Basemap tile styles', href: 'https://carto.com/attributions' },
  { name: 'OpenFreeMap', what: 'Free vector basemap', href: 'https://openfreemap.org/' },
  { name: 'Open-Meteo', what: 'Historical weather', href: 'https://open-meteo.com/' },
  { name: 'Nominatim', what: 'Reverse geocoding (place names)', href: 'https://nominatim.org/' },
]
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="max-w-3xl mx-auto p-6 space-y-8">

      <!-- Hero -->
      <header class="flex items-center gap-4">
        <div class="w-14 h-14 rounded-2xl bg-primary/10 text-primary flex items-center justify-center shrink-0">
          <GoatLogo :size="34" />
        </div>
        <div>
          <h1 class="text-2xl font-bold leading-tight">TourGaze</h1>
          <p class="text-sm text-muted-fg">Local-first ride viewer — import, browse, tag &amp; replay your tours.</p>
          <span class="inline-block mt-1 text-[10px] font-mono px-1.5 py-0.5 rounded bg-muted/40 border border-border text-muted-fg">v{{ version?.app ?? '1.0.0' }}</span>
          <!-- Diagnostics for bug reports: Flyway schema version + database. -->
          <span v-if="version" class="block mt-1 text-[10px] font-mono text-muted-fg">
            DB schema v{{ version.schemaVersion }}<template v-if="version.schemaDescription"> ({{ version.schemaDescription }})</template> · {{ version.database }}
          </span>
        </div>
      </header>

      <!-- What it is -->
      <section class="space-y-2">
        <p class="text-sm leading-relaxed">
          TourGaze turns the FIT and GPX files from your bike computer or watch into a
          searchable, taggable library of rides — with a cinematic map replay, an
          elevation/heart-rate viewer, per-gear and yearly statistics, and a
          JIRA-style faceted search across everything.
        </p>
      </section>

      <!-- Cards: FOSS + Privacy -->
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <section class="p-4 rounded-lg border border-border bg-muted/10 space-y-2">
          <div class="flex items-center gap-2 text-primary"><HeartHandshake :size="18" /><h2 class="text-sm font-semibold text-foreground">Free &amp; open source</h2></div>
          <p class="text-[13px] text-muted-fg leading-relaxed">
            TourGaze is free, open-source software (FOSS). You can read, run, modify and
            self-host it. Source, issues and the license live on the project repository.
          </p>
          <a href="https://github.com/tourgaze/tourgaze" target="_blank" rel="noopener"
            class="inline-flex items-center gap-1.5 text-[13px] font-medium text-primary hover:underline">
            <Github :size="14" /> View on GitHub
          </a>
        </section>

        <section class="p-4 rounded-lg border border-border bg-muted/10 space-y-2">
          <div class="flex items-center gap-2 text-primary"><Lock :size="18" /><h2 class="text-sm font-semibold text-foreground">Local-first &amp; private</h2></div>
          <p class="text-[13px] text-muted-fg leading-relaxed">
            Everything runs on your machine. Your rides, tracks and tags stay in a local
            database and file store — nothing is uploaded to a cloud service. Map tiles are
            fetched once and cached locally.
          </p>
        </section>
      </div>

      <!-- Built with -->
      <section class="space-y-3">
        <div class="flex items-center gap-2 text-muted-fg"><Code2 :size="15" /><h2 class="text-xs font-semibold uppercase tracking-wide">Built with open source</h2></div>
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-1.5">
          <div v-for="l in libs" :key="l.name" class="flex items-baseline justify-between gap-3 text-[13px] border-b border-border/50 pb-1">
            <span class="font-medium">{{ l.name }}</span>
            <span class="text-muted-fg text-right text-[11px]">{{ l.what }}</span>
          </div>
        </div>
        <!-- Garmin FIT attribution / trademark notice (FIT SDK + sport names). -->
        <p class="text-[11px] text-muted-fg leading-relaxed pt-1">
          FIT files are read with the
          <a href="https://developer.garmin.com/fit/" target="_blank" rel="noopener" class="text-primary hover:underline">Garmin FIT SDK</a>,
          and sport / sub-sport names follow the FIT protocol. FIT, Garmin and the FIT
          logo are trademarks of Garmin International, Inc. or its subsidiaries; the
          FIT SDK is © Garmin Canada Inc. TourGaze is not affiliated with or endorsed
          by Garmin.
        </p>
      </section>

      <!-- Map & data attribution -->
      <section class="space-y-3">
        <div class="flex items-center gap-2 text-muted-fg"><MapIcon :size="15" /><h2 class="text-xs font-semibold uppercase tracking-wide">Maps, geocoding &amp; weather</h2></div>
        <ul class="space-y-1.5">
          <li v-for="d in data" :key="d.name" class="flex items-baseline justify-between gap-3 text-[13px]">
            <a :href="d.href" target="_blank" rel="noopener" class="font-medium text-primary hover:underline">{{ d.name }}</a>
            <span class="text-muted-fg text-right text-[11px]">{{ d.what }}</span>
          </li>
        </ul>
        <p class="text-[11px] text-muted-fg flex items-center gap-1.5 pt-1">
          <CloudSun :size="13" /> Weather is historical reanalysis; geocoding is rate-limited and cached.
        </p>
      </section>

      <!-- Support OpenStreetMap -->
      <section class="rounded-lg border border-border bg-muted/10 p-3 space-y-2">
        <div class="flex items-center gap-2 text-foreground">
          <HeartHandshake :size="15" class="text-rose-500" />
          <h2 class="text-xs font-semibold uppercase tracking-wide">Support OpenStreetMap</h2>
        </div>
        <p class="text-[12px] text-muted-fg leading-relaxed">
          TourGaze runs on OpenStreetMap's free map tiles and Nominatim geocoding —
          kept online by the non-profit OpenStreetMap Foundation, funded entirely by
          donations. TourGaze takes no money for this; if it's useful to you, please
          chip in to keep those services running for everyone.
        </p>
        <a href="https://donate.openstreetmap.org/" target="_blank" rel="noopener"
          class="inline-flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium rounded bg-primary text-primary-fg hover:bg-primary/90">
          <HeartHandshake :size="14" /> Donate to OpenStreetMap
        </a>
      </section>

      <!-- Footer -->
      <footer class="pt-4 border-t border-border text-[11px] text-muted-fg flex items-center gap-2">
        <Database :size="13" />
        <span>TourGaze — your rides, your machine. Built by cyclists, shared as open source.</span>
      </footer>
    </div>
  </div>
</template>
