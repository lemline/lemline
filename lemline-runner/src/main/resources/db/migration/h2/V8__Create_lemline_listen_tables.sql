-- Definition Listen Filters table
-- Stores listen filter definitions extracted from workflow definitions
-- One row per event filter in a listen task
CREATE TABLE lemline_definition_listens (
    -- Primary key
    id UUID PRIMARY KEY,

    -- Definition reference (composite key from lemline_definitions)
    workflow_namespace VARCHAR(255) NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    workflow_version VARCHAR(255) NOT NULL,

    -- Listen task location in workflow
    node_position VARCHAR(1000) NOT NULL,

    -- Filter index within the listen task (for ALL strategy ordering)
    filter_index INTEGER NOT NULL,

    -- Event matching criteria (CloudEvent attributes)
    event_type VARCHAR(255),
    event_source CLOB,
    event_subject VARCHAR(255),

    -- Serialized correlation definitions (JSON)
    correlations CLOB,

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,

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
    ON lemline_definition_listens (event_type);

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
    workflow_position CLOB NOT NULL,
    workflow_state CLOB NOT NULL,

    -- Listen task configuration
    strategy VARCHAR(10) NOT NULL,  -- 'ONE', 'ANY', 'ALL'
    read_mode VARCHAR(10) NOT NULL, -- 'DATA', 'ENVELOPE', 'RAW'
    config CLOB NOT NULL,           -- Serialized ListenConfig JSON

    -- Correlation state (for Mode 2: first-sets-baseline)
    correlation_values CLOB,        -- JSON map of correlation key -> baseline value

    -- Accumulated events (for ANY with until)
    accumulated_events CLOB,        -- JSON array of matched events
    matched_filter_indices CLOB,    -- JSON array of matched filter indices (for ALL strategy)

    -- Timeout handling
    timeout_at TIMESTAMP WITH TIME ZONE,

    -- Outbox fields
    outbox_scheduled_for TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_delayed_until TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_attempt_count INTEGER NOT NULL DEFAULT 0,
    outbox_error_class CLOB,
    outbox_error_message CLOB,
    outbox_error_stacktrace CLOB,
    outbox_completed_at TIMESTAMP WITH TIME ZONE,
    outbox_failed_at TIMESTAMP WITH TIME ZONE,

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE
);

-- Index for efficient lookup by workflow_id
CREATE INDEX idx_lemline_listeners_workflow_id
    ON lemline_listeners (workflow_id);

-- Index for finding listeners by workflow definition (for event routing)
CREATE INDEX idx_lemline_listeners_definition
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version);

-- Index for timeout processing
CREATE INDEX idx_lemline_listeners_timeout
    ON lemline_listeners (timeout_at);

-- Index for outbox processing
CREATE INDEX idx_lemline_listeners_processing
    ON lemline_listeners (outbox_completed_at, outbox_failed_at, outbox_delayed_until);

-- Index for cleanup queries
CREATE INDEX idx_lemline_listeners_completed
    ON lemline_listeners (outbox_completed_at);
