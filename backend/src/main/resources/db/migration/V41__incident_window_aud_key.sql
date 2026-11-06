-- A window can be removed and added in one revision (Envers numbers the elements of a set by position), so the history
-- needs both rows.
ALTER TABLE incident_window_aud DROP CONSTRAINT incident_window_aud_pkey;
ALTER TABLE incident_window_aud ALTER COLUMN revtype SET NOT NULL;
ALTER TABLE incident_window_aud ADD PRIMARY KEY (rev, incident_id, setordinal, starts_at, ends_at, revtype);
