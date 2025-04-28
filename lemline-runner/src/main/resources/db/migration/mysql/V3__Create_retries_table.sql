CREATE TABLE IF NOT EXISTS retries
(
    id             VARCHAR(36) PRIMARY KEY,
    message        TEXT NOT NULL,
    status         VARCHAR(50) NOT NULL,
    delayed_until  TIMESTAMP NOT NULL,
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    last_error     TEXT,
    version_number BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Create index for efficient querying on status and delayed_until
CREATE INDEX idx_retries_status_delayed ON retries (status, delayed_until); 