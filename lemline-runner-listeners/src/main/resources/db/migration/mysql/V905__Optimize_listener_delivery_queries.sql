-- Support token-first joins as well as per-listener receipt checks.
DROP INDEX idx_listener_receipts_delivery ON lemline_listener_receipts;
CREATE INDEX idx_listener_receipts_delivery ON lemline_listener_receipts (delivery_token, listener_id);

CREATE INDEX idx_lemline_listeners_open ON lemline_listeners (workflow_namespace(50), workflow_name(100), workflow_version(20), workflow_position(400), closed_at, id);

-- NULL identifies legacy rows, which remain readable without an eager payload backfill.
ALTER TABLE lemline_listener_events ADD COLUMN event_source LONGTEXT;

-- Match the correlated lookup's ordering without sorting the listener's history.
CREATE INDEX idx_listener_events_last ON lemline_listener_events (listener_id, event_index DESC);
