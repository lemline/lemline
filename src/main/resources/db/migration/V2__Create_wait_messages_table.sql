CREATE TABLE wait_messages
(
    id            UUID PRIMARY KEY,
    message       TEXT        NOT NULL,
    status        VARCHAR(20) NOT NULL,
    delayed_until TIMESTAMP   NOT NULL,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    last_error    TEXT,
    version       BIGINT      NOT NULL DEFAULT 0
);

-- Create a single combined index for our main query pattern
CREATE INDEX idx_wait_ready ON wait_messages (status, delayed_until, attempt_count);