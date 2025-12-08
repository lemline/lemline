-- Listener events table (for ALL and ANY+until strategies)
-- Stores accumulated CloudEvents for listeners that need multiple events
-- Also serves as an outbox for foreach event processing
CREATE TABLE lemline_listener_events
(
    id                      UUID PRIMARY KEY,

    -- Reference to parent listener (CASCADE delete for automatic cleanup)
    listener_id             UUID           NOT NULL REFERENCES lemline_listeners (id) ON DELETE CASCADE,

    -- Filter index: explicit for ALL strategy (0, 1, 2...), NULL for ANY+until
    filter_index            INT,

    -- CloudEvent ID for idempotency (prevents duplicate events on retry)
    -- Used for ANY+until strategy to ensure same CloudEvent isn't added twice
    cloudevent_id           VARCHAR(255),

    -- CloudEvent data
    event                   TEXT           NOT NULL,

    -- Foreach iteration tracking
    iteration_index         INT,
    iteration_output        TEXT,

    -- Outbox fields for foreach processing
    outbox_scheduled_for    TIMESTAMPTZ(6),
    outbox_delayed_until    TIMESTAMPTZ(6),
    outbox_attempt_count    INT            NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMPTZ(6),
    outbox_failed_at        TIMESTAMPTZ(6),

    -- Timestamps
    created_at              TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ(6),

    -- Ensure one event per filter index per listener (protects ALL strategy)
    UNIQUE (listener_id, filter_index),

    -- Ensure same CloudEvent isn't added twice for ANY+until strategy
    UNIQUE (listener_id, cloudevent_id)
);

-- Index for efficient lookup by listener_id
CREATE INDEX idx_lemline_listener_events_listener_id
    ON lemline_listener_events (listener_id);

-- Index for foreach outbox processing
-- Only includes events that have outbox enabled (outbox_scheduled_for IS NOT NULL)
CREATE INDEX idx_lemline_listener_events_outbox
    ON lemline_listener_events (outbox_delayed_until)
    WHERE outbox_scheduled_for IS NOT NULL
      AND outbox_completed_at IS NULL
      AND outbox_failed_at IS NULL;

-- Comments for documentation
COMMENT ON TABLE lemline_listener_events IS 'Accumulated CloudEvents for ALL and ANY+until strategies';
COMMENT ON COLUMN lemline_listener_events.filter_index IS 'Filter index that matched (explicit for ALL, auto-generated for ANY+until)';
COMMENT ON COLUMN lemline_listener_events.event IS 'CloudEvent data (JSON)';
COMMENT ON COLUMN lemline_listener_events.outbox_scheduled_for IS 'When outbox processing was scheduled (NULL = not foreach enabled)';
COMMENT ON COLUMN lemline_listener_events.outbox_delayed_until IS 'When event is ready for foreach processing (NULL = queued)';
COMMENT ON COLUMN lemline_listener_events.iteration_index IS 'Foreach iteration index (0-based)';
COMMENT ON COLUMN lemline_listener_events.iteration_output IS 'Output from foreach.do for this event (JSON)';
