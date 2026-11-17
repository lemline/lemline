-- The unfiltered (namespace, type) index covers both lifecycle namespaces.
DROP INDEX CONCURRENTLY IF EXISTS idx_lemline_lifecycle_events_namespace_wftype_time;
