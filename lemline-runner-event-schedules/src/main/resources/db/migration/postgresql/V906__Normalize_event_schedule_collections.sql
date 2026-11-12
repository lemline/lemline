-- The nullable legacy payload is converted transactionally under the definition lock.
ALTER TABLE lemline_event_schedule_state ALTER COLUMN collections DROP NOT NULL;

CREATE TABLE lemline_event_schedule_collections (
    id UUID PRIMARY KEY,
    state_id UUID NOT NULL,
    collection_index BIGINT NOT NULL,
    correlations TEXT NOT NULL,
    matched_filters TEXT NOT NULL,
    until_filters TEXT NOT NULL,
    event_count BIGINT NOT NULL,
    UNIQUE (state_id, collection_index),
    FOREIGN KEY (state_id) REFERENCES lemline_event_schedule_state(id) ON DELETE CASCADE
);

CREATE TABLE lemline_event_schedule_events (
    collection_id UUID NOT NULL,
    event_index BIGINT NOT NULL,
    event_source TEXT NOT NULL,
    event_id TEXT NOT NULL,
    event TEXT NOT NULL,
    PRIMARY KEY (collection_id, event_index),
    FOREIGN KEY (collection_id) REFERENCES lemline_event_schedule_collections(id) ON DELETE CASCADE
);
