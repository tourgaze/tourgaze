# TourGaze vs the field

Where TourGaze sits in the GPS-ride landscape, honestly — what it's deliberately
good at, and what it intentionally is **not**.

## One-line positioning

> **A local-first, open-source ride *viewer* and *re-liver* — own your FIT/GPX
> files, browse and relive them with cinematic replay + ghost-chase compare, on
> your own machine. No cloud, no subscription, no social feed.**

It is *not* a route planner, a training-load platform, or a social network — the
incumbents own those, and TourGaze is happy to be complementary.

## The field

| | **TourGaze** | Strava | Komoot | Garmin Connect | RideWithGPS | intervals.icu | GoldenCheetah |
|---|---|---|---|---|---|---|---|
| Model | **Local / self-host** | Cloud SaaS | Cloud SaaS | Cloud (Garmin) | Cloud SaaS | Cloud (free) | Local desktop |
| Open source | **✅ AGPL-3.0** | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ GPL |
| Price | **Free** | Freemium £/mo | Freemium | Free | Freemium | Free/donate | Free |
| Your data stays yours | **✅ on your disk** | ❌ cloud | ❌ | ❌ | ❌ | ❌ | ✅ |
| Optional encryption at rest | **🔜 planned** | n/a | n/a | n/a | n/a | n/a | ❌ |
| Imports FIT/GPX/TCX/KMZ | **✅ incl. KMZ photos** | ✅ | partial | ✅ | ✅ | ✅ | ✅ |
| Cinematic replay | **✅ camera strategies, smooth** | Flyby (cloud) | 3D flythrough | basic | basic | ❌ | ❌ |
| Ghost-chase compare | **✅ in-app race, gaps/HR** | Segment compare only | ❌ | ❌ | ❌ | ❌ | ❌ |
| Auto pass/peak highlights | **✅ OSM, lazy-cached** | partial (segments) | ✅ (planning) | ❌ | partial | ❌ | ❌ |
| Map hillshade / terrain | **✅** | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| Route planning / nav | ❌ (viewer only) | ✅ | **✅ core** | ✅ | **✅ core** | ❌ | ❌ |
| Segments / leaderboards | ❌ | **✅ moat** | ❌ | ✅ | partial | ❌ | ❌ |
| Social feed / kudos | ❌ | **✅ moat** | partial | ✅ | partial | ❌ | ❌ |
| Training load / fitness | ❌ (basic stats only) | ✅ (premium) | ❌ | ✅ | ❌ | **✅ core** | **✅ core** |
| Mobile app | ❌ (web/desktop) | **✅** | **✅** | **✅** | ✅ | ✅ (web) | ❌ |

## Where TourGaze wins

- **Privacy / ownership.** Everything lives under `~/.tourgaze/` on your machine
  — no account, no upload, no terms-of-service rug-pull. The only thing that
  closely matches this is GoldenCheetah; everything else is cloud.
- **Re-living a ride.** The replay (smooth sub-point camera, drone/follow/
  top-down/helicopter strategies, deadzone anti-jitter), the **ghost-chase race**
  (line up several rides, watch who leads by how many metres + HR, off-screen
  Mario-Kart arrows), and **auto-detected pass/peak highlights** from OSM are a
  genuinely differentiated "watch your ride back" experience. Strava's Flyby is
  the nearest cousin but it's cloud + social.
- **Self-hostable in one container.** `docker compose up` or a Helm chart; the
  SPA + API are one jar. Friendly to homelab / NAS users.
- **Open source + no subscription.** Read it, run it, change it.
- **Local-first ingestion that fits real life.** Watch-folder import (incl.
  Google-Drive-synced OpenTracks KMZ with embedded photos), SHA-dedup, copy-
  never-move.

## Where TourGaze does not compete (by design)

- **Route planning & turn-by-turn navigation** — Komoot and RideWithGPS own
  this; TourGaze views rides you already rode.
- **Segments, leaderboards, kudos, clubs** — Strava's social moat. Out of scope
  for a single-user local app (consciously dropped with auth in v1).
- **Training-load / performance modelling** — intervals.icu and GoldenCheetah
  (and Strava/TrainingPeaks premium) are deep here; TourGaze shows per-ride
  stats + HR zones, not CTL/ATL/FTP modelling.
- **Mobile app** — it's a web/desktop app; phones aren't the target surface.

## Who it's for

A privacy-minded cyclist/hiker who records on a Garmin, watch, or OpenTracks,
wants to **own and relive** their rides locally — and optionally compare them —
without feeding a cloud platform. It pairs fine *alongside* Strava (keep the
social feed there) while being the private, beautiful home for your actual files.

## Natural next moves to widen the moat

(From the backlog, in rough priority for differentiation, not parity-chasing:)

- **Hausrunde auto-detection** + auto-tagging — "you've ridden this 7 times"
  clustering is something the cloud apps under-serve for *your private* routes.
- **LLM ride descriptions / cinematic "Hollywood" replay** — leans further into
  the re-living angle nobody else owns.
- **Long-segment (Strava-style) overlap compare** — the one place borrowing a
  Strava idea clearly helps the ghost-chase.
- **Encryption at rest** — turns "local-first" into "safely syncable", a real
  edge over both cloud apps and GoldenCheetah.

Deliberately *not* chasing: a social network, a route planner, or a mobile app —
those are the incumbents' moats, and competing there dilutes the local-first,
re-live-your-ride identity that makes TourGaze worth using.
