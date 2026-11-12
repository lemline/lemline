-- Support token-first joins as well as per-listener receipt checks.
DROP INDEX idx_listener_receipts_delivery;
CREATE INDEX idx_listener_receipts_delivery ON lemline_listener_receipts (delivery_token, listener_id);

CREATE INDEX idx_lemline_listeners_open ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position, closed_at, id);

-- NULL identifies legacy rows, which remain readable without an eager payload backfill.
ALTER TABLE lemline_listener_events ADD COLUMN event_source CLOB;

-- H2 does not use the ascending queue index for an efficient descending LIMIT lookup.
CREATE INDEX idx_listener_events_last ON lemline_listener_events (listener_id, event_index DESC);
