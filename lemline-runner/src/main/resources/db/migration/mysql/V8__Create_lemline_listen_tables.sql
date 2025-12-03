-- Listen Task Definitions table
-- Stores listen task definitions extracted from workflow definitions
-- One row per listen task in a workflow
CREATE TABLE lemline_definition_listens
(
    id                 BINARY(16) PRIMARY KEY,
    workflow_namespace VARCHAR(255) NOT NULL,
    workflow_name      VARCHAR(255) NOT NULL,
    workflow_version   VARCHAR(255) NOT NULL,

    -- Listen task location in workflow
    node_position      TEXT         NOT NULL,

    -- Listen task configuration
    strategy           VARCHAR(10)  NOT NULL, -- 'ONE', 'ANY', 'ALL'
    read_mode          VARCHAR(10)  NOT NULL, -- 'DATA', 'ENVELOPE', 'RAW'

    -- Timestamps
    created_at         TIMESTAMP(6) NOT NULL,
    updated_at         TIMESTAMP(6),

    -- Foreign key to definitions table
    CONSTRAINT fk_definition_listens_definition
        FOREIGN KEY (workflow_namespace, workflow_name, workflow_version)
            REFERENCES lemline_definitions (namespace, name, version)
            ON DELETE CASCADE,

    -- Unique constraint on definition + position (one listen task per position)
    -- Using prefixes to stay within MySQL's 3072 byte key limit (utf8mb4 = 4 bytes/char)
    CONSTRAINT uk_definition_listens_position
        UNIQUE (workflow_namespace(191), workflow_name(191), workflow_version(100), node_position(100))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;

-- Index for efficient lookup by definition
CREATE INDEX idx_lemline_definition_listens_definition
    ON lemline_definition_listens (workflow_namespace, workflow_name, workflow_version);

-- Listen Filter Definitions table
-- Stores event filters for each listen task
-- One row per event filter in a listen task
CREATE TABLE lemline_definition_listen_filters
(
    id                    BINARY(16) PRIMARY KEY,

    -- Reference to parent listen task
    listen_id             BINARY(16)   NOT NULL,

    -- Filter index within the listen task (for ALL strategy ordering)
    filter_index          INTEGER      NOT NULL,

    -- Event matching criteria (CloudEvent attributes)
    -- Values can be literals (for exact match) or expressions (for runtime evaluation)
    event_id              VARCHAR(255),
    event_type            VARCHAR(255),
    event_source          TEXT,
    event_subject         VARCHAR(255),
    event_datacontenttype VARCHAR(255),
    event_dataschema      TEXT,
    event_time            VARCHAR(255),
    event_data            TEXT,

    -- Serialized correlation definitions (JSON)
    correlations          TEXT,

    -- Timestamps
    created_at            TIMESTAMP(6) NOT NULL,
    updated_at            TIMESTAMP(6),

    -- Foreign key to listen task
    CONSTRAINT fk_definition_listen_filters_listen
        FOREIGN KEY (listen_id)
            REFERENCES lemline_definition_listens (id)
            ON DELETE CASCADE,

    -- Unique constraint on listen task + filter index
    CONSTRAINT uk_definition_listen_filters_index
        UNIQUE (listen_id, filter_index)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;

-- Index for efficient lookup by listen task
CREATE INDEX idx_lemline_definition_listen_filters_listen
    ON lemline_definition_listen_filters (listen_id);

-- Index for efficient lookup by event type (most common filter)
CREATE INDEX idx_lemline_definition_listen_filters_event_type
    ON lemline_definition_listen_filters (event_type);

-- Listeners table (outbox pattern)
-- Stores active listener instances waiting for events
CREATE TABLE lemline_listeners
(
    -- Primary key
    id                      BINARY(16) PRIMARY KEY,

    -- Reference to listen task definition
    listen_definition_id    BINARY(16)   NOT NULL,

    -- Workflow instance information
    workflow_id             BINARY(16)   NOT NULL,
    workflow_position       TEXT         NOT NULL,
    workflow_state          MEDIUMTEXT   NOT NULL,

    -- Correlation state (for Mode 2: first-sets-baseline)
    correlation_values      TEXT,                  -- JSON map of correlation key -> baseline value

    -- Accumulated events (for ANY with until)
    accumulated_events      MEDIUMTEXT,            -- JSON array of matched events
    matched_filter_indices  TEXT,                  -- JSON array of matched filter indices (for ALL strategy)

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
    created_at              TIMESTAMP(6) NOT NULL,
    updated_at              TIMESTAMP(6),

    -- Foreign key to listen definition
    CONSTRAINT fk_listeners_listen_definition
        FOREIGN KEY (listen_definition_id)
            REFERENCES lemline_definition_listens (id)
            ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;

-- Index for efficient lookup by workflow_id
CREATE INDEX idx_lemline_listeners_workflow_id
    ON lemline_listeners (workflow_id);

-- Index for efficient lookup by listen definition (for event routing)
CREATE INDEX idx_lemline_listeners_listen_definition
    ON lemline_listeners (listen_definition_id);

-- Index for timeout processing
CREATE INDEX idx_lemline_listeners_timeout
    ON lemline_listeners (timeout_at);

-- Index for outbox processing
CREATE INDEX idx_lemline_listeners_processing
    ON lemline_listeners (outbox_completed_at, outbox_failed_at, outbox_delayed_until);

-- Index for cleanup queries
CREATE INDEX idx_lemline_listeners_completed
    ON lemline_listeners (outbox_completed_at);
