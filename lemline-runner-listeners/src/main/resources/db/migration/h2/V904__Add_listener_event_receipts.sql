-- SHA-256 indexes the length-prefixed source + id; full values verify exact identity.
-- Retain receipts until the owning listener is deleted, independently of event cleanup.
CREATE TABLE lemline_listener_receipts (
    listener_id UUID NOT NULL,
    event_key VARCHAR(64) NOT NULL,
    event_source CLOB NOT NULL,
    event_id CLOB NOT NULL,
    delivery_token VARCHAR(36) NOT NULL,
    PRIMARY KEY (listener_id, event_key),
    FOREIGN KEY (listener_id) REFERENCES lemline_listeners(id) ON DELETE CASCADE
);

-- Batch queries select the receipts claimed by the current delivery transaction.
CREATE INDEX idx_listener_receipts_delivery
    ON lemline_listener_receipts (listener_id, delivery_token);
