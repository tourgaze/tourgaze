-- Default ride-event types, seeded as data (versioned + reproducible), mirroring
-- V2__default_sports. Each install curates from here: add your own kinds, hide
-- the rest. Keys are UPPER_SNAKE and match the wire value stored on a ride event
-- (RideEvent.type). WEATHER_RAIN is what the importer emits when it detects a
-- shower; the others are handy user-driven kinds.
--
-- id = key (stable, readable, unique, <=26 chars); version=0 for the JPA
-- optimistic lock; created_at stamped now.

-- Composite index for the hot lookup (enabled types in display order). The PK
-- (id) and the unique event_key are already indexed by their constraints in V1.
create index idx_event_type_enabled_ordinal on event_type (enabled, ordinal);

-- WEATHER_RAIN is builtin=true: the importer emits it, so it can't be deleted.
insert into event_type (id, event_key, name, icon, color, ordinal, enabled, builtin, created_at, version) values
  ('WEATHER_RAIN', 'WEATHER_RAIN', 'Rainfall',    'CloudRain',  '#3b82f6', 0, true, true,  current_timestamp, 0),
  ('DRINK_BREAK',  'DRINK_BREAK',  'Drink break', 'CupSoda',    '#f59e0b', 1, true, false, current_timestamp, 0),
  ('FOOD',         'FOOD',         'Food stop',   'Utensils',   '#84cc16', 2, true, false, current_timestamp, 0),
  ('PUNCTURE',     'PUNCTURE',     'Puncture',    'Disc',       '#ef4444', 3, true, false, current_timestamp, 0),
  ('MECHANICAL',   'MECHANICAL',   'Mechanical',  'Wrench',     '#6b7280', 4, true, false, current_timestamp, 0),
  ('VIEWPOINT',    'VIEWPOINT',    'Viewpoint',   'Binoculars', '#06b6d4', 5, true, false, current_timestamp, 0),
  ('PHOTO',        'PHOTO',        'Photo',       'Camera',     '#a855f7', 6, true, false, current_timestamp, 0),
  ('REST',         'REST',         'Rest',        'TreePalm',   '#14b8a6', 7, true, false, current_timestamp, 0);
