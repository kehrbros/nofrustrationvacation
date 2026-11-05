-- The impact follows from the type of the incident (Incident.impact()), no need to store a copy per consequence
ALTER TABLE consequence DROP COLUMN impact;
