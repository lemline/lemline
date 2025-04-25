CREATE TABLE retries
(
    id            VARCHAR(36) PRIMARY KEY,
    message       TEXT        NOT NULL,
    status        VARCHAR(20) NOT NULL,
    delayed_until TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    last_error    TEXT,
    version       BIGINT      NOT NULL DEFAULT 0
);

-- Create a single combined index for our main query pattern
CREATE INDEX idx_retries_ready ON retries (status, delayed_until, attempt_count);