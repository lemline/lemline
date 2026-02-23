CREATE INDEX IF NOT EXISTS idx_lemline_lifecycle_events_namespace_type
    ON lemline_lifecycle_events (lemline_workflow_namespace, type);

CREATE INDEX IF NOT EXISTS idx_lemline_lifecycle_events_namespace_wftype_time
    ON lemline_lifecycle_events (lemline_workflow_namespace, type, event_time DESC)
    WHERE type LIKE 'io.serverlessworkflow.workflow.%';
