-- Closure whose times are only in the text and could not be read yet, see Docs/traffic-feed.md
ALTER TABLE incident ADD COLUMN times_unconfirmed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE incident_aud ADD COLUMN times_unconfirmed BOOLEAN;
