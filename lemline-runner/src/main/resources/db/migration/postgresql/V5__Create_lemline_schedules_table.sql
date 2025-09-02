-- Use the table name from com.lemline.runner.models.ScheduleModel
CREATE TABLE lemline_schedules
(
    id                   uuid PRIMARY KEY,
    workflow_id          uuid UNIQUE    NOT NULL,
    workflow_name        VARCHAR(255)   NOT NULL,
    workflow_version     VARCHAR(255)   NOT NULL,
    workflow_position    TEXT           NOT NULL,
    workflow_state       TEXT           NOT NULL,
    parent_id            uuid,
    outbox_status        VARCHAR(50)    NOT NULL,
    outbox_scheduled_for TIMESTAMPTZ(6),
    outbox_delayed_until TIMESTAMPTZ(6),
    outbox_attempt_count INTEGER        NOT NULL DEFAULT 0,
    outbox_last_error    TEXT,
    schedule_after       VARCHAR(255),
    schedule_every       VARCHAR(255),
    schedule_cron        VARCHAR(255),
    schedule_zone        VARCHAR(64),
    created_at           TIMESTAMPTZ(6) NOT NULL,
    updated_at           TIMESTAMPTZ(6)
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_workflow_id
    ON lemline_schedules (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_parent_id
    ON lemline_schedules (parent_id);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_status_delayed_until
    ON lemline_schedules (outbox_status, outbox_delayed_until);
