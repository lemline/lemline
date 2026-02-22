CREATE TABLE IF NOT EXISTS lemline_lifecycle_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    source TEXT NOT NULL,
    type VARCHAR(255) NOT NULL,
    specversion VARCHAR(16) NOT NULL,
    subject TEXT NULL,
    event_time TIMESTAMPTZ NULL,
    datacontenttype VARCHAR(255) NULL,
    dataschema TEXT NULL,
    lemline_workflow_id UUID NULL,
    lemline_workflow_namespace VARCHAR(255) NULL,
    lemline_workflow_name VARCHAR(255) NULL,
    lemline_workflow_version VARCHAR(255) NULL,
    payload JSONB NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_lemline_lifecycle_events_source_event_id UNIQUE (source, event_id)
);

CREATE INDEX IF NOT EXISTS idx_lemline_lifecycle_events_event_time
    ON lemline_lifecycle_events (event_time);

CREATE INDEX IF NOT EXISTS idx_lemline_lifecycle_events_type
    ON lemline_lifecycle_events (type);

CREATE INDEX IF NOT EXISTS idx_lemline_lifecycle_events_workflow_name_version
    ON lemline_lifecycle_events (lemline_workflow_name, lemline_workflow_version);

CREATE INDEX IF NOT EXISTS idx_lemline_lifecycle_events_workflow_id
    ON lemline_lifecycle_events (lemline_workflow_id);
