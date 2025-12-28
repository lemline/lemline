-- Listener events table (following standard outbox pattern)
-- Stores CloudEvents for ALL listeners (not just accumulating strategies)
-- Also serves as the foreach outbox for sequential event processing
-- Uses composite primary key (listener_id, event_id, filter_index)
-- This allows the same CloudEvent to satisfy multiple filters in an ALL strategy
CREATE TABLE lemline_listener_events
(
    -- Reference to parent listener (CASCADE delete for automatic cleanup)
    listener_id             BINARY(16)   NOT NULL,

    -- CloudEvent ID (from the CloudEvent spec 'id' field)
    -- Part of composite PK for natural idempotency
    event_id                VARCHAR(255) NOT NULL,

    -- Filter index that matched (for ALL strategy completion check)
    -- Defaults to 0 for ONE/ANY strategies (single event per listener)
    -- Part of composite PK to allow same event to match multiple filters
    filter_index            INT,

    -- CloudEvent data as JSON string
    event                   MEDIUMTEXT   NOT NULL,

    -- Auto-increment sort key for deterministic ordering
    sort_key                BIGINT       NOT NULL AUTO_INCREMENT,

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

    -- Unique key for auto-increment sort_key (required by MySQL)
    UNIQUE KEY uk_lemline_listener_events_sort_key (sort_key),

    -- Foreign key with CASCADE delete
    CONSTRAINT fk_listener_events_listener
        FOREIGN KEY (listener_id) REFERENCES lemline_listeners (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;

-- Unique index replacing PRIMARY KEY (listener_id, event_id, filter_index)
-- Natural idempotency: same CloudEvent + filter combination cannot be inserted twice
-- Allows same event to satisfy multiple filters in ALL strategy
CREATE UNIQUE INDEX idx_lemline_listener_events_pk
    ON lemline_listener_events (listener_id, event_id, filter_index);

-- Index for finding events by listener ordered by arrival time
CREATE INDEX idx_lemline_listener_events_listener
    ON lemline_listener_events (listener_id, created_at);

-- Unique constraint for ALL strategy idempotency (one event per filter per listener)
-- For ONE/ANY strategies, filter_index defaults to 0, ensuring only one event is stored
-- For ANY+Until strategies, filter_index is NULL, allowing multiple events to accumulate
CREATE UNIQUE INDEX idx_lemline_listener_events_filter_unique
    ON lemline_listener_events (listener_id, filter_index);

-- Index for cleanup
CREATE INDEX idx_lemline_listener_events_cleanup
    ON lemline_listener_events (cleanup_after);

-- Indexes for markReadyForForeach FIFO queue processing
-- 1) Pending head selection (covers WHERE + GROUP BY + MIN + join back)
CREATE INDEX idx_lemline_listener_events_pending_head
    ON lemline_listener_events (listener_id, foreach_completed, outbox_delayed_until, sort_key);

-- 2) Blocker existence per listener (for NOT EXISTS check)
CREATE INDEX idx_lemline_listener_events_blocker
    ON lemline_listener_events (listener_id, foreach_completed, outbox_delayed_until);

-- 3) Ready row polling (for findEntitiesToProcess)
CREATE INDEX idx_lemline_listener_events_ready_poll
    ON lemline_listener_events (foreach_completed, outbox_delayed_until, sort_key);
