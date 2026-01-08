-- Listener events table (following standard outbox pattern)
-- Stores CloudEvents for ALL listeners (not just accumulating strategies)
-- Also serves as the foreach outbox for sequential event processing
CREATE TABLE lemline_listener_events
(
    -- Reference to parent listener (CASCADE delete for automatic cleanup)
    listener_id             UUID           NOT NULL REFERENCES lemline_listeners (id) ON DELETE CASCADE,

    -- Filter index that matched (for ALL strategy completion check)
    -- Defaults to 0 for ONE/ANY strategies (single event per listener)
    -- Part of composite PK to allow same event to match multiple filters
    filter_index            INT,

    -- Per-listener sort key for deterministic FIFO ordering (0, 1, 2... per listener)
    sort_key                BIGINT         NOT NULL DEFAULT 0,

    -- CloudEvent ID (from the CloudEvent spec 'id' field)
    -- Part of composite PK for natural idempotency
    event_id                VARCHAR(255)   NOT NULL,

    -- CloudEvent data as JSON string
    event                   TEXT           NOT NULL,

    -- Whether foreach.do has completed (efficient boolean for indexing)
    foreach_completed       BOOLEAN        NOT NULL DEFAULT FALSE,

    -- Output from foreach.do iteration (captured after completion)
    foreach_output          TEXT,

    -- Standard outbox fields (for foreach processing via AbstractOutbox)
    -- outbox_scheduled_for: when this event was inserted (NULL for non-foreach events)
    -- outbox_delayed_until: NULL = waiting for FIFO turn, NOT NULL = ready for processing
    -- outbox_completed_at: foreach.do completed successfully (or no foreach, completed immediately)
    outbox_scheduled_for    TIMESTAMPTZ(6),
    outbox_delayed_until    TIMESTAMPTZ(6),
    outbox_attempt_count    INT            NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMPTZ(6),
    outbox_failed_at        TIMESTAMPTZ(6),

    -- Cleanup
    cleanup_after           TIMESTAMPTZ(6),

    -- Timestamps (created_at used for FIFO ordering)
    created_at              TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ(6)
);

-- Unique constraint for ALL strategy idempotency (one event per filter per listener)
-- For ONE/ANY strategies, filter_index defaults to 0, ensuring only one event is stored
-- For ANY+Until strategies, filter_index is NULL, allowing multiple events to accumulate
CREATE UNIQUE INDEX idx_lemline_listener_events_filter_unique
    ON lemline_listener_events (listener_id, filter_index);

-- Index for cleanup
CREATE INDEX idx_lemline_listener_events_cleanup
    ON lemline_listener_events (cleanup_after)
    WHERE cleanup_after IS NOT NULL;

-- Unique constraint on (listener_id, sort_key) for FIFO ordering per listener
CREATE UNIQUE INDEX idx_lemline_listener_events_listener_sort_key
    ON lemline_listener_events (listener_id, sort_key);

-- Partial indexes for markReadyForForeach FIFO queue processing
CREATE INDEX idx_lemline_listener_events_pending
    ON lemline_listener_events (listener_id, sort_key)
    WHERE foreach_completed = FALSE AND outbox_delayed_until IS NULL;

CREATE INDEX idx_lemline_listener_events_processing
    ON lemline_listener_events (listener_id)
    WHERE foreach_completed = FALSE AND outbox_delayed_until IS NOT NULL;

-- For findEntitiesToProcess outbox polling
CREATE INDEX idx_lemline_listener_events_outbox_poll
    ON lemline_listener_events (outbox_delayed_until)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Comments for documentation
COMMENT ON TABLE lemline_listener_events IS 'CloudEvents for listeners with foreach outbox support';
COMMENT ON COLUMN lemline_listener_events.filter_index IS 'Filter index that matched (part of PK, defaults to 0)';
COMMENT ON COLUMN lemline_listener_events.sort_key IS 'Auto-increment sort key for deterministic ordering';
COMMENT ON COLUMN lemline_listener_events.event_id IS 'CloudEvent ID (part of composite PK)';
COMMENT ON COLUMN lemline_listener_events.event IS 'CloudEvent data (JSON)';
COMMENT ON COLUMN lemline_listener_events.foreach_completed IS 'Whether foreach.do has completed (for efficient indexing)';
COMMENT ON COLUMN lemline_listener_events.foreach_output IS 'Output from foreach.do iteration (JSON)';
COMMENT ON COLUMN lemline_listener_events.outbox_scheduled_for IS 'When this event was inserted';
COMMENT ON COLUMN lemline_listener_events.outbox_delayed_until IS 'NULL = waiting for FIFO turn, NOT NULL = ready for processing';
COMMENT ON COLUMN lemline_listener_events.outbox_completed_at IS 'When foreach.do completed (or immediate if no foreach)';
COMMENT ON COLUMN lemline_listener_events.created_at IS 'Timestamp for FIFO ordering';
