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

    -- Single event storage (for ONE and ANY without until)
    event                   TEXT,                    -- JSON CloudEvent data

    -- ALL strategy: total number of filters that must match before completion
    total_filters           INT,

    -- Timeout handling
    timeout_at              TIMESTAMPTZ(6),

    -- Outbox fields
    -- outbox_delayed_until: NULL = waiting, NOT NULL = ready for processing
    outbox_scheduled_for    TIMESTAMPTZ(6) NOT NULL,
    outbox_delayed_until    TIMESTAMPTZ(6),
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

-- Listener events table (for ALL and ANY+until strategies)
-- Stores accumulated CloudEvents for listeners that need multiple events
CREATE TABLE lemline_listener_events
(
    id              UUID PRIMARY KEY,

    -- Reference to parent listener (CASCADE delete for automatic cleanup)
    listener_id     UUID         NOT NULL REFERENCES lemline_listeners (id) ON DELETE CASCADE,

    -- Filter index: explicit for ALL strategy (0, 1, 2...), NULL for ANY+until
    filter_index    INT,

    -- CloudEvent data
    event           TEXT         NOT NULL,

    -- Timestamps
    created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ(6),

    -- Ensure one event per filter index per listener (protects ALL strategy)
    UNIQUE (listener_id, filter_index)
);

-- Index for efficient lookup by listener_id
CREATE INDEX idx_lemline_listener_events_listener_id
    ON lemline_listener_events (listener_id);

-- Comments for documentation
COMMENT ON TABLE lemline_listeners IS 'Active listener instances waiting for CloudEvents';
COMMENT ON COLUMN lemline_listeners.workflow_namespace IS 'Workflow namespace for locating listen task in cached workflow definition';
COMMENT ON COLUMN lemline_listeners.workflow_name IS 'Workflow name for locating listen task in cached workflow definition';
COMMENT ON COLUMN lemline_listeners.workflow_version IS 'Workflow version for locating listen task in cached workflow definition';
COMMENT ON COLUMN lemline_listeners.correlation_values IS 'Baseline correlation values set by first matching event (Mode 2)';
COMMENT ON COLUMN lemline_listeners.event IS 'Single matched event for ONE/ANY strategies (JSON)';

COMMENT ON TABLE lemline_listener_events IS 'Accumulated CloudEvents for ALL and ANY+until strategies';
COMMENT ON COLUMN lemline_listener_events.filter_index IS 'Filter index that matched (explicit for ALL, auto-generated for ANY+until)';
COMMENT ON COLUMN lemline_listener_events.event IS 'CloudEvent data (JSON)';
