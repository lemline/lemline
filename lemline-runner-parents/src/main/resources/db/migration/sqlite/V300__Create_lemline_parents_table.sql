-- Use the table name from com.lemline.runner.repositories.PARENTS_TABLE
CREATE TABLE IF NOT EXISTS lemline_parents
(
    id                  TEXT NOT NULL PRIMARY KEY,
    workflow_id         TEXT                     NOT NULL,
    workflow_namespace  TEXT             NOT NULL,
    workflow_name       TEXT             NOT NULL,
    workflow_version    TEXT             NOT NULL,
    workflow_position   TEXT                     NOT NULL,
    workflow_state      TEXT                     NOT NULL,
    child_id            TEXT                     NOT NULL,
    completed_at        INTEGER,
    cleanup_after       INTEGER,
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_parents_workflow_id
    ON lemline_parents (workflow_id);

-- Create a unique index on child_id (each child can have only one parent)
CREATE UNIQUE INDEX idx_lemline_parents_child_id
    ON lemline_parents (child_id);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_parents_cleanup
    ON lemline_parents (cleanup_after);
