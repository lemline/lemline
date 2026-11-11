-- Each outbox row is an occurrence; retain a stable identity across the recurrence chain.
ALTER TABLE lemline_schedules ADD COLUMN schedule_id UUID AFTER id;
UPDATE lemline_schedules SET schedule_id = id;
ALTER TABLE lemline_schedules ALTER COLUMN schedule_id SET NOT NULL;
CREATE INDEX idx_lemline_schedules_schedule_id ON lemline_schedules (schedule_id);
