-- Branch execution table
-- One row per branch, tracks individual branch execution
CREATE TABLE lemline_fork_branches
(
    -- Primary key
    id               TEXT         NOT NULL PRIMARY KEY,
    -- Foreign key to parent fork
    fork_id          TEXT         NOT NULL,
    -- Branch metadata
    branch_position  TEXT NOT NULL,
    -- Execution state
    branch_output    TEXT,
    -- Timestamps
    completed_at     INTEGER,
    failed_at        INTEGER,
    -- Error details (inline instead of FK to failures table)
    error_reason     TEXT,
    error_class      TEXT,
    error_message    TEXT,
    error_stacktrace TEXT,
    created_at       INTEGER    NOT NULL DEFAULT (CAST(round(unixepoch('subsec') * 1000000) AS INTEGER)),
    updated_at       INTEGER    NOT NULL DEFAULT (CAST(round(unixepoch('subsec') * 1000000) AS INTEGER)),
    -- Constraints

    CONSTRAINT fk_fork_branches_fork
        FOREIGN KEY (fork_id)
            REFERENCES lemline_forks (id)
            ON DELETE CASCADE
);

-- Index for efficient branch lookup by fork_id (used by deleteByForkId)
CREATE INDEX idx_lemline_fork_branches_fork_id ON lemline_fork_branches (fork_id);

-- Composite index for efficient branch retrieval with ORDER BY branch_position (used by findByForkId)
CREATE INDEX idx_lemline_fork_branches_fork_position ON lemline_fork_branches (fork_id, branch_position);
