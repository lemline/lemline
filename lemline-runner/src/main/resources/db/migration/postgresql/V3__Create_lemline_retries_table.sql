-- Use the table name from com.lemline.runner.models.RetryModel
CREATE TABLE IF NOT EXISTS lemline_retries
(
    workflow_id   VARCHAR(36) NOT NULL,
    message       TEXT        NOT NULL,
    status        VARCHAR(50) NOT NULL,
    scheduled_for TIMESTAMPTZ NOT NULL,
    delayed_until TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    last_error    TEXT
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX IF NOT EXISTS idx_lemline_retries_workflow_id
    ON lemline_retries (workflow_id);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX IF NOT EXISTS idx_lemline_retries_status_delayed_until
    ON lemline_retries (status, delayed_until);
