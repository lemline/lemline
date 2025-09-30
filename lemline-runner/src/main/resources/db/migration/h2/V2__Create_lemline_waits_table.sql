-- Use the table name from com.lemline.runner.repositories.WAIT_TABLE
CREATE TABLE IF NOT EXISTS lemline_waits
(
    id                        UUID PRIMARY KEY,
    workflow_id               UUID                     NOT NULL,
    workflow_namespace        VARCHAR(255)             NOT NULL,
    workflow_name             VARCHAR(255)             NOT NULL,
    workflow_version          VARCHAR(255)             NOT NULL,
    workflow_position         CLOB                     NOT NULL,
    workflow_state            CLOB                     NOT NULL,
    parent_id                 UUID,
    run_status                VARCHAR(50)              NOT NULL,
    run_at                    TIMESTAMP WITH TIME ZONE NOT NULL,
    run_delayed_until         TIMESTAMP WITH TIME ZONE NOT NULL,
    run_attempt_count         INTEGER                  NOT NULL DEFAULT 0,
    run_last_error_class      CLOB,
    run_last_error_message    CLOB,
    run_last_error_stacktrace CLOB,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITH TIME ZONE
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_waits_workflow_id
    ON lemline_waits (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX idx_lemline_waits_parent_id
    ON lemline_waits (parent_id);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX idx_lemline_waits_status_delayed_until
    ON lemline_waits (run_status, run_delayed_until);
