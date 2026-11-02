-- Point of the accommodation for spatial queries (e.g. incidents near it), derived from latitude/longitude by the
-- database. Not mapped in JPA and not audited, see Docs/architecture.md#persistence.
ALTER TABLE booking_accommodation ADD COLUMN location geometry(Point, 4326)
    GENERATED ALWAYS AS (CASE WHEN longitude IS NOT NULL THEN ST_SetSRID(ST_MakePoint(longitude, latitude), 4326) END) STORED;
CREATE INDEX ix_booking_accommodation_location ON booking_accommodation USING GIST (location);
