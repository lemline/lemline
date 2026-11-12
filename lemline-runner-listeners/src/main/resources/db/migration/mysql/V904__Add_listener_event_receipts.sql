-- SHA-256 indexes the length-prefixed source + id; full values verify exact identity.
-- Retain receipts until the owning listener is deleted, independently of event cleanup.
CREATE TABLE lemline_listener_receipts (
    listener_id BINARY(16) NOT NULL,
    event_key VARCHAR(64) NOT NULL,
    event_source LONGTEXT NOT NULL,
    event_id LONGTEXT NOT NULL,
    delivery_token VARCHAR(36) NOT NULL,
    PRIMARY KEY (listener_id, event_key),
    FOREIGN KEY (listener_id) REFERENCES lemline_listeners(id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Batch queries select the receipts claimed by the current delivery transaction.
CREATE INDEX idx_listener_receipts_delivery
    ON lemline_listener_receipts (listener_id, delivery_token);
