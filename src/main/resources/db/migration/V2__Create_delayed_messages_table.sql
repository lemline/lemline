CREATE TABLE delayed_messages
(
    id            SERIAL PRIMARY KEY,
    message       TEXT        NOT NULL,
    status        VARCHAR(20) NOT NULL,
    delayed_until TIMESTAMP   NOT NULL,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    last_error    TEXT,
    version       BIGINT      NOT NULL DEFAULT 0
);

-- Create a single combined index for our main query pattern
CREATE INDEX idx_delayed_ready ON delayed_messages (status, delayed_until, attempt_count);