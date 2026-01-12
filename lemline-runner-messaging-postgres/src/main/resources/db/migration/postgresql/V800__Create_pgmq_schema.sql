-- PGMQ (PostgreSQL Message Queue) Schema
-- SQL-only implementation based on https://github.com/pgmq/pgmq
-- See: https://github.com/pgmq/pgmq/blob/main/INSTALLATION.md

------------------------------------------------------------
-- Schema and Tables
------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS pgmq;

-- Table where queues and metadata about them is stored
CREATE TABLE IF NOT EXISTS pgmq.meta (
    queue_name VARCHAR UNIQUE NOT NULL,
    is_partitioned BOOLEAN NOT NULL,
    is_unlogged BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL
);

-- Grant permission to pg_monitor to all tables and sequences
GRANT USAGE ON SCHEMA pgmq TO pg_monitor;
GRANT SELECT ON ALL TABLES IN SCHEMA pgmq TO pg_monitor;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA pgmq TO pg_monitor;
ALTER DEFAULT PRIVILEGES IN SCHEMA pgmq GRANT SELECT ON TABLES TO pg_monitor;
ALTER DEFAULT PRIVILEGES IN SCHEMA pgmq GRANT SELECT ON SEQUENCES TO pg_monitor;

------------------------------------------------------------
-- Types
------------------------------------------------------------

-- Message record type returned by pgmq functions
CREATE TYPE pgmq.message_record AS (
    msg_id BIGINT,
    read_ct INTEGER,
    enqueued_at TIMESTAMP WITH TIME ZONE,
    vt TIMESTAMP WITH TIME ZONE,
    message JSONB,
    headers JSONB
);

-- Queue record type for list_queues
CREATE TYPE pgmq.queue_record AS (
    queue_name VARCHAR,
    is_partitioned BOOLEAN,
    is_unlogged BOOLEAN,
    created_at TIMESTAMP WITH TIME ZONE
);

-- Metrics result type
CREATE TYPE pgmq.metrics_result AS (
    queue_name TEXT,
    queue_length BIGINT,
    newest_msg_age_sec INT,
    oldest_msg_age_sec INT,
    total_messages BIGINT,
    scrape_time TIMESTAMP WITH TIME ZONE,
    queue_visible_length BIGINT
);
