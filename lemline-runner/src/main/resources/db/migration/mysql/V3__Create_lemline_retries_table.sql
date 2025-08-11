-- Use the table name from com.lemline.runner.models.RetryModel
CREATE TABLE IF NOT EXISTS lemline_retries
(
    id            VARCHAR(36) PRIMARY KEY,
    message       MEDIUMTEXT  NOT NULL,
    status        VARCHAR(50) NOT NULL,
    delayed_until TIMESTAMP   NOT NULL,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    last_error    MEDIUMTEXT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX idx_lemline_retries_status_delayed_until ON lemline_retries (status, delayed_until);
