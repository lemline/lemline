-- Listeners table (outbox pattern)
-- Stores active listener instances waiting for CloudEvents
--
-- Listen task configuration (strategy, filters, readAs) is retrieved on-demand
-- from the cached workflow definition using (workflow_namespace, workflow_name, workflow_version, workflow_position)
CREATE TABLE lemline_listeners
(
    id                      UUID PRIMARY KEY,

    -- Workflow definition reference (for locating listen task in cached workflow)
    workflow_namespace      VARCHAR(255) COLLATE "C" NOT NULL,
    workflow_name           VARCHAR(255) COLLATE "C" NOT NULL,
    workflow_version        VARCHAR(255) COLLATE "C" NOT NULL,

    -- Workflow instance information
    workflow_id             UUID                     NOT NULL,
    workflow_position       TEXT                     NOT NULL,
    workflow_state          TEXT                     NOT NULL,

    -- Listen strategy: ONE, ANY, ANY_UNTIL_EXPR, ANY_UNTIL_EVENT, ALL
    strategy                VARCHAR(20)              NOT NULL,

    -- Total number of filters (for ALL strategy completion check)
    filters_count           INT,

    -- Whether listener has an until condition (for routing decisions)
    has_until               BOOLEAN                  NOT NULL DEFAULT FALSE,

    -- Until expression (for ANY_UNTIL_EXPR strategy)
    until_expression        TEXT,

    -- Whether listener has foreach.do configured
    has_foreach             BOOLEAN                  NOT NULL DEFAULT FALSE,

    -- Correlation baseline values (Mode 2: first-sets-baseline), JSON map
    correlation_values      TEXT,

    -- Timeout handling
    timeout_at              TIMESTAMPTZ(6),

    -- State progression: closed_at is set when listener stops accepting new events
    -- NOTE: Does NOT directly trigger ListenerCompletionOutbox - only outbox_delayed_until does
    closed_at               TIMESTAMPTZ(6),

    -- Standard outbox fields (for completion processing)
    -- outbox_delayed_until: NULL = waiting, NOT NULL = ready for processing
    outbox_scheduled_for    TIMESTAMPTZ(6),
    outbox_delayed_until    TIMESTAMPTZ(6),
    outbox_attempt_count    INTEGER                  NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMPTZ(6),
    outbox_failed_at        TIMESTAMPTZ(6),

    -- Cleanup
    cleanup_after           TIMESTAMPTZ(6),

    -- Timestamps
    created_at              TIMESTAMPTZ(6)           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ(6)
);

-- Index for efficient lookup by workflow_id
CREATE INDEX idx_lemline_listeners_workflow_id
    ON lemline_listeners (workflow_id);

-- Composite index for efficient lookup by workflow definition (used by deleteByWorkflowDefinition)
CREATE INDEX idx_lemline_listeners_definition
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version);

-- Index for finding pending listeners by workflow identity (for event routing)
CREATE INDEX idx_lemline_listeners_pending
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Index for correlation-based lookup
-- Optimized: Added closed_at filter for improved query performance
CREATE INDEX idx_lemline_listeners_correlation
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position, correlation_values)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL AND closed_at IS NULL;

-- Index for completion outbox processing (closed listeners)
CREATE INDEX idx_lemline_listeners_closed
    ON lemline_listeners (closed_at)
    WHERE closed_at IS NOT NULL AND outbox_completed_at IS NULL;

-- Index for timeout processing
-- Optimized: Explicit ASC for ORDER BY timeout_at queries
CREATE INDEX idx_lemline_listeners_timeout
    ON lemline_listeners (timeout_at ASC)
    WHERE timeout_at IS NOT NULL
      AND outbox_completed_at IS NULL
      AND outbox_failed_at IS NULL;

-- Index for outbox processing
CREATE INDEX idx_lemline_listeners_processing
    ON lemline_listeners (outbox_completed_at, outbox_failed_at, outbox_delayed_until)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Index for cleanup queries
CREATE INDEX idx_lemline_listeners_cleanup
    ON lemline_listeners (cleanup_after)
    WHERE cleanup_after IS NOT NULL;

-- Comments for documentation
COMMENT ON TABLE lemline_listeners IS 'Active listener instances waiting for CloudEvents';
COMMENT ON COLUMN lemline_listeners.workflow_namespace IS 'Workflow namespace for locating listen task in cached workflow definition';
COMMENT ON COLUMN lemline_listeners.workflow_name IS 'Workflow name for locating listen task in cached workflow definition';
COMMENT ON COLUMN lemline_listeners.workflow_version IS 'Workflow version for locating listen task in cached workflow definition';
COMMENT ON COLUMN lemline_listeners.strategy IS 'Listen strategy: ONE, ANY, ANY_UNTIL_EXPR, ANY_UNTIL_EVENT, ALL';
COMMENT ON COLUMN lemline_listeners.filters_count IS 'Total number of filters (for ALL strategy completion check)';
COMMENT ON COLUMN lemline_listeners.has_until IS 'TRUE if listener has an until condition';
COMMENT ON COLUMN lemline_listeners.until_expression IS 'JQ expression for ANY_UNTIL_EXPR strategy';
COMMENT ON COLUMN lemline_listeners.has_foreach IS 'TRUE if listener has foreach.do configured';
COMMENT ON COLUMN lemline_listeners.correlation_values IS 'Baseline correlation values set by first matching event (Mode 2)';
COMMENT ON COLUMN lemline_listeners.closed_at IS 'Timestamp when listener stopped accepting new events (does NOT trigger outbox - see outbox_delayed_until)';
