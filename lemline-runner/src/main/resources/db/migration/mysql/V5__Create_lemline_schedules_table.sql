-- Use the table name from com.lemline.runner.models.ScheduleModel
CREATE TABLE lemline_schedules
(
    workflow_id   VARCHAR(36) PRIMARY KEY,
    cron          VARCHAR(255),
    after         VARCHAR(255),
    every         VARCHAR(255),
    message       TEXT        NOT NULL,
    status        VARCHAR(50) NOT NULL,
    scheduled_for TIMESTAMP,
    delayed_until TIMESTAMP,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    last_error    MEDIUMTEXT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Add index for status for more efficient queries
CREATE INDEX idx_lemline_schedules_status_delayed_until
    ON lemline_schedules (status, delayed_until);
