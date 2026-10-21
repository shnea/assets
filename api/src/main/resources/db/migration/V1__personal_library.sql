CREATE TABLE settings (
    id integer PRIMARY KEY CHECK (id = 1),
    default_quota bigint NOT NULL CHECK (default_quota >= 0)
);
INSERT INTO settings VALUES (1, 10000000000);

CREATE TABLE members (
    id uuid PRIMARY KEY,
    issuer text NOT NULL,
    subject text NOT NULL,
    display_name varchar(160) NOT NULL,
    base_quota bigint NOT NULL CHECK (base_quota >= 0),
    quota_override bigint CHECK (quota_override >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (issuer, subject)
);
CREATE TABLE assets (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES members(id),
    draft jsonb NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    version integer NOT NULL DEFAULT 0,
    favorite boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id)
);
CREATE INDEX assets_owner_updated ON assets(owner_id, updated_at DESC, id);
CREATE TABLE asset_versions (
    asset_id uuid NOT NULL REFERENCES assets(id),
    number integer NOT NULL,
    content jsonb NOT NULL,
    change_note varchar(500) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_id, number)
);
CREATE TABLE ideas (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES members(id),
    title varchar(200) NOT NULL DEFAULT '',
    body text NOT NULL DEFAULT '',
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id)
);
CREATE INDEX ideas_owner_updated ON ideas(owner_id, updated_at DESC, id);
CREATE TABLE files (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES members(id),
    asset_id uuid,
    idea_id uuid,
    name varchar(255) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    sha256 varchar(64) NOT NULL,
    platform_upload_id uuid,
    platform_file_id uuid UNIQUE,
    received_bytes bigint NOT NULL DEFAULT 0,
    state varchar(24) NOT NULL DEFAULT 'UPLOADING',
    expires_at timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((asset_id IS NOT NULL)::integer + (idea_id IS NOT NULL)::integer = 1),
    FOREIGN KEY (owner_id, asset_id) REFERENCES assets(owner_id,id),
    FOREIGN KEY (owner_id, idea_id) REFERENCES ideas(owner_id,id)
);
CREATE INDEX files_owner ON files(owner_id, state);
CREATE TABLE mcp_connections (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES members(id),
    name varchar(100) NOT NULL,
    token_hash varchar(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz
);
CREATE TABLE usage_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES members(id),
    kind varchar(32) NOT NULL CHECK (kind IN ('ASSET_CREATE','ASSET_WRITE','ASSET_VIEW','MCP_SUCCESS','MCP_FAILURE')),
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX usage_events_time ON usage_events(occurred_at, kind, member_id);
