-- Use the table name from com.lemline.runner.models.FailureModel
CREATE TABLE IF NOT EXISTS lemline_failures
(
    id                UUID PRIMARY KEY,
    workflow_id       UUID           NOT NULL,
    workflow_name     VARCHAR(255)   NOT NULL,
    workflow_version  VARCHAR(255)   NOT NULL,
    workflow_position TEXT           NOT NULL,
    workflow_state    TEXT           NOT NULL,
    parent_id         UUID,
    message           TEXT,
    reason            VARCHAR(255)   NOT NULL,
    error_class       TEXT           NOT NULL,
    error_message     TEXT,
    error_stacktrace  TEXT           NOT NULL,
    created_at        TIMESTAMPTZ(6) NOT NULL,
    updated_at        TIMESTAMPTZ(6)
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX IF NOT EXISTS idx_lemline_failures_workflow_id
    ON lemline_failures (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX IF NOT EXISTS idx_lemline_failures_parent_id
    ON lemline_failures (parent_id);
