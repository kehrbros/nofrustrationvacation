-- The answer of the model depends on the validity as well as the text (windows are dated), see
-- Docs/traffic-feed.md#anreicherung-messageenrichment. Answers stored without it are asked again.
ALTER TABLE message_enrichment ADD COLUMN validity VARCHAR(100) DEFAULT '' NOT NULL;
ALTER TABLE message_enrichment DROP CONSTRAINT uk_message_enrichment;
ALTER TABLE message_enrichment ADD CONSTRAINT uk_message_enrichment
    UNIQUE (source_id, subject_id, message_hash, validity);
