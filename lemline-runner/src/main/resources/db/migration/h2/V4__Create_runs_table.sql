-- Use the table name from com.lemline.runner.models.RunModel
CREATE TABLE IF NOT EXISTS lemline_runs
(
    id            VARCHAR(36) PRIMARY KEY,
    message       CLOB        NOT NULL,
    status        VARCHAR(50) NOT NULL,
    delayed_until TIMESTAMP   NOT NULL,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    last_error    CLOB
);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX idx_lemline_runs_status_delayed_until ON lemline_runs (status, delayed_until);
