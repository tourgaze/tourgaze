-- Default sport / activity-type list, seeded as data so it's versioned and
-- reproducible (replaces the former Java SportSeeder). Each install curates from
-- here: add your sports, hide the rest.
--
-- Keys are Garmin/FIT canonical sport + sub_sport names (cycling, road, mountain,
-- gravel_cycling, e_biking, running, trail, walking, hiking, swimming, …) so we
-- don't diverge from what devices report and a future FIT sub-sport mapping is a
-- direct key match. No redundant aliases. Keys the pace detector emits
-- (cycling/running/hiking) are present.
--
-- id = key (stable, readable, unique, ≤26 chars); version=0 for the JPA optimistic
-- lock; created_at stamped now.

-- Indexes (inline). The PK (id / ULID) and the unique sport_key are already
-- indexed by their constraints in V1; this composite serves the hot lookup —
-- enabled sports in display order (GET /api/sports?enabledOnly=true).
create index idx_sport_enabled_ordinal on sport (enabled, ordinal);

-- Family-level sports only. Cycling disciplines (road / MTB / gravel / e-bike)
-- are NOT separate sports — that distinction is carried by GEAR (which bike), so
-- `sport:cycling` matches every ride and `gear:` narrows to a discipline. The
-- finer Garmin sub-sport names are still offered as hints (sport_proposals.json)
-- if an install wants them.
-- builtin=true: seeded defaults are protected from deletion (the importer maps
-- onto these keys). They can still be renamed / re-iconed / hidden.
insert into sport (id, sport_key, name, icon, color, ordinal, enabled, builtin, created_at, version) values
  ('cycling',        'cycling',        'Cycling',        'Bike',       '#10b981', 0, true, true, current_timestamp, 0),
  ('running',        'running',        'Running',        'Footprints', '#ef4444', 1, true, true, current_timestamp, 0),
  ('walking',        'walking',        'Walking',        'Footprints', '#84cc16', 2, true, true, current_timestamp, 0),
  ('hiking',         'hiking',         'Hiking',         'Mountain',   '#7c3aed', 3, true, true, current_timestamp, 0),
  ('swimming',       'swimming',       'Swimming',       'Waves',      '#06b6d4', 4, true, true, current_timestamp, 0),
  ('inline_skating', 'inline_skating', 'Inline skating', 'Disc3',      '#d946ef', 5, true, true, current_timestamp, 0),
  ('generic',        'generic',        'Generic',        'Activity',   '#64748b', 6, true, true, current_timestamp, 0);
