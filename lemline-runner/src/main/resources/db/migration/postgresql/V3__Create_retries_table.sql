CREATE TABLE IF NOT EXISTS retries
(
    id             VARCHAR(36) PRIMARY KEY,
    message        TEXT NOT NULL,
    status         VARCHAR(50) NOT NULL,
    delayed_until  TIMESTAMP WITH TIME ZONE NOT NULL,
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    last_error     TEXT,
    version_number BIGINT NOT NULL DEFAULT 0
) WITH (
    ENCODING = 'UTF8'  -- PostgreSQL UTF8 supports full Unicode (up to 4 bytes)
);
-- Create index for efficient querying on status and delayed_until
CREATE INDEX IF NOT EXISTS idx_retries_status_delayed ON retries (status, delayed_until);