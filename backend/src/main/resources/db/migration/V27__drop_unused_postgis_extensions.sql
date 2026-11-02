-- The postgis/postgis image creates TIGER geocoder (US address data) and topology in the database on first start,
-- only postgis itself is used, see Docs/architecture.md#persistence. Without CASCADE: fails if anything uses them.
DROP EXTENSION IF EXISTS postgis_tiger_geocoder;
DROP EXTENSION IF EXISTS postgis_topology;
DROP EXTENSION IF EXISTS fuzzystrmatch;
DROP SCHEMA IF EXISTS tiger_data;
DROP SCHEMA IF EXISTS tiger;
DROP SCHEMA IF EXISTS topology;
