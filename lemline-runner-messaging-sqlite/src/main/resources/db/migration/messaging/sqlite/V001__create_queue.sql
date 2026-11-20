CREATE TABLE lemline_messages (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    queue        TEXT NOT NULL,
    message_id   TEXT NOT NULL,
    payload      BLOB NOT NULL,
    enqueued_at  INTEGER NOT NULL,
    available_at INTEGER NOT NULL,
    attempts     INTEGER NOT NULL DEFAULT 0,
    lease_id     TEXT,
    is_dead_letter INTEGER NOT NULL DEFAULT 0 CHECK (is_dead_letter IN (0, 1))
) STRICT;

CREATE UNIQUE INDEX lemline_messages_active_id
    ON lemline_messages (queue, message_id) WHERE is_dead_letter = 0;

CREATE INDEX lemline_messages_ready
    ON lemline_messages (queue, available_at, id);

CREATE INDEX lemline_messages_queue_head
    ON lemline_messages (queue, id);
