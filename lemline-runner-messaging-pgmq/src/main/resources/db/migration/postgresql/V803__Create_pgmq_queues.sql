-- ============================================================================
-- LEMLINE PGMQ QUEUE CREATION
-- ============================================================================
-- Creates the default PGMQ queues used by Lemline for workflow messaging.
-- These queues are created at migration time so they exist before any
-- workflow commands try to use them.
--
-- Queues:
-- - lemline-commands: Workflow command messages (instance start, resume, etc.)
-- - lemline-events: Database event messages (waits, retries, parent notifications)
-- - lemline-cloudevents: External CloudEvents for listen tasks
-- - lemline-lifecycle-events: Workflow lifecycle events (created, completed, etc.)
-- ============================================================================

-- Create the commands queue
SELECT pgmq.create('lemline-commands');
SELECT lemline.create_dedup_index('lemline-commands');

-- Create the events queue
SELECT pgmq.create('lemline-events');
SELECT lemline.create_dedup_index('lemline-events');

-- Create the CloudEvents queue
SELECT pgmq.create('lemline-cloudevents');
SELECT lemline.create_dedup_index('lemline-cloudevents');

-- Create the lifecycle events queue
SELECT pgmq.create('lemline-lifecycle-events');
SELECT lemline.create_dedup_index('lemline-lifecycle-events');
