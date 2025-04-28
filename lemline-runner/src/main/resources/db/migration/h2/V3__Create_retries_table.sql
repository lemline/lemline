CREATE TABLE IF NOT EXISTS retries
(
    id             VARCHAR(36) PRIMARY KEY,
    message        CLOB NOT NULL,
    status         VARCHAR(50) NOT NULL,
    delayed_until  TIMESTAMP NOT NULL,
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    last_error     CLOB,
    version_number BIGINT NOT NULL DEFAULT 0
);

-- Create index for efficient querying on status and delayed_until
CREATE INDEX IF NOT EXISTS idx_retries_status_delayed ON retries (status, delayed_until); 