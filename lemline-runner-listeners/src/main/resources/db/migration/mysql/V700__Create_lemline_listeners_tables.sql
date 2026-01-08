-- Listeners table (outbox pattern)
-- Stores active listener instances waiting for CloudEvents
--
-- Listen task configuration (strategy, filters, readAs) is retrieved on-demand
-- from the cached workflow definition using (workflow_namespace, workflow_name, workflow_version, workflow_position)
CREATE TABLE lemline_listeners
(
    id                      BINARY(16) PRIMARY KEY,

    -- Workflow definition reference (for locating listen task in cached workflow)
    workflow_namespace      VARCHAR(255) NOT NULL,
    workflow_name           VARCHAR(255) NOT NULL,
    workflow_version        VARCHAR(255) NOT NULL,

    -- Workflow instance information
    workflow_id             BINARY(16)   NOT NULL,
    workflow_position       TEXT         NOT NULL,
    workflow_state          MEDIUMTEXT   NOT NULL,

    -- Listen strategy: ONE, ANY, ANY_UNTIL_EXPR, ANY_UNTIL_EVENT, ALL
    strategy                VARCHAR(20)  NOT NULL,

    -- Total number of filters (for ALL strategy completion check)
    filters_count           INT,

    -- Whether listener has an until condition (for routing decisions)
    has_until               BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Until expression (for ANY_UNTIL_EXPR strategy)
    until_expression        TEXT,

    -- Whether listener has foreach.do configured
    has_foreach             BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Correlation baseline values (Mode 2: first-sets-baseline), JSON map
    correlation_values      TEXT,

    -- Timeout handling
    timeout_at              TIMESTAMP(6),

    -- State progression: closed_at is set when listener stops accepting new events
    -- NOTE: Does NOT directly trigger ListenerCompletionOutbox - only outbox_delayed_until does
    closed_at               TIMESTAMP(6),

    -- Standard outbox fields (for completion processing)
    -- outbox_delayed_until: NULL = waiting, NOT NULL = ready for processing
    outbox_scheduled_for    TIMESTAMP(6) NULL,
    outbox_delayed_until    TIMESTAMP(6),
    outbox_attempt_count    INTEGER      NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace MEDIUMTEXT,
    outbox_completed_at     TIMESTAMP(6),
    outbox_failed_at        TIMESTAMP(6),

    -- Cleanup
    cleanup_after           TIMESTAMP(6),

    -- Timestamps
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;

-- Index for efficient lookup by workflow_id
CREATE INDEX idx_lemline_listeners_workflow_id
    ON lemline_listeners (workflow_id);

-- Composite index for efficient lookup by workflow definition (used by deleteByWorkflowDefinition)
CREATE INDEX idx_lemline_listeners_definition
    ON lemline_listeners (workflow_namespace(50), workflow_name(100), workflow_version(20));

-- Index for finding pending listeners by workflow identity (for event routing)
-- Using prefixes to stay within MySQL's 3072 byte key limit (utf8mb4 = 4 bytes/char)
CREATE INDEX idx_lemline_listeners_pending
    ON lemline_listeners (workflow_namespace(50), workflow_name(100), workflow_version(20), workflow_position(500));

-- Index for correlation-based lookup (optimized for active listeners)
-- Prefix lengths reduced to stay within MySQL's 3072 byte key limit (utf8mb4 = 4 bytes/char)
-- Includes status columns first for efficient filtering (MySQL doesn't support partial indexes)
CREATE INDEX idx_lemline_listeners_correlation
    ON lemline_listeners (outbox_completed_at, outbox_failed_at, closed_at, workflow_namespace(50), workflow_name(100), workflow_version(20), workflow_position(400), correlation_values(100));

-- Index for completion outbox processing (closed listeners)
CREATE INDEX idx_lemline_listeners_closed
    ON lemline_listeners (closed_at);

-- Index for timeout processing (optimized for active listeners with timeout)
-- MySQL doesn't support partial indexes, so include filter columns in composite index
CREATE INDEX idx_lemline_listeners_timeout
    ON lemline_listeners (outbox_completed_at, outbox_failed_at, timeout_at);

-- Index for outbox processing
CREATE INDEX idx_lemline_listeners_processing
    ON lemline_listeners (outbox_completed_at, outbox_failed_at, outbox_delayed_until);

-- Index for cleanup queries
CREATE INDEX idx_lemline_listeners_cleanup
    ON lemline_listeners (cleanup_after);
