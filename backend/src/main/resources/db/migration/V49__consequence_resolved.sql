-- Consequence whose incident has ended: kept as "resolved" until the traveller removes it from the map, see
-- Docs/domain.md#incidents
ALTER TABLE consequence ADD COLUMN resolved_at TIMESTAMP(6) WITH TIME ZONE;
CREATE INDEX ix_consequence_unresolved ON consequence (incident_id) WHERE resolved_at IS NULL;
