-- Listener events table (for ALL and ANY+until strategies)
-- Stores accumulated CloudEvents for listeners that need multiple events
-- Also serves as an outbox for foreach event processing
CREATE TABLE lemline_listener_events
(
    id                      BINARY(16) PRIMARY KEY,

    -- Reference to parent listener (CASCADE delete for automatic cleanup)
    listener_id             BINARY(16)   NOT NULL,

    -- Filter index: explicit for ALL strategy (0, 1, 2...), NULL for ANY+until
    filter_index            INT,

    -- CloudEvent ID for idempotency (prevents duplicate events on retry)
    -- Used for ANY+until strategy to ensure same CloudEvent isn't added twice
    cloudevent_id           VARCHAR(255),

    -- CloudEvent data
    event                   MEDIUMTEXT   NOT NULL,

    -- Foreach iteration tracking
    iteration_index         INT,
    iteration_output        MEDIUMTEXT,

    -- Outbox fields for foreach processing
    outbox_scheduled_for    TIMESTAMP(6),
    outbox_delayed_until    TIMESTAMP(6),
    outbox_attempt_count    INT          NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace MEDIUMTEXT,
    outbox_completed_at     TIMESTAMP(6),
    outbox_failed_at        TIMESTAMP(6),

    -- Timestamps
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              TIMESTAMP(6),

    -- Ensure one event per filter index per listener (protects ALL strategy)
    -- NULL values are excluded from UNIQUE constraint
    UNIQUE KEY (listener_id, filter_index),

    -- Ensure same CloudEvent isn't added twice for ANY+until strategy
    UNIQUE KEY (listener_id, cloudevent_id),

    -- Foreign key with CASCADE delete
    CONSTRAINT fk_listener_events_listener
        FOREIGN KEY (listener_id) REFERENCES lemline_listeners (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;

-- Index for efficient lookup by listener_id (covered by UNIQUE KEY)

-- Index for foreach outbox processing
-- Only includes events that have outbox enabled (outbox_scheduled_for IS NOT NULL)
CREATE INDEX idx_lemline_listener_events_outbox
    ON lemline_listener_events (outbox_delayed_until);
