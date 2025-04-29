-- Use the table name from WaitRepository (tableName = "waits")
CREATE TABLE IF NOT EXISTS waits
(
    id            VARCHAR(36) PRIMARY KEY,
    message       TEXT        NOT NULL,
    status        VARCHAR(50) NOT NULL,
    delayed_until TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    last_error    TEXT
);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX IF NOT EXISTS idx_waits_status_delayed_until ON waits (status, delayed_until);
