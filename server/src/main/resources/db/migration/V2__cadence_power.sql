-- Cadence (rpm) and power (watts) ride metrics, parsed from FIT Session data and
-- GPX/KML per-point extensions. Additive, nullable — existing rides keep null.
alter table activity add column avg_cadence integer;
alter table activity add column max_cadence integer;
alter table activity add column avg_power_w integer;
alter table activity add column max_power_w integer;
