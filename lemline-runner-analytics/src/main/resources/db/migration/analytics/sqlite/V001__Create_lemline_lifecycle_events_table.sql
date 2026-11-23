CREATE TABLE lemline_lifecycle_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT COLLATE BINARY NOT NULL,
    source TEXT COLLATE BINARY NOT NULL,
    type TEXT NOT NULL,
    specversion TEXT NOT NULL,
    subject TEXT,
    event_time INTEGER,
    datacontenttype TEXT,
    dataschema TEXT,
    lemline_workflow_id TEXT,
    lemline_workflow_namespace TEXT,
    lemline_workflow_name TEXT,
    lemline_workflow_version TEXT,
    payload TEXT NOT NULL CHECK (json_valid(payload)),
    ingested_at INTEGER NOT NULL,
    CONSTRAINT uq_lemline_lifecycle_events_source_event_id UNIQUE (source, event_id)
) STRICT;

CREATE INDEX idx_lemline_lifecycle_events_event_time
    ON lemline_lifecycle_events (event_time);

CREATE INDEX idx_lemline_lifecycle_events_type
    ON lemline_lifecycle_events (type);

CREATE INDEX idx_lemline_lifecycle_events_workflow_name_version
    ON lemline_lifecycle_events (lemline_workflow_name, lemline_workflow_version);

CREATE INDEX idx_lemline_lifecycle_events_workflow_id
    ON lemline_lifecycle_events (lemline_workflow_id);

CREATE INDEX idx_lemline_lifecycle_events_namespace_wftype_time
    ON lemline_lifecycle_events (lemline_workflow_namespace, type, event_time DESC);
