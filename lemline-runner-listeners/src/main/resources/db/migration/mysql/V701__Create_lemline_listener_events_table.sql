-- Listener events table (following standard outbox pattern)
-- Stores CloudEvents for ALL listeners (not just accumulating strategies)
-- Also serves as the foreach outbox for sequential event processing
CREATE TABLE lemline_listener_events
(
    -- Reference to parent listener (CASCADE delete for automatic cleanup)
    listener_id             BINARY(16)   NOT NULL,

    -- Filter index that matched (for ALL strategy completion check)
    -- Defaults to 0 for ONE/ANY strategies (single event per listener)
    -- Part of composite PK to allow same event to match multiple filters
    filter_index            INT,

    -- Per-listener order/index at which events are received (0, 1, 2... per listener)
    event_index                BIGINT       NOT NULL DEFAULT 0,

    -- CloudEvent ID (from the CloudEvent spec 'id' field)
    event_id                VARCHAR(255) NOT NULL,

    -- CloudEvent data as JSON string
    event                   MEDIUMTEXT   NOT NULL,

    -- Whether foreach.do has completed (efficient boolean for indexing)
    foreach_completed       BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Output from foreach.do iteration (captured after completion)
    foreach_output          MEDIUMTEXT,

    -- Standard outbox fields (for foreach processing via AbstractOutbox)
    -- outbox_scheduled_for: when this event was inserted (NULL for non-foreach events)
    -- outbox_delayed_until: NULL = waiting for FIFO turn, NOT NULL = ready for processing
    -- outbox_completed_at: foreach.do completed successfully (or no foreach, completed immediately)
    outbox_scheduled_for    TIMESTAMP(6),
    outbox_delayed_until    TIMESTAMP(6),
    outbox_attempt_count    INT          NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace MEDIUMTEXT,
    outbox_completed_at     TIMESTAMP(6),
    outbox_failed_at        TIMESTAMP(6),

    -- Cleanup
    cleanup_after           TIMESTAMP(6),

    -- Timestamps (created_at used for FIFO ordering)
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              TIMESTAMP(6),

    -- Foreign key with CASCADE delete
    CONSTRAINT fk_listener_events_listener
        FOREIGN KEY (listener_id) REFERENCES lemline_listeners (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;

-- Unique constraint for ALL strategy idempotency (one event per filter per listener)
-- For ONE/ANY strategies, filter_index defaults to 0, ensuring only one event is stored
-- For ANY+Until strategies, filter_index is NULL, allowing multiple events to accumulate
CREATE UNIQUE INDEX idx_lemline_listener_events_filter_unique
    ON lemline_listener_events (listener_id, filter_index);

-- Index for cleanup
CREATE INDEX idx_lemline_listener_events_cleanup
    ON lemline_listener_events (cleanup_after);

-- Unique constraint on (listener_id, event_index) for FIFO ordering per listener
CREATE UNIQUE INDEX idx_lemline_listener_events_listener_event_index
    ON lemline_listener_events (listener_id, event_index);

-- For markReadyForForeach FIFO queue processing
CREATE INDEX idx_lemline_listener_events_pending_head
    ON lemline_listener_events (listener_id, foreach_completed, outbox_delayed_until, event_index);

-- For findEntitiesToProcess outbox polling
CREATE INDEX idx_lemline_listener_events_outbox_poll
    ON lemline_listener_events (outbox_completed_at, outbox_failed_at, outbox_delayed_until);
