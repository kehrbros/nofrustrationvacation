-- Trip period and offsets of user-entered points in time, see Docs/domain.md.
-- PostgreSQL stores TIMESTAMP WITH TIME ZONE as instant only, Hibernate keeps the offset in a _tz column
-- (@TimeZoneStorage(COLUMN), offset in seconds).

ALTER TABLE trip ADD COLUMN start_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE trip ADD COLUMN start_at_tz INTEGER;
ALTER TABLE trip ADD COLUMN end_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE trip ADD COLUMN end_at_tz INTEGER;

-- Existing trips have no period yet: the moment of creation, in UTC
UPDATE trip SET start_at = created_at, start_at_tz = 0, end_at = created_at, end_at_tz = 0;

ALTER TABLE trip ALTER COLUMN start_at SET NOT NULL;
ALTER TABLE trip ALTER COLUMN start_at_tz SET NOT NULL;
ALTER TABLE trip ALTER COLUMN end_at SET NOT NULL;
ALTER TABLE trip ALTER COLUMN end_at_tz SET NOT NULL;
ALTER TABLE trip ADD CONSTRAINT ck_trip_period CHECK (end_at >= start_at);
CREATE INDEX ix_trip_app_user_start ON trip (app_user_id, start_at);

ALTER TABLE trip_aud ADD COLUMN start_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE trip_aud ADD COLUMN start_at_tz INTEGER;
ALTER TABLE trip_aud ADD COLUMN end_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE trip_aud ADD COLUMN end_at_tz INTEGER;

-- Existing stop times keep their instant, offset UTC
ALTER TABLE stop ADD COLUMN arrival_at_tz INTEGER;
ALTER TABLE stop ADD COLUMN departure_at_tz INTEGER;
UPDATE stop SET arrival_at_tz = 0 WHERE arrival_at IS NOT NULL;
UPDATE stop SET departure_at_tz = 0 WHERE departure_at IS NOT NULL;

ALTER TABLE stop_aud ADD COLUMN arrival_at_tz INTEGER;
ALTER TABLE stop_aud ADD COLUMN departure_at_tz INTEGER;
