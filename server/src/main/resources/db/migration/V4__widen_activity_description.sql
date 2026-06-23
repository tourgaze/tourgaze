-- activity.description was varchar(255) — too short for recovered ride notes
-- (MyTourbook titles + weather + body weight). Widen to text. SET DATA TYPE is
-- accepted by both H2 (dev, PostgreSQL mode) and real PostgreSQL.
alter table activity alter column description set data type text;
