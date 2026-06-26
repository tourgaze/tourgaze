# Inbox model

**The whole thing in two rules:**

1. Hash a file. **If the hash is already in the repository → skip it** (nothing to do).
2. **If not → copy it into `inbox/`**, where you review it and **Import** it. Import is
   the only thing that puts it into the **repository** — the cloud-syncable, backed-up
   library. The inbox itself is *not* safe; only the repository is.

Two ways a file reaches the inbox (**upload** — universal; **watch-folder** — optional,
for server-accessible folders like a Garmin USB or a synced Google-Drive folder). Two
things you can do with a staged file: **Import** (→ repository) or **Delete from inbox**
(→ parked, won't re-stage). "Exists" always means the exact content hash.

## Diagram

```
                            -- TWO PRODUCERS --
  +--------------------------+          +----------------------------------+
  | UPLOAD  (universal)      |          | WATCH-FOLDER  (optional, local)  |
  | browser drag-drop        |          | WatchFolderScanService           |
  | POST /api/inbox          |          | @Scheduled every 60s (or Refresh)|
  +------------+-------------+          +-----------------+----------------+
               | write bytes                             | per file: sha = SHA-256
               |                                         v
               |                    +------------------------------------------+
               |                    | copyIfNew():                              |
               |                    |   hash in inbox-ignored/ (parked) -> skip |
               |                    |   hash already in inbox/          -> skip |
               |                    |   hash in repo (findBySourceHash) -> record SKIPPED
               |                    |   else NEW -> copy into inbox/            |  (Ignored
               |                    |      if delete-from-device:               |   filter)
               |                    |         recordSource(devicePath)          |
               |                    +-----------------+------------------------+
               +-------------+--------------------+ copy
                             v
                    ====================   the SINGLE staging buffer
                    ||  inbox/  (dataDir) ||  (NOT backed up — staging only)
                    ====================
          +-----------------+----------------------------+
          | warmPending() @~10s | every change: notifyChanged()
          |  P1 PARSE   -> parseCache (dist/type/gear,    |          SSE
          |     existingActivityId, route-dup)            v       inbox-changed
          |  P2 GEOCODE -> predictionCache       [ InboxStreamSvc ] -----> client refetch
          +-----------------+----------------------------+
            GET /api/inbox (listPending): instant dir scan
                            |   cached -> assemble()  |  uncached -> skeleton
                            v
  +-------------------------------------------------------------------+
  | ITEM STATE  (from fields, no separate status)                      |
  |   parsing    skeleton, warm job hasn't parsed it yet               |
  |   ready      importable  (+ "similar route" hint when              |
  |              duplicateOfName set -- different bytes, same route)   |
  |   duplicate  existingActivityId set  =  EXACT hash already in repo |
  +-------------+--------------------------------------+--------------+
                | Import (POST /{f}/import)            | Delete from inbox (DELETE /{f})
                v                                      v
  +------------------------------------+   +------------------------------+
  | if hash in repo -> return existing,|   | moveOutOfInbox -> inbox-     |
  |                    drop inbox copy  |   | ignored/  hash now PARKED -> |
  | else -> moveIntoStore + Activity    |   | won't re-stage from a kept   |
  | then deleteRecordedSource()         |   | device                       |
  |   (delete device original ONLY      |   +------------------------------+
  |    after the store/ write succeeds) |
  +----------------+-------------------+
                   v
        ========================         ==============================
        || store/ (repositoryDir)|------>|| TOUR LIST GET /api/activities
        || the library, backed up|        ==============================
        ========================

  -- ON DISK --                              -- TRANSPARENCY --
   repositoryDir/store/    imported           GET /api/inbox/skipped
   dataDir/inbox/          staging              -> "Already imported - N" filter: files
   dataDir/inbox-ignored/  parked (ONE folder)     the scan ignored as already in the
   cache/inbox-origins.json   filename -> source   library, each linking to its ride.
   cache/inbox-sources.json   filename -> path      (a one-time migration folded the old
                              (delete-mode only)     inbox-processed/ into inbox-ignored/)
```

## Sources

Configured in Settings -> Inbox as a JSON list (the `inbox.sources` setting), each
`{label, path, keepOriginal}`. Multiple sources are fine — e.g. a Garmin USB and a synced
Google-Drive folder, both `keepOriginal: true`. Paths with spaces/parentheses are fine.

## Safety invariant

A file is never single-homed in an un-backed-up place. A watch-folder original stays on the
device until import; an upload lives in the persistent inbox until import; the device delete
(delete-from-device sources only) happens **after** the `store/` copy succeeds. Imported =
in `store/` (the backed-up repository).

## Phase 2 (Docker / remote)

The server can't see a user's USB. A small **watchdog client** on the user's machine watches
the local folder and uploads new files (a cheap `exists?hash` pre-check skips ones already in
the repo). The server inbox is unchanged — upload is already the universal path.
