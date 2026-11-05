-- Time windows of an incident (e.g. closed at night only), see Docs/traffic-feed.md#zeitfenster.
-- No windows: valid the whole time from valid_from to valid_to, which stays the envelope for queries.
CREATE TABLE incident_window (
    incident_id BIGINT                      NOT NULL,
    starts_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    ends_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (incident_id, starts_at, ends_at),
    CONSTRAINT fk_incident_window_incident FOREIGN KEY (incident_id) REFERENCES incident (id)
);

-- Envers numbers the elements of a set of embeddables (setordinal); a revision may remove and add windows with the
-- same ordinal, so the times are part of the key
CREATE TABLE incident_window_aud (
    rev         BIGINT                      NOT NULL,
    revtype     SMALLINT,
    incident_id BIGINT                      NOT NULL,
    setordinal  INTEGER                     NOT NULL,
    starts_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    ends_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (rev, incident_id, setordinal, starts_at, ends_at),
    CONSTRAINT fk_incident_window_aud_revinfo FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- First window of the incident the passage falls into, for display ("closed 21:00–04:30")
ALTER TABLE consequence ADD COLUMN window_start TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE consequence ADD COLUMN window_end TIMESTAMP(6) WITH TIME ZONE;
