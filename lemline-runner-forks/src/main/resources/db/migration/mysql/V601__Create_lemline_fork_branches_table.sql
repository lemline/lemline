-- Branch execution table
-- One row per branch, tracks individual branch execution
CREATE TABLE lemline_fork_branches (
    -- Foreign key to parent fork
    fork_id BINARY(16) NOT NULL,

    -- Branch metadata
    name VARCHAR(255) NOT NULL,

    -- Execution state
    output MEDIUMTEXT,

    -- Timestamps
    completed_at TIMESTAMP NULL DEFAULT NULL,
    failed_at TIMESTAMP NULL DEFAULT NULL,

    -- Error details (inline instead of FK to failures table)
    error_reason VARCHAR(255),
    error_class VARCHAR(500),
    error_message TEXT,
    error_stacktrace MEDIUMTEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    PRIMARY KEY (fork_id, name),

    CONSTRAINT fk_fork_branches_fork
        FOREIGN KEY (fork_id)
        REFERENCES lemline_forks(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
