-- Delay a source assumes without giving a figure (e.g. NZTA "Delays": 15 min), see Docs/traffic-feed.md#verzögerung-ohne-angabe
ALTER TABLE incident ADD COLUMN assumed_delay BIGINT;
ALTER TABLE incident_aud ADD COLUMN assumed_delay BIGINT;
