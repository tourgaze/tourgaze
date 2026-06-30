-- Test-only "future" migration. Applied to the DB so the schema history carries
-- a version higher than anything in the production classpath (db/migration tops
-- out far below 9999), which SchemaVersionGuardTest then sees as a FUTURE
-- migration — i.e. a database written by a newer app than the one booting.
create table guard_test_future_probe (id integer primary key);
