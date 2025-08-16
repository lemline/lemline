-- Use the table name from com.lemline.runner.models.RunModel
CREATE TABLE IF NOT EXISTS lemline_run_workflows
(
    id                   VARCHAR(36) PRIMARY KEY,
    workflow_id          VARCHAR(36)  NOT NULL,
    workflow_name        VARCHAR(255) NOT NULL,
    workflow_version     VARCHAR(255) NOT NULL,
    workflow_position    CLOB         NOT NULL,
    workflow_state       CLOB         NOT NULL,
    outbox_status        VARCHAR(50)  NOT NULL,
    outbox_scheduled_for TIMESTAMP,
    outbox_delayed_until TIMESTAMP,
    outbox_attempt_count INTEGER      NOT NULL DEFAULT 0,
    outbox_last_error    CLOB
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_run_workflows_workflow_id
    ON lemline_run_workflows (workflow_id);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX idx_lemline_run_workflows_status_delayed_until
    ON lemline_run_workflows (outbox_status, outbox_delayed_until);
