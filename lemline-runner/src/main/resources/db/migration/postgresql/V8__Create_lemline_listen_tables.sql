-- Definition Listen Filters table
-- Stores listen filter definitions extracted from workflow definitions
-- One row per event filter in a listen task
CREATE TABLE lemline_definition_listens (
    -- Primary key
    id UUID PRIMARY KEY,

    -- Definition reference (composite key from lemline_definitions)
    workflow_namespace VARCHAR(255) COLLATE "C" NOT NULL,
    workflow_name VARCHAR(255) COLLATE "C" NOT NULL,
    workflow_version VARCHAR(255) COLLATE "C" NOT NULL,

    -- Listen task location in workflow
    node_position TEXT NOT NULL,

    -- Filter index within the listen task (for ALL strategy ordering)
    filter_index INTEGER NOT NULL,

    -- Event matching criteria (CloudEvent attributes)
    event_type VARCHAR(255),
    event_source TEXT,
    event_subject VARCHAR(255),

    -- Serialized correlation definitions (JSON)
    correlations TEXT,

    -- Timestamps
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6),

    -- Foreign key to definitions table
    CONSTRAINT fk_definition_listens_definition
        FOREIGN KEY (workflow_namespace, workflow_name, workflow_version)
        REFERENCES lemline_definitions(namespace, name, version)
        ON DELETE CASCADE,

    -- Unique constraint on definition + position + filter index
    CONSTRAINT uk_definition_listens_position
        UNIQUE (workflow_namespace, workflow_name, workflow_version, node_position, filter_index)
);

-- Index for efficient lookup by definition
CREATE INDEX idx_lemline_definition_listens_definition
    ON lemline_definition_listens (workflow_namespace, workflow_name, workflow_version);

-- Index for efficient lookup by event type (most common filter)
CREATE INDEX idx_lemline_definition_listens_event_type
    ON lemline_definition_listens (event_type)
    WHERE event_type IS NOT NULL;

-- Listeners table (outbox pattern)
-- Stores active listener instances waiting for events
CREATE TABLE lemline_listeners (
    -- Primary key
    id UUID PRIMARY KEY,

    -- Workflow instance information
    workflow_id UUID NOT NULL,
    workflow_namespace VARCHAR(255) NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    workflow_version VARCHAR(255) NOT NULL,
    workflow_position TEXT NOT NULL,
    workflow_state TEXT NOT NULL,

    -- Listen task configuration
    strategy VARCHAR(10) NOT NULL,  -- 'ONE', 'ANY', 'ALL'
    read_mode VARCHAR(10) NOT NULL, -- 'DATA', 'ENVELOPE', 'RAW'
    config TEXT NOT NULL,           -- Serialized ListenConfig JSON

    -- Correlation state (for Mode 2: first-sets-baseline)
    correlation_values TEXT,        -- JSON map of correlation key -> baseline value

    -- Accumulated events (for ANY with until)
    accumulated_events TEXT,        -- JSON array of matched events
    matched_filter_indices TEXT,    -- JSON array of matched filter indices (for ALL strategy)

    -- Timeout handling
    timeout_at TIMESTAMPTZ(6),

    -- Outbox fields
    outbox_scheduled_for TIMESTAMPTZ(6) NOT NULL,
    outbox_delayed_until TIMESTAMPTZ(6) NOT NULL,
    outbox_attempt_count INTEGER NOT NULL DEFAULT 0,
    outbox_error_class TEXT,
    outbox_error_message TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at TIMESTAMPTZ(6),
    outbox_failed_at TIMESTAMPTZ(6),

    -- Timestamps
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6)
);

-- Index for efficient lookup by workflow_id
CREATE INDEX idx_lemline_listeners_workflow_id
    ON lemline_listeners (workflow_id);

-- Index for finding listeners by workflow definition (for event routing)
CREATE INDEX idx_lemline_listeners_definition
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version)
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
COMMENT ON TABLE lemline_definition_listens IS 'Listen filter definitions extracted from workflow definitions';
COMMENT ON TABLE lemline_listeners IS 'Active listener instances waiting for CloudEvents';
COMMENT ON COLUMN lemline_listeners.strategy IS 'Event consumption strategy: ONE, ANY, or ALL';
COMMENT ON COLUMN lemline_listeners.correlation_values IS 'Baseline correlation values set by first matching event (Mode 2)';
COMMENT ON COLUMN lemline_listeners.accumulated_events IS 'Events accumulated so far (for ANY with until condition)';
COMMENT ON COLUMN lemline_listeners.matched_filter_indices IS 'Filter indices that have been matched (for ALL strategy)';
