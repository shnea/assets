ALTER TABLE mcp_connections ADD COLUMN can_write boolean NOT NULL DEFAULT false;

CREATE TABLE mcp_writes (
    owner_id uuid NOT NULL REFERENCES members(id),
    request_id uuid NOT NULL,
    operation varchar(40) NOT NULL,
    request_hash varchar(64) NOT NULL,
    asset_id uuid NOT NULL,
    version integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, request_id),
    FOREIGN KEY (owner_id, asset_id) REFERENCES assets(owner_id, id),
    FOREIGN KEY (asset_id, version) REFERENCES asset_versions(asset_id, number)
);
