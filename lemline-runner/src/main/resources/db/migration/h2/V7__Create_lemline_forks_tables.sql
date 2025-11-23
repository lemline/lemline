-- Fork metadata table
-- Stores fork configuration and parent workflow state
CREATE TABLE lemline_forks
(
    -- Primary key
    id                  UUID          NOT NULL PRIMARY KEY,
    -- Workflow instance information
    workflow_id         UUID,
    workflow_namespace  VARCHAR(255),
    workflow_name       VARCHAR(255),
    workflow_version    VARCHAR(255),
    workflow_position   VARCHAR(1000),
    workflow_state      CLOB,
    -- Fork-specific fields
    position            VARCHAR(1000) NOT NULL,
    compete             BOOLEAN       NOT NULL,
    output              CLOB,
    -- Cleanup tracking
    outbox_completed_at TIMESTAMP WITH TIME ZONE,
    failed_at           TIMESTAMP,
    -- Error details (inline instead of FK to failures table)
    error_reason        VARCHAR(255),
    error_class         VARCHAR(500),
    error_message       CLOB,
    error_stack_trace   CLOB,
    -- Timestamps
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,

    -- Constraints
    CONSTRAINT uk_forks_workflow_position UNIQUE (workflow_id, position)
);

-- Branch execution table
-- One row per branch, tracks individual branch execution
CREATE TABLE lemline_fork_branches
(
    -- Foreign key to parent fork
    fork_id      UUID         NOT NULL,
    -- Branch metadata
    name         VARCHAR(255) NOT NULL,
    -- Execution state
    output       CLOB,
    -- Timestamps
    completed_at TIMESTAMP,
    failed_at    TIMESTAMP,
    -- Error details (inline instead of FK to failures table)
    error_reason        VARCHAR(255),
    error_class         VARCHAR(500),
    error_message       CLOB,
    error_stack_trace   CLOB,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Constraints
    PRIMARY KEY (fork_id, name),

    CONSTRAINT fk_fork_branches_fork
        FOREIGN KEY (fork_id)
            REFERENCES lemline_forks (id)
            ON DELETE CASCADE
);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_forks_completed
    ON lemline_forks (outbox_completed_at);

-- Note: (workflow_id, position) has unique constraint, no separate index needed
