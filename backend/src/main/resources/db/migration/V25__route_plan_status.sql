-- Outcome of the last planning of a route, shown to the user (e.g. departure missing), see Docs/domain.md
ALTER TABLE route ADD COLUMN plan_outcome VARCHAR(30);
ALTER TABLE route ADD COLUMN plan_reason VARCHAR(500);
UPDATE route SET plan_outcome = 'PLANNED' WHERE planned_at IS NOT NULL;