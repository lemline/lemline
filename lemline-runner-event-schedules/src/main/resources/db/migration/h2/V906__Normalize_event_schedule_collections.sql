-- The nullable legacy payload is converted transactionally under the definition lock.
ALTER TABLE lemline_event_schedule_state ALTER COLUMN collections DROP NOT NULL;

CREATE TABLE lemline_event_schedule_collections (
    id UUID PRIMARY KEY,
    state_id UUID NOT NULL,
    collection_index BIGINT NOT NULL,
    correlations CLOB NOT NULL,
    matched_filters CLOB NOT NULL,
    until_filters CLOB NOT NULL,
    event_count BIGINT NOT NULL,
    UNIQUE (state_id, collection_index),
    FOREIGN KEY (state_id) REFERENCES lemline_event_schedule_state(id) ON DELETE CASCADE
);

CREATE TABLE lemline_event_schedule_events (
    collection_id UUID NOT NULL,
    event_index BIGINT NOT NULL,
    event_source CLOB NOT NULL,
    event_id CLOB NOT NULL,
    event CLOB NOT NULL,
    PRIMARY KEY (collection_id, event_index),
    FOREIGN KEY (collection_id) REFERENCES lemline_event_schedule_collections(id) ON DELETE CASCADE
);
