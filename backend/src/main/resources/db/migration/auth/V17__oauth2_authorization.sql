-- Persistent authorizations, consents and cached client metadata documents of the own identity provider
-- (package com.vacantagent.auth), see Docs/authentication.md. Schema of Spring Authorization Server's JDBC services
-- with VARCHAR instead of BLOB and timestamps with time zone, so it runs on H2 and PostgreSQL.
-- No foreign keys to tables of other modules: the auth module can be replaced by an external IdP.
CREATE TABLE oauth2_authorization (
    id                            VARCHAR(100) NOT NULL,
    registered_client_id          VARCHAR(100) NOT NULL,
    principal_name                VARCHAR(200) NOT NULL,
    authorization_grant_type      VARCHAR(100) NOT NULL,
    authorized_scopes             VARCHAR(1000),
    attributes                    VARCHAR,
    state                         VARCHAR(500),
    authorization_code_value      VARCHAR,
    authorization_code_issued_at  TIMESTAMP(6) WITH TIME ZONE,
    authorization_code_expires_at TIMESTAMP(6) WITH TIME ZONE,
    authorization_code_metadata   VARCHAR,
    access_token_value            VARCHAR,
    access_token_issued_at        TIMESTAMP(6) WITH TIME ZONE,
    access_token_expires_at       TIMESTAMP(6) WITH TIME ZONE,
    access_token_metadata         VARCHAR,
    access_token_type             VARCHAR(100),
    access_token_scopes           VARCHAR(1000),
    oidc_id_token_value           VARCHAR,
    oidc_id_token_issued_at       TIMESTAMP(6) WITH TIME ZONE,
    oidc_id_token_expires_at      TIMESTAMP(6) WITH TIME ZONE,
    oidc_id_token_metadata        VARCHAR,
    refresh_token_value           VARCHAR,
    refresh_token_issued_at       TIMESTAMP(6) WITH TIME ZONE,
    refresh_token_expires_at      TIMESTAMP(6) WITH TIME ZONE,
    refresh_token_metadata        VARCHAR,
    user_code_value               VARCHAR,
    user_code_issued_at           TIMESTAMP(6) WITH TIME ZONE,
    user_code_expires_at          TIMESTAMP(6) WITH TIME ZONE,
    user_code_metadata            VARCHAR,
    device_code_value             VARCHAR,
    device_code_issued_at         TIMESTAMP(6) WITH TIME ZONE,
    device_code_expires_at        TIMESTAMP(6) WITH TIME ZONE,
    device_code_metadata          VARCHAR,
    PRIMARY KEY (id)
);

CREATE INDEX ix_oauth2_authorization_principal ON oauth2_authorization (principal_name, registered_client_id);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id VARCHAR(100)  NOT NULL,
    principal_name       VARCHAR(200)  NOT NULL,
    authorities          VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

-- Client ID Metadata Documents (CIMD) of MCP clients. client_id is the URL of the document,
-- registered_client_id a stable hash of it (id of the RegisteredClient in the tables above).
CREATE TABLE auth_cimd_client (
    registered_client_id VARCHAR(100)  NOT NULL,
    client_id            VARCHAR(2048) NOT NULL,
    metadata_json        VARCHAR       NOT NULL,
    fetched_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (registered_client_id)
);
