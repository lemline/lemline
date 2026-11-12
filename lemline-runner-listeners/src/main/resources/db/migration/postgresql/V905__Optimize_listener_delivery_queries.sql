-- Support token-first joins as well as per-listener receipt checks.
DROP INDEX idx_listener_receipts_delivery;
CREATE INDEX idx_listener_receipts_delivery ON lemline_listener_receipts (delivery_token, listener_id);

CREATE INDEX idx_lemline_listeners_open ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position, id) WHERE closed_at IS NULL;

-- NULL identifies legacy rows, which remain readable without an eager payload backfill.
ALTER TABLE lemline_listener_events ADD COLUMN event_source TEXT;
