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
    workflow_id             UUID           NOT NULL,
    workflow_position       TEXT           NOT NULL,
    workflow_state          TEXT           NOT NULL,

    -- Correlation state (for Mode 2: first-sets-baseline)
    correlation_values      TEXT,                    -- JSON map of correlation key -> baseline value

    -- Accumulated events (for ANY with until)
    accumulated_events      TEXT,                    -- JSON array of matched events
    matched_filter_indices  TEXT,                    -- JSON array of matched filter indices (for ALL strategy)

    -- Timeout handling
    timeout_at              TIMESTAMPTZ(6),

    -- Outbox fields
    outbox_scheduled_for    TIMESTAMPTZ(6) NOT NULL,
    outbox_delayed_until    TIMESTAMPTZ(6) NOT NULL,
    outbox_attempt_count    INTEGER        NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMPTZ(6),
    outbox_failed_at        TIMESTAMPTZ(6),

    -- Timestamps
    created_at              TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ(6)
);

-- Index for efficient lookup by workflow_id
CREATE INDEX idx_lemline_listeners_workflow_id
    ON lemline_listeners (workflow_id);

-- Index for efficient lookup by workflow info + position (for event routing)
CREATE INDEX idx_lemline_listeners_workflow_position
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Index for correlation-based lookup
CREATE INDEX idx_lemline_listeners_correlation
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position, correlation_values)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Index for timeout processing
CREATE INDEX idx_lemline_listeners_timeout
    ON lemline_listeners (timeout_at)
    WHERE timeout_at IS NOT NULL
        AND outbox_completed_at IS NULL
        AND outbox_failed_at IS NULL;

-- Index for outbox processing
CREATE INDEX idx_lemline_listeners_processing
    ON lemline_listeners (outbox_completed_at, outbox_failed_at, outbox_delayed_until)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Index for cleanup queries
CREATE INDEX idx_lemline_listeners_completed
    ON lemline_listeners (outbox_completed_at)
    WHERE outbox_completed_at IS NOT NULL;

-- Comments for documentation
COMMENT ON TABLE lemline_listeners IS 'Active listener instances waiting for CloudEvents';
COMMENT ON COLUMN lemline_listeners.workflow_namespace IS 'Workflow namespace for locating listen task in cached workflow definition';
COMMENT ON COLUMN lemline_listeners.workflow_name IS 'Workflow name for locating listen task in cached workflow definition';
COMMENT ON COLUMN lemline_listeners.workflow_version IS 'Workflow version for locating listen task in cached workflow definition';
COMMENT ON COLUMN lemline_listeners.correlation_values IS 'Baseline correlation values set by first matching event (Mode 2)';
COMMENT ON COLUMN lemline_listeners.accumulated_events IS 'Events accumulated so far (for ANY with until condition)';
COMMENT ON COLUMN lemline_listeners.matched_filter_indices IS 'Filter indices that have been matched (for ALL strategy)';
