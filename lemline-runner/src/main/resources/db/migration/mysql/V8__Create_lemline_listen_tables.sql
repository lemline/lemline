-- Listeners table (outbox pattern)
-- Stores active listener instances waiting for CloudEvents
--
-- Listen task configuration (strategy, filters, readAs) is retrieved on-demand
-- from the cached workflow definition using (workflow_namespace, workflow_name, workflow_version, workflow_position)
CREATE TABLE lemline_listeners
(
    id                      BINARY(16) PRIMARY KEY,

    -- Workflow definition reference (for locating listen task in cached workflow)
    workflow_namespace      VARCHAR(255) NOT NULL,
    workflow_name           VARCHAR(255) NOT NULL,
    workflow_version        VARCHAR(255) NOT NULL,

    -- Workflow instance information
    workflow_id             BINARY(16)   NOT NULL,
    workflow_position       TEXT         NOT NULL,
    workflow_state          MEDIUMTEXT   NOT NULL,

    -- Correlation state (for Mode 2: first-sets-baseline)
    correlation_values      TEXT,                    -- JSON map of correlation key -> baseline value

    -- Accumulated events (for ANY with until)
    accumulated_events      MEDIUMTEXT,              -- JSON array of matched events
    matched_filter_indices  TEXT,                    -- JSON array of matched filter indices (for ALL strategy)

    -- Timeout handling
    timeout_at              TIMESTAMP(6),

    -- Outbox fields
    outbox_scheduled_for    TIMESTAMP(6) NOT NULL,
    outbox_delayed_until    TIMESTAMP(6) NOT NULL,
    outbox_attempt_count    INTEGER      NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace MEDIUMTEXT,
    outbox_completed_at     TIMESTAMP(6),
    outbox_failed_at        TIMESTAMP(6),

    -- Timestamps
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;

-- Index for efficient lookup by workflow_id
CREATE INDEX idx_lemline_listeners_workflow_id
    ON lemline_listeners (workflow_id);

-- Index for efficient lookup by workflow info + position (for event routing)
-- Using prefixes to stay within MySQL's 3072 byte key limit (utf8mb4 = 4 bytes/char)
-- Reallocated: namespace/name/version are short, position can be long for nested workflows
CREATE INDEX idx_lemline_listeners_workflow_position
    ON lemline_listeners (workflow_namespace(50), workflow_name(100), workflow_version(20), workflow_position(500));

-- Index for timeout processing
CREATE INDEX idx_lemline_listeners_timeout
    ON lemline_listeners (timeout_at);

-- Index for outbox processing
CREATE INDEX idx_lemline_listeners_processing
    ON lemline_listeners (outbox_completed_at, outbox_failed_at, outbox_delayed_until);

-- Index for cleanup queries
CREATE INDEX idx_lemline_listeners_completed
    ON lemline_listeners (outbox_completed_at);
