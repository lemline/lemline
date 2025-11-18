-- Remove outbox-related columns from lemline_parents table
-- ParentWaitingModel no longer extends OutboxModel, so these columns are not needed

-- Drop the index that references outbox columns
DROP INDEX IF EXISTS idx_lemline_parents_status_delayed_until ON lemline_parents;

-- Drop outbox columns
ALTER TABLE lemline_parents DROP COLUMN IF EXISTS outbox_status;
ALTER TABLE lemline_parents DROP COLUMN IF EXISTS outbox_scheduled_for;
ALTER TABLE lemline_parents DROP COLUMN IF EXISTS outbox_delayed_until;
ALTER TABLE lemline_parents DROP COLUMN IF EXISTS outbox_attempt_count;
ALTER TABLE lemline_parents DROP COLUMN IF EXISTS outbox_error_class;
ALTER TABLE lemline_parents DROP COLUMN IF EXISTS outbox_error_message;
ALTER TABLE lemline_parents DROP COLUMN IF EXISTS outbox_error_stacktrace;
