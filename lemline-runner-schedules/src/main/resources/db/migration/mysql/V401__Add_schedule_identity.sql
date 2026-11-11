-- Each outbox row is an occurrence; retain a stable identity across the recurrence chain.
ALTER TABLE lemline_schedules ADD COLUMN schedule_id BINARY(16) AFTER id;
UPDATE lemline_schedules SET schedule_id = id;
ALTER TABLE lemline_schedules MODIFY COLUMN schedule_id BINARY(16) NOT NULL;
CREATE INDEX idx_lemline_schedules_schedule_id ON lemline_schedules (schedule_id);
