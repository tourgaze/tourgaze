-- TourGaze schema baseline (v1). Generated from the JPA entities via
-- Hibernate schema export, so ddl-auto=validate accepts it. Add V2+ forward
-- migrations for any later change — never edit this file.
--
-- One hand-applied addition on top of the raw Hibernate export: explicit
-- ON DELETE actions on every FK (Hibernate exports plain RESTRICT). Without
-- them, deleting a referenced tag/gear/user/activity throws a constraint
-- violation, and ride-scoped markers leak when their activity is deleted.
-- ddl-auto=validate ignores referential actions, so this stays valid.
--   SET NULL — optional reference (rider/gear on a ride, group tag of a
--              preset): clear the pointer on parent delete.
--   CASCADE  — child has no meaning without its parent (tag↔activity links,
--              a marker bound to one ride, and a tag's child tags — deleting a
--              tag drops its whole subtree): remove it on parent delete.
--
-- Also hand-applied (matros conventions): every mutable table carries a
-- `version bigint` (JPA @Version optimistic lock) and an `updated_at` audit
-- column, and `tag` has a unique (parent_id, name) so siblings can't collide.

create table activity (avg_hr integer, avg_speed_kmh float(53), distance_km float(53), duration_s integer, elevation_gain_m float(53), end_country varchar(2), max_hr integer, max_speed_kmh float(53), moving_time_s integer, start_country varchar(2), start_lat float(53), start_lon float(53), weather_humidity_pct integer, weather_temp_c float(53), weather_wind_kph float(53), weight_kg float(53), end_time timestamp(6) with time zone, imported_at timestamp(6) with time zone not null, start_time timestamp(6) with time zone, weather_fetched_at timestamp(6) with time zone, source_format varchar(20) not null, gear_id varchar(26), id varchar(26) not null, user_id varchar(26), source_hash varchar(64) not null unique, weather_condition varchar(100), original_filename varchar(500), source_filename varchar(500) not null, activity_type varchar(255), description varchar(255), end_location varchar(255), name varchar(255), route_geocells clob, start_location varchar(255), version bigint, updated_at timestamp(6) with time zone, primary key (id));
create table activity_tag (activity_id varchar(26) not null, tag_id varchar(26) not null, primary key (activity_id, tag_id));
create table app_user (date_of_birth date, height_cm integer, max_hr integer, resting_hr integer, weight_kg float(53), created_at timestamp(6) with time zone not null, gender varchar(20), id varchar(26) not null, display_name varchar(255), username varchar(255) not null unique, version bigint, updated_at timestamp(6) with time zone, primary key (id));
create table filter_preset (created_at timestamp(6) with time zone not null, group_tag_id varchar(26), id varchar(26) not null, group_by varchar(40), name varchar(120) not null, query varchar(500), version bigint, updated_at timestamp(6) with time zone, primary key (id));
create table gear (created_at timestamp(6) with time zone not null, retired_at timestamp(6) with time zone, id varchar(26) not null, user_id varchar(26), description varchar(255), name varchar(255) not null, type varchar(255), version bigint, updated_at timestamp(6) with time zone, primary key (id));
create table map_provider (is_dark boolean not null, max_zoom integer, created_at timestamp(6) with time zone not null, type varchar(20) not null, id varchar(26) not null, style_url varchar(1000), url_template varchar(1000), attribution varchar(2000), description varchar(255), name varchar(255) not null, version bigint, updated_at timestamp(6) with time zone, primary key (id));
create table marker (lat float(53) not null, lon float(53) not null, created_at timestamp(6) with time zone not null, activity_id varchar(26), id varchar(26) not null, category varchar(64) not null, description clob, label varchar(255) not null, version bigint, updated_at timestamp(6) with time zone, primary key (id));
create table setting (setting_key varchar(255) not null, setting_value varchar(255), primary key (setting_key));
create table tag (created_at timestamp(6) with time zone not null, color varchar(20), id varchar(26) not null, parent_id varchar(26), icon varchar(60), name varchar(120) not null, version bigint, updated_at timestamp(6) with time zone, primary key (id), constraint UQ_TAG_PARENT_NAME unique (parent_id, name));
create index idx_activity_start on activity (start_time desc);
create index idx_activity_source_hash on activity (source_hash);
create index idx_activity_user on activity (user_id);
create index idx_activity_gear on activity (gear_id);
create index idx_activity_tag_activity on activity_tag (activity_id);
create index idx_activity_tag_tag on activity_tag (tag_id);
create index idx_gear_user on gear (user_id);
create index idx_marker_activity on marker (activity_id);
create index idx_tag_parent on tag (parent_id);
-- Readable, stable constraint names (matros convention) — Hibernate validate
-- ignores FK names, so naming them is free and far easier to reason about than
-- the generated FK<hash> identifiers.
alter table if exists activity add constraint FK_ACTIVITY_GEAR foreign key (gear_id) references gear on delete set null;
alter table if exists activity add constraint FK_ACTIVITY_USER foreign key (user_id) references app_user on delete set null;
alter table if exists activity_tag add constraint FK_ACTIVITY_TAG_TAG foreign key (tag_id) references tag on delete cascade;
alter table if exists activity_tag add constraint FK_ACTIVITY_TAG_ACTIVITY foreign key (activity_id) references activity on delete cascade;
alter table if exists gear add constraint FK_GEAR_USER foreign key (user_id) references app_user on delete set null;
-- Self-FK: deleting a tag deletes its child tags too (recursive subtree drop),
-- matching the cascade semantics the tag editor warns the user about.
alter table if exists tag add constraint FK_TAG_PARENT foreign key (parent_id) references tag on delete cascade;
-- Soft references in the entities (plain String columns, not @ManyToOne) get a
-- real FK here so the DB enforces them: marker→ride (drop ride markers with the
-- ride), preset→group tag (clear the grouping when that tag is deleted).
alter table if exists marker add constraint FK_MARKER_ACTIVITY foreign key (activity_id) references activity on delete cascade;
alter table if exists filter_preset add constraint FK_FILTER_PRESET_GROUP_TAG foreign key (group_tag_id) references tag on delete set null;

-- Cached OSM peaks/passes for auto-detected ride highlights, plus the geohash
-- cells already fetched from Overpass (so we never re-query a region). Pure
-- reference cache — no FKs, no version/updated_at (not user-editable).
create table geo_feature (osm_id bigint not null, lat float(53) not null, lon float(53) not null, ele_m float(53), fetched_at timestamp(6) with time zone not null, type varchar(8) not null, geocell varchar(12) not null, wikidata varchar(32), name varchar(200), name_en varchar(200), name_de varchar(200), primary key (osm_id));
create table geo_region (feature_count integer not null, fetched_at timestamp(6) with time zone not null, geocell varchar(12) not null, primary key (geocell));
create index idx_geo_feature_cell on geo_feature (geocell);
create index idx_geo_feature_type on geo_feature (type);
