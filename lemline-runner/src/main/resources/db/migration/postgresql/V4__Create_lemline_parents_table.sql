-- Use the table name from com.lemline.runner.repositories.PARENT_TABLE
CREATE TABLE IF NOT EXISTS lemline_parents
(
    id                  uuid PRIMARY KEY,
    workflow_id         uuid           NOT NULL,
    workflow_namespace  VARCHAR(255)   NOT NULL,
    workflow_name       VARCHAR(255)   NOT NULL,
    workflow_version    VARCHAR(255)   NOT NULL,
    workflow_position   TEXT           NOT NULL,
    workflow_state      TEXT           NOT NULL,
    child_id            uuid           NOT NULL,
    outbox_completed_at TIMESTAMPTZ(6),
    created_at          TIMESTAMPTZ(6) NOT NULL,
    updated_at          TIMESTAMPTZ(6)
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX IF NOT EXISTS idx_lemline_parents_workflow_id
    ON lemline_parents (workflow_id);

-- Create a unique index on child_id (each child can have only one parent)
CREATE UNIQUE INDEX IF NOT EXISTS idx_lemline_parents_child_id
    ON lemline_parents (child_id);

-- Create index for efficient cleanup queries
CREATE INDEX IF NOT EXISTS idx_lemline_parents_completed
    ON lemline_parents (outbox_completed_at)
    WHERE outbox_completed_at IS NOT NULL;

