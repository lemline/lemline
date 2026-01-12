-- ============================================================================
-- LEMLINE-SPECIFIC PGMQ EXTENSIONS
-- ============================================================================
-- This file contains Lemline-specific functions that extend PGMQ.
-- These are NOT part of PGMQ core (V800, V801) and should be preserved
-- when updating PGMQ to newer versions.
--
-- Purpose: Message deduplication via unique index on headers->>'messageId'
-- ============================================================================

------------------------------------------------------------
-- Message Deduplication Index
------------------------------------------------------------

-- Creates a unique partial index on the messageId field within headers JSONB.
-- This prevents duplicate messages from being enqueued when the same
-- idempotent key (IDV7) is used.
--
-- The index is partial (WHERE clause) to:
-- 1. Only index messages that have a messageId header
-- 2. Allow messages without messageId (NULL headers or missing key)
--
-- Usage: Call this function after pgmq.create() to enable deduplication
-- Example: SELECT lemline.create_dedup_index('my_queue');

CREATE SCHEMA IF NOT EXISTS lemline;

CREATE OR REPLACE FUNCTION lemline.create_dedup_index(queue_name TEXT)
RETURNS void AS $$
DECLARE
    qtable TEXT := 'q_' || lower(queue_name);
    index_name TEXT := qtable || '_message_id_dedup_idx';
BEGIN
    -- Create unique partial index on headers->>'messageId'
    -- Only applies to messages with non-null messageId in headers
    EXECUTE FORMAT(
        $QUERY$
        CREATE UNIQUE INDEX IF NOT EXISTS %I ON pgmq.%I ((headers->>'messageId'))
        WHERE headers->>'messageId' IS NOT NULL
        $QUERY$,
        index_name, qtable
    );
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION lemline.create_dedup_index(TEXT) IS
    'Creates a unique index on headers->>''messageId'' for message deduplication. '
    'This is a Lemline-specific extension, not part of PGMQ core.';

-- Drop deduplication index (for cleanup if needed)
CREATE OR REPLACE FUNCTION lemline.drop_dedup_index(queue_name TEXT)
RETURNS void AS $$
DECLARE
    qtable TEXT := 'q_' || lower(queue_name);
    index_name TEXT := qtable || '_message_id_dedup_idx';
BEGIN
    EXECUTE FORMAT('DROP INDEX IF EXISTS pgmq.%I', index_name);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION lemline.drop_dedup_index(TEXT) IS
    'Drops the messageId deduplication index for a queue. '
    'This is a Lemline-specific extension, not part of PGMQ core.';
