-- Granary initial schema. Written to run unchanged on PostgreSQL and on H2 in PostgreSQL mode.

CREATE TABLE repositories (
    id                       UUID         NOT NULL PRIMARY KEY,
    name                     VARCHAR(100) NOT NULL,
    format                   VARCHAR(32)  NOT NULL,
    type                     VARCHAR(16)  NOT NULL,
    online                   BOOLEAN      NOT NULL DEFAULT TRUE,
    blob_store               VARCHAR(64)  NOT NULL DEFAULT 'default',
    description              VARCHAR(500),
    allow_redeploy           BOOLEAN      NOT NULL DEFAULT FALSE,
    version_policy           VARCHAR(16)  NOT NULL DEFAULT 'MIXED',
    remote_url               VARCHAR(1000),
    remote_username          VARCHAR(200),
    remote_password          VARCHAR(500),
    remote_token             VARCHAR(1000),
    connect_timeout_seconds  INTEGER,
    read_timeout_seconds     INTEGER,
    metadata_max_age_minutes INTEGER,
    negative_cache_minutes   INTEGER,
    offline_mode             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_repository_name UNIQUE (name)
);

CREATE TABLE repository_members (
    repository_id UUID         NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    position      INTEGER      NOT NULL,
    member_name   VARCHAR(100) NOT NULL,
    PRIMARY KEY (repository_id, position)
);

CREATE TABLE components (
    id            UUID         NOT NULL PRIMARY KEY,
    repository_id UUID         NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    format        VARCHAR(32)  NOT NULL,
    namespace     VARCHAR(500) NOT NULL DEFAULT '',
    name          VARCHAR(500) NOT NULL,
    version       VARCHAR(200) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_component_coordinates UNIQUE (repository_id, namespace, name, version)
);

CREATE INDEX ix_components_name ON components (name);

CREATE TABLE assets (
    id                     UUID          NOT NULL PRIMARY KEY,
    repository_id          UUID          NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    component_id           UUID          REFERENCES components (id) ON DELETE SET NULL,
    path                   VARCHAR(2000) NOT NULL,
    blob_sha256            VARCHAR(64)   NOT NULL,
    size                   BIGINT        NOT NULL,
    content_type           VARCHAR(200),
    origin                 VARCHAR(16)   NOT NULL,
    metadata               BOOLEAN       NOT NULL DEFAULT FALSE,
    upstream_etag          VARCHAR(500),
    upstream_last_modified VARCHAR(100),
    last_verified_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    last_downloaded_at     TIMESTAMP WITH TIME ZONE,
    download_count         BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_asset_path UNIQUE (repository_id, path)
);

CREATE INDEX ix_assets_component ON assets (component_id);
CREATE INDEX ix_assets_blob ON assets (blob_sha256);

CREATE TABLE roles (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    builtin     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_role_name UNIQUE (name)
);

CREATE TABLE role_privileges (
    role_id   UUID         NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    privilege VARCHAR(200) NOT NULL,
    PRIMARY KEY (role_id, privilege)
);

CREATE TABLE users (
    id            UUID         NOT NULL PRIMARY KEY,
    username      VARCHAR(100) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    display_name  VARCHAR(200),
    email         VARCHAR(320),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    builtin       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_user_username UNIQUE (username)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE api_tokens (
    id           UUID         NOT NULL PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(200) NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,
    token_prefix VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_api_token_hash UNIQUE (token_hash)
);

CREATE TABLE settings (
    setting_key   VARCHAR(100)  NOT NULL PRIMARY KEY,
    setting_value VARCHAR(2000)
);
