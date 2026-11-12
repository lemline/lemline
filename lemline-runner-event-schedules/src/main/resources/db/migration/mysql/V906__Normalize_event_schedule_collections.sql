-- The nullable legacy payload is converted transactionally under the definition lock.
ALTER TABLE lemline_event_schedule_state MODIFY collections LONGTEXT NULL;

CREATE TABLE lemline_event_schedule_collections (
    id BINARY(16) PRIMARY KEY,
    state_id BINARY(16) NOT NULL,
    collection_index BIGINT NOT NULL,
    correlations LONGTEXT NOT NULL,
    matched_filters LONGTEXT NOT NULL,
    until_filters LONGTEXT NOT NULL,
    event_count BIGINT NOT NULL,
    UNIQUE (state_id, collection_index),
    FOREIGN KEY (state_id) REFERENCES lemline_event_schedule_state(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

CREATE TABLE lemline_event_schedule_events (
    collection_id BINARY(16) NOT NULL,
    event_index BIGINT NOT NULL,
    event_source LONGTEXT NOT NULL,
    event_id LONGTEXT NOT NULL,
    event LONGTEXT NOT NULL,
    PRIMARY KEY (collection_id, event_index),
    FOREIGN KEY (collection_id) REFERENCES lemline_event_schedule_collections(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;
