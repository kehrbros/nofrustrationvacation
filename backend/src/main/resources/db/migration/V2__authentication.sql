-- AppUser is mapped to the JWT by username, see Docs/authentication.md
ALTER TABLE app_user ADD COLUMN username VARCHAR(255) NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT uk_app_user_username UNIQUE (username);

-- Username of the authenticated user that caused the revision, see Docs/envers.md
ALTER TABLE revinfo ADD COLUMN username VARCHAR(255);
