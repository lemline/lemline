-- Listener events table (for ALL and ANY+until strategies)
-- Stores accumulated CloudEvents for listeners that need multiple events
-- Also serves as an outbox for foreach event processing
CREATE TABLE lemline_listener_events
(
    id                      UUID PRIMARY KEY,

    -- Reference to parent listener (CASCADE delete for automatic cleanup)
    listener_id             UUID                     NOT NULL REFERENCES lemline_listeners (id) ON DELETE CASCADE,

    -- Filter index: explicit for ALL strategy (0, 1, 2...), NULL for ANY+until
    filter_index            INT,

    -- CloudEvent ID for idempotency (prevents duplicate events on retry)
    -- Used for ANY+until strategy to ensure same CloudEvent isn't added twice
    cloudevent_id           VARCHAR(255),

    -- CloudEvent data
    event                   CLOB                     NOT NULL,

    -- Foreach iteration tracking
    iteration_index         INT,
    iteration_output        CLOB,

    -- Outbox fields for foreach processing
    outbox_scheduled_for    TIMESTAMP WITH TIME ZONE,
    outbox_delayed_until    TIMESTAMP WITH TIME ZONE,
    outbox_attempt_count    INT                      NOT NULL DEFAULT 0,
    outbox_error_class      CLOB,
    outbox_error_message    CLOB,
    outbox_error_stacktrace CLOB,
    outbox_completed_at     TIMESTAMP WITH TIME ZONE,
    outbox_failed_at        TIMESTAMP WITH TIME ZONE,

    -- Timestamps
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE,

    -- Ensure one event per filter index per listener (protects ALL strategy)
    UNIQUE (listener_id, filter_index),

    -- Ensure same CloudEvent isn't added twice for ANY+until strategy
    UNIQUE (listener_id, cloudevent_id)
);

-- Index for efficient lookup by listener_id
CREATE INDEX idx_lemline_listener_events_listener_id
    ON lemline_listener_events (listener_id);

-- Index for foreach outbox processing
CREATE INDEX idx_lemline_listener_events_outbox
    ON lemline_listener_events (outbox_delayed_until);
