-- The existing unfiltered index covers both lifecycle event namespaces in H2.
CREATE INDEX IF NOT EXISTS idx_lemline_lifecycle_events_namespace_wftype_time
    ON public.lemline_lifecycle_events (lemline_workflow_namespace, type, event_time DESC);
