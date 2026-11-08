-- Reduced copy of the title photo for lists of trips, see Docs/domain.md. Empty for existing photos until the
-- backfill on startup (CoverThumbnailBackfill) and for photos that cannot be decoded (e.g. WebP); then the
-- original is served.
ALTER TABLE trip_cover_image ADD COLUMN thumbnail_content_type VARCHAR(64);
ALTER TABLE trip_cover_image ADD COLUMN thumbnail_content BYTEA;
