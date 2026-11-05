-- Assessment of consequences against the arrival at the target stop, see Docs/consequence-assessment.md.
-- Derived values, not historized, recomputed per route.
ALTER TABLE consequence ADD COLUMN category VARCHAR(16);
ALTER TABLE consequence ADD COLUMN match_kind VARCHAR(16) DEFAULT 'DIRECT' NOT NULL;
-- null: not passable (closure without end or waiting longer than max-wait)
ALTER TABLE consequence ADD COLUMN assessed_delay BIGINT;
ALTER TABLE consequence ADD COLUMN delay_assumed BOOLEAN DEFAULT FALSE NOT NULL;
-- passage of the first affected section, moved by the delays before it
ALTER TABLE consequence ADD COLUMN passage_at TIMESTAMP(6) WITH TIME ZONE;
-- expected arrival at the target stop after this consequence, null if not passable or not planned
ALTER TABLE consequence ADD COLUMN expected_arrival TIMESTAMP(6) WITH TIME ZONE;
-- arrival at the target stop when assessed, null without
ALTER TABLE consequence ADD COLUMN deadline TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE consequence ADD COLUMN assessed_at TIMESTAMP(6) WITH TIME ZONE;

-- Vehicles an incident applies to (enrichment), HEAVY_ONLY does not affect cars
ALTER TABLE incident ADD COLUMN vehicle_scope VARCHAR(16) DEFAULT 'ALL' NOT NULL;
ALTER TABLE incident_aud ADD COLUMN vehicle_scope VARCHAR(16);
