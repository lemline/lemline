-- PGMQ (PostgreSQL Message Queue) Schema
-- SQL-only implementation based on PGMQ v1.8.1
-- Source: https://github.com/tembo-io/pgmq/releases/tag/v1.8.1
-- Last synced: 2025-01-12
--
-- This file provides PGMQ functionality without requiring the PostgreSQL extension,
-- enabling compatibility with managed PostgreSQL services (RDS, Cloud SQL, Azure).

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

-- Table for notification throttling (used by enable_notify_insert/disable_notify_insert)
CREATE UNLOGGED TABLE IF NOT EXISTS pgmq.notify_insert_throttle (
    queue_name VARCHAR UNIQUE NOT NULL REFERENCES pgmq.meta(queue_name) ON DELETE CASCADE,
    throttle_interval_ms INTEGER DEFAULT 0,
    last_notified_at TIMESTAMP WITH TIME ZONE DEFAULT to_timestamp(0) NOT NULL
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
