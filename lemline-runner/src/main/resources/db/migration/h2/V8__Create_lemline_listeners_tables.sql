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

    -- Correlation state (for Mode 2: first-sets-baseline)
    correlation_values      CLOB,                              -- JSON map of correlation key -> baseline value

    -- Single event storage (for ONE and ANY without until)
    event                   CLOB,                              -- JSON CloudEvent data

    -- ALL strategy: total number of filters that must match before completion
    total_filters           INT,

    -- Timeout handling
    timeout_at              TIMESTAMP WITH TIME ZONE,

    -- Outbox fields
    -- outbox_delayed_until: NULL = waiting, NOT NULL = ready for processing
    outbox_scheduled_for    TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_delayed_until    TIMESTAMP WITH TIME ZONE,
    outbox_attempt_count    INTEGER                  NOT NULL DEFAULT 0,
    outbox_error_class      CLOB,
    outbox_error_message    CLOB,
    outbox_error_stacktrace CLOB,
    outbox_completed_at     TIMESTAMP WITH TIME ZONE,
    outbox_failed_at        TIMESTAMP WITH TIME ZONE,

    -- Timestamps
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE
);

-- Index for efficient lookup by workflow_id
CREATE INDEX idx_lemline_listeners_workflow_id
    ON lemline_listeners (workflow_id);

-- Index for efficient lookup by workflow info + position (for event routing)
CREATE INDEX idx_lemline_listeners_workflow_position
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position);

-- Index for timeout processing
CREATE INDEX idx_lemline_listeners_timeout
    ON lemline_listeners (timeout_at);

-- Index for outbox processing
CREATE INDEX idx_lemline_listeners_processing
    ON lemline_listeners (outbox_completed_at, outbox_failed_at, outbox_delayed_until);

-- Index for cleanup queries
CREATE INDEX idx_lemline_listeners_completed
    ON lemline_listeners (outbox_completed_at);

-- Listener events table (for ALL and ANY+until strategies)
-- Stores accumulated CloudEvents for listeners that need multiple events
CREATE TABLE lemline_listener_events
(
    id              UUID PRIMARY KEY,

    -- Reference to parent listener (CASCADE delete for automatic cleanup)
    listener_id     UUID                     NOT NULL REFERENCES lemline_listeners (id) ON DELETE CASCADE,

    -- Filter index: explicit for ALL strategy (0, 1, 2...), NULL for ANY+until
    filter_index    INT,

    -- CloudEvent ID for idempotency (prevents duplicate events on retry)
    -- Used for ANY+until strategy to ensure same CloudEvent isn't added twice
    cloudevent_id   VARCHAR(255),

    -- CloudEvent data
    event           CLOB                     NOT NULL,

    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE,

    -- Ensure one event per filter index per listener (protects ALL strategy)
    UNIQUE (listener_id, filter_index),

    -- Ensure same CloudEvent isn't added twice for ANY+until strategy
    UNIQUE (listener_id, cloudevent_id)
);

-- Index for efficient lookup by listener_id
CREATE INDEX idx_lemline_listener_events_listener_id
    ON lemline_listener_events (listener_id);
