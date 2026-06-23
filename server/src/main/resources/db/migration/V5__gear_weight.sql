-- Gear weight (kg) — added to the rider's body weight for the system mass used
-- by the cycling-power estimate. Nullable (unknown for old gear).
alter table gear add column weight_kg float(53);
