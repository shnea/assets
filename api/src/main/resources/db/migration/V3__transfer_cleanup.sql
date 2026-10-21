-- Only remote uploads created by a failed restore are queued here.
CREATE TABLE transfer_cleanup (
    upload_id uuid PRIMARY KEY,
    attempted_at timestamptz
);
