ALTER TABLE ideas ALTER COLUMN body DROP DEFAULT;
ALTER TABLE ideas ALTER COLUMN body TYPE jsonb USING to_jsonb(body);
ALTER TABLE ideas ALTER COLUMN body SET DEFAULT '""'::jsonb;
CREATE TABLE asset_version_files (
    asset_id uuid NOT NULL,
    number integer NOT NULL,
    file_id uuid NOT NULL REFERENCES files(id),
    PRIMARY KEY(asset_id,number,file_id),
    FOREIGN KEY(asset_id,number) REFERENCES asset_versions(asset_id,number)
);
INSERT INTO asset_version_files(asset_id,number,file_id)
SELECT v.asset_id,v.number,(reference.value)::uuid
FROM asset_versions v
CROSS JOIN LATERAL jsonb_array_elements_text(v.content->'fileIds') reference
JOIN files f ON f.id=(reference.value)::uuid AND f.asset_id=v.asset_id
ON CONFLICT DO NOTHING;
