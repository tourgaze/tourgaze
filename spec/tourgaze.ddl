-- TourGaze schema (reference) — mirrors the canonical
-- server/src/main/resources/db/migration/V1__baseline.sql. Regenerate this
-- whenever the baseline changes; it is documentation only and not executed.


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
alter table if exists activity add constraint FK_ACTIVITY_GEAR foreign key (gear_id) references gear on delete set null;
alter table if exists activity add constraint FK_ACTIVITY_USER foreign key (user_id) references app_user on delete set null;
alter table if exists activity_tag add constraint FK_ACTIVITY_TAG_TAG foreign key (tag_id) references tag on delete cascade;
alter table if exists activity_tag add constraint FK_ACTIVITY_TAG_ACTIVITY foreign key (activity_id) references activity on delete cascade;
alter table if exists gear add constraint FK_GEAR_USER foreign key (user_id) references app_user on delete set null;
alter table if exists tag add constraint FK_TAG_PARENT foreign key (parent_id) references tag on delete cascade;
alter table if exists marker add constraint FK_MARKER_ACTIVITY foreign key (activity_id) references activity on delete cascade;
alter table if exists filter_preset add constraint FK_FILTER_PRESET_GROUP_TAG foreign key (group_tag_id) references tag on delete set null;
