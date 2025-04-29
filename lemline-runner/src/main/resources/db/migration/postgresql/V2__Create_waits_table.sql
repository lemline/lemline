CREATE TABLE IF NOT EXISTS waits
(
    id             VARCHAR(36) PRIMARY KEY,
    message        TEXT NOT NULL,
    status         VARCHAR(50) NOT NULL,
    delayed_until  TIMESTAMP WITH TIME ZONE NOT NULL,
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    last_error     TEXT,
    version_number BIGINT NOT NULL DEFAULT 0
);

-- Create index for efficient querying on status and delayed_until
CREATE INDEX idx_waits_status_delayed_until ON waits (status, delayed_until);