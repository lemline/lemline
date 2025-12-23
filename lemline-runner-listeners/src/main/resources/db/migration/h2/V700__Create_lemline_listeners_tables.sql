-- Listeners table (outbox pattern)
-- Stores active listener instances waiting for CloudEvents
--
-- Listen task configuration (strategy, filters, readAs) is retrieved on-demand
-- from the cached workflow definition using (workflow_namespace, workflow_name, workflow_version, workflow_position)
CREATE TABLE lemline_listeners
(
    id                      UUID PRIMARY KEY,

    -- Workflow definition reference (for locating listen task in cached workflow)
    workflow_namespace      VARCHAR(255)             NOT NULL,
    workflow_name           VARCHAR(255)             NOT NULL,
    workflow_version        VARCHAR(255)             NOT NULL,

    -- Workflow instance information
    workflow_id             UUID                     NOT NULL,
    workflow_position       VARCHAR(1000)            NOT NULL,
    workflow_state          CLOB                     NOT NULL,

    -- Listen strategy: ONE, ANY, ANY_UNTIL_EXPR, ANY_UNTIL_EVENT, ALL
    strategy                VARCHAR(20)              NOT NULL,

    -- Total number of filters (for ALL strategy completion check)
    filters_count           INT,

    -- Whether listener has an until condition (for routing decisions)
    has_until               BOOLEAN                  NOT NULL DEFAULT FALSE,

    -- Until expression (for ANY_UNTIL_EXPR strategy)
    until_expression        CLOB,

    -- Whether listener has foreach.do configured
    has_foreach             BOOLEAN                  NOT NULL DEFAULT FALSE,

    -- Correlation baseline values (Mode 2: first-sets-baseline), JSON map
    -- Using VARCHAR instead of CLOB to allow indexing (H2 test database only)
    correlation_values      VARCHAR(1000),

    -- Timeout handling
    timeout_at              TIMESTAMP WITH TIME ZONE,

    -- State progression: ready_at is set when completion criteria are met
    -- This triggers ListenerCompletionOutbox to process the listener
    ready_at                TIMESTAMP WITH TIME ZONE,

    -- Standard outbox fields (for completion processing)
    -- outbox_delayed_until: NULL = waiting, NOT NULL = ready for processing
    outbox_scheduled_for    TIMESTAMP WITH TIME ZONE,
    outbox_delayed_until    TIMESTAMP WITH TIME ZONE,
    outbox_attempt_count    INTEGER                  NOT NULL DEFAULT 0,
    outbox_error_class      CLOB,
    outbox_error_message    CLOB,
    outbox_error_stacktrace CLOB,
    outbox_completed_at     TIMESTAMP WITH TIME ZONE,
    outbox_failed_at        TIMESTAMP WITH TIME ZONE,

    -- Cleanup
    cleanup_after           TIMESTAMP WITH TIME ZONE,

    -- Timestamps
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE
);

-- Index for efficient lookup by workflow_id
CREATE INDEX idx_lemline_listeners_workflow_id
    ON lemline_listeners (workflow_id);

-- Index for finding pending listeners by workflow identity (for event routing)
CREATE INDEX idx_lemline_listeners_pending
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position);

-- Index for correlation-based lookup
CREATE INDEX idx_lemline_listeners_correlation
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position, correlation_values);

-- Index for completion outbox processing (ready listeners)
CREATE INDEX idx_lemline_listeners_ready
    ON lemline_listeners (ready_at);

-- Index for timeout processing
CREATE INDEX idx_lemline_listeners_timeout
    ON lemline_listeners (timeout_at);

-- Index for outbox processing
CREATE INDEX idx_lemline_listeners_processing
    ON lemline_listeners (outbox_completed_at, outbox_failed_at, outbox_delayed_until);

-- Index for cleanup queries
CREATE INDEX idx_lemline_listeners_cleanup
    ON lemline_listeners (cleanup_after);
