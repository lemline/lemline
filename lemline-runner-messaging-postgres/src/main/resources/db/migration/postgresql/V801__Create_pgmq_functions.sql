-- PGMQ Core Functions
-- SQL-only implementation based on PGMQ v1.8.1
-- Source: https://github.com/tembo-io/pgmq/releases/tag/v1.8.1
-- Last synced: 2025-01-12
--
-- This file provides PGMQ functionality without requiring the PostgreSQL extension,
-- enabling compatibility with managed PostgreSQL services (RDS, Cloud SQL, Azure).
--
-- NOTE: Partitioned queue functions require pg_partman extension to be installed.

------------------------------------------------------------
-- Helper Functions
------------------------------------------------------------

-- Format table names and validate queue names
CREATE OR REPLACE FUNCTION pgmq.format_table_name(queue_name TEXT, prefix TEXT)
RETURNS TEXT AS $$
BEGIN
    IF queue_name ~ '\$|;|--|'''
    THEN
        RAISE EXCEPTION 'queue name contains invalid characters: $, ;, --, or \''';
    END IF;
    RETURN lower(prefix || '_' || queue_name);
END;
$$ LANGUAGE plpgsql;

-- Validate queue name length
CREATE OR REPLACE FUNCTION pgmq.validate_queue_name(queue_name TEXT)
RETURNS void AS $$
BEGIN
    IF length(queue_name) > 47 THEN
        RAISE EXCEPTION 'queue name is too long, maximum length is 47 characters';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Acquire advisory lock to prevent race conditions during queue creation
CREATE OR REPLACE FUNCTION pgmq.acquire_queue_lock(queue_name TEXT)
RETURNS void AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('pgmq.queue_' || queue_name));
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------
-- Queue Management Functions
------------------------------------------------------------

-- Create a non-partitioned queue
CREATE OR REPLACE FUNCTION pgmq.create_non_partitioned(queue_name TEXT)
RETURNS void AS $$
DECLARE
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
    atable TEXT := pgmq.format_table_name(queue_name, 'a');
BEGIN
    PERFORM pgmq.validate_queue_name(queue_name);
    PERFORM pgmq.acquire_queue_lock(queue_name);

    EXECUTE FORMAT(
        $QUERY$
        CREATE TABLE IF NOT EXISTS pgmq.%I (
            msg_id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
            read_ct INT DEFAULT 0 NOT NULL,
            enqueued_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
            vt TIMESTAMP WITH TIME ZONE NOT NULL,
            message JSONB,
            headers JSONB
        )
        $QUERY$,
        qtable
    );

    EXECUTE FORMAT(
        $QUERY$
        CREATE TABLE IF NOT EXISTS pgmq.%I (
            msg_id BIGINT PRIMARY KEY,
            read_ct INT DEFAULT 0 NOT NULL,
            enqueued_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
            archived_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
            vt TIMESTAMP WITH TIME ZONE NOT NULL,
            message JSONB,
            headers JSONB
        )
        $QUERY$,
        atable
    );

    EXECUTE FORMAT(
        $QUERY$
        CREATE INDEX IF NOT EXISTS %I ON pgmq.%I (vt ASC)
        $QUERY$,
        qtable || '_vt_idx', qtable
    );

    EXECUTE FORMAT(
        $QUERY$
        CREATE INDEX IF NOT EXISTS %I ON pgmq.%I (archived_at)
        $QUERY$,
        'archived_at_idx_' || queue_name, atable
    );

    EXECUTE FORMAT(
        $QUERY$
        INSERT INTO pgmq.meta (queue_name, is_partitioned, is_unlogged)
        VALUES (%L, false, false)
        ON CONFLICT DO NOTHING
        $QUERY$,
        queue_name
    );
END;
$$ LANGUAGE plpgsql;

-- Alias for create_non_partitioned
CREATE OR REPLACE FUNCTION pgmq.create(queue_name TEXT)
RETURNS void AS $$
BEGIN
    PERFORM pgmq.create_non_partitioned(queue_name);
END;
$$ LANGUAGE plpgsql;

-- Create an unlogged queue (faster but not crash-safe)
CREATE OR REPLACE FUNCTION pgmq.create_unlogged(queue_name TEXT)
RETURNS void AS $$
DECLARE
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
    atable TEXT := pgmq.format_table_name(queue_name, 'a');
BEGIN
    PERFORM pgmq.validate_queue_name(queue_name);
    PERFORM pgmq.acquire_queue_lock(queue_name);

    EXECUTE FORMAT(
        $QUERY$
        CREATE UNLOGGED TABLE IF NOT EXISTS pgmq.%I (
            msg_id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
            read_ct INT DEFAULT 0 NOT NULL,
            enqueued_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
            vt TIMESTAMP WITH TIME ZONE NOT NULL,
            message JSONB,
            headers JSONB
        )
        $QUERY$,
        qtable
    );

    EXECUTE FORMAT(
        $QUERY$
        CREATE TABLE IF NOT EXISTS pgmq.%I (
            msg_id BIGINT PRIMARY KEY,
            read_ct INT DEFAULT 0 NOT NULL,
            enqueued_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
            archived_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
            vt TIMESTAMP WITH TIME ZONE NOT NULL,
            message JSONB,
            headers JSONB
        )
        $QUERY$,
        atable
    );

    EXECUTE FORMAT(
        $QUERY$
        CREATE INDEX IF NOT EXISTS %I ON pgmq.%I (vt ASC)
        $QUERY$,
        qtable || '_vt_idx', qtable
    );

    EXECUTE FORMAT(
        $QUERY$
        CREATE INDEX IF NOT EXISTS %I ON pgmq.%I (archived_at)
        $QUERY$,
        'archived_at_idx_' || queue_name, atable
    );

    EXECUTE FORMAT(
        $QUERY$
        INSERT INTO pgmq.meta (queue_name, is_partitioned, is_unlogged)
        VALUES (%L, false, true)
        ON CONFLICT DO NOTHING
        $QUERY$,
        queue_name
    );
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------
-- Partitioned Queue Helper Functions (require pg_partman)
------------------------------------------------------------

-- Check if an extension exists
CREATE OR REPLACE FUNCTION pgmq._extension_exists(extension_name TEXT)
RETURNS BOOLEAN AS $$
    SELECT EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = extension_name
    );
$$ LANGUAGE sql;

-- Get the schema where pg_partman is installed
CREATE OR REPLACE FUNCTION pgmq._get_pg_partman_schema()
RETURNS TEXT AS $$
DECLARE
    schema_name TEXT;
BEGIN
    SELECT n.nspname INTO schema_name
    FROM pg_extension e
    JOIN pg_namespace n ON e.extnamespace = n.oid
    WHERE e.extname = 'pg_partman';

    IF schema_name IS NULL THEN
        RAISE EXCEPTION 'pg_partman extension is not installed';
    END IF;

    RETURN schema_name;
END;
$$ LANGUAGE plpgsql;

-- Get pg_partman major version
CREATE OR REPLACE FUNCTION pgmq._get_pg_partman_major_version()
RETURNS INTEGER AS $$
DECLARE
    version_string TEXT;
    major_version INTEGER;
BEGIN
    SELECT extversion INTO version_string
    FROM pg_extension
    WHERE extname = 'pg_partman';

    IF version_string IS NULL THEN
        RAISE EXCEPTION 'pg_partman extension is not installed';
    END IF;

    major_version := split_part(version_string, '.', 1)::INTEGER;
    RETURN major_version;
END;
$$ LANGUAGE plpgsql;

-- Ensure pg_partman is installed
CREATE OR REPLACE FUNCTION pgmq._ensure_pg_partman_installed()
RETURNS void AS $$
BEGIN
    IF NOT pgmq._extension_exists('pg_partman') THEN
        RAISE EXCEPTION 'pg_partman extension is required for partitioned queues. Install it with: CREATE EXTENSION pg_partman';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Get partition column based on interval
CREATE OR REPLACE FUNCTION pgmq._get_partition_col(partition_interval TEXT)
RETURNS TEXT AS $$
BEGIN
    IF partition_interval ~ '^[0-9]+$' THEN
        RETURN 'msg_id';
    ELSE
        RETURN 'enqueued_at';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Create a partitioned queue (requires pg_partman extension)
CREATE OR REPLACE FUNCTION pgmq.create_partitioned(
    queue_name TEXT,
    partition_interval TEXT DEFAULT '10000',
    retention_interval TEXT DEFAULT '100000'
)
RETURNS void AS $$
DECLARE
    partition_col TEXT;
    a_partition_col TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
    atable TEXT := pgmq.format_table_name(queue_name, 'a');
    fq_qtable TEXT := 'pgmq.' || qtable;
    fq_atable TEXT := 'pgmq.' || atable;
BEGIN
    PERFORM pgmq.validate_queue_name(queue_name);
    PERFORM pgmq.acquire_queue_lock(queue_name);
    PERFORM pgmq._ensure_pg_partman_installed();
    SELECT pgmq._get_partition_col(partition_interval) INTO partition_col;

    EXECUTE FORMAT(
        $QUERY$
        CREATE TABLE IF NOT EXISTS pgmq.%I (
            msg_id BIGINT GENERATED ALWAYS AS IDENTITY,
            read_ct INT DEFAULT 0 NOT NULL,
            enqueued_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
            vt TIMESTAMP WITH TIME ZONE NOT NULL,
            message JSONB,
            headers JSONB
        ) PARTITION BY RANGE (%I)
        $QUERY$,
        qtable, partition_col
    );

    EXECUTE FORMAT(
        $QUERY$
        SELECT %I.create_parent(
            p_parent_table := %L,
            p_control := %L,
            p_interval := %L,
            p_type := CASE
                WHEN pgmq._get_pg_partman_major_version() = 5 THEN 'range'
                ELSE 'native'
            END
        )
        $QUERY$,
        pgmq._get_pg_partman_schema(),
        fq_qtable,
        partition_col,
        partition_interval
    );

    EXECUTE FORMAT(
        $QUERY$
        CREATE INDEX IF NOT EXISTS %I ON pgmq.%I (%I)
        $QUERY$,
        qtable || '_part_idx', qtable, partition_col
    );

    EXECUTE FORMAT(
        $QUERY$
        UPDATE %I.part_config
        SET
            retention = %L,
            retention_keep_table = false,
            retention_keep_index = true,
            automatic_maintenance = 'on'
        WHERE parent_table = %L
        $QUERY$,
        pgmq._get_pg_partman_schema(),
        retention_interval,
        'pgmq.' || qtable
    );

    EXECUTE FORMAT(
        $QUERY$
        INSERT INTO pgmq.meta (queue_name, is_partitioned, is_unlogged)
        VALUES (%L, true, false)
        ON CONFLICT DO NOTHING
        $QUERY$,
        queue_name
    );

    IF partition_col = 'enqueued_at' THEN
        a_partition_col := 'archived_at';
    ELSE
        a_partition_col := partition_col;
    END IF;

    EXECUTE FORMAT(
        $QUERY$
        CREATE TABLE IF NOT EXISTS pgmq.%I (
            msg_id BIGINT NOT NULL,
            read_ct INT DEFAULT 0 NOT NULL,
            enqueued_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
            archived_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
            vt TIMESTAMP WITH TIME ZONE NOT NULL,
            message JSONB,
            headers JSONB
        ) PARTITION BY RANGE (%I)
        $QUERY$,
        atable, a_partition_col
    );

    EXECUTE FORMAT(
        $QUERY$
        SELECT %I.create_parent(
            p_parent_table := %L,
            p_control := %L,
            p_interval := %L,
            p_type := CASE
                WHEN pgmq._get_pg_partman_major_version() = 5 THEN 'range'
                ELSE 'native'
            END
        )
        $QUERY$,
        pgmq._get_pg_partman_schema(),
        fq_atable,
        a_partition_col,
        partition_interval
    );

    EXECUTE FORMAT(
        $QUERY$
        UPDATE %I.part_config
        SET
            retention = %L,
            retention_keep_table = false,
            retention_keep_index = true,
            automatic_maintenance = 'on'
        WHERE parent_table = %L
        $QUERY$,
        pgmq._get_pg_partman_schema(),
        retention_interval,
        'pgmq.' || atable
    );

    EXECUTE FORMAT(
        $QUERY$
        CREATE INDEX IF NOT EXISTS %I ON pgmq.%I (archived_at)
        $QUERY$,
        'archived_at_idx_' || queue_name, atable
    );
END;
$$ LANGUAGE plpgsql;

-- Drop a queue and its archive
CREATE OR REPLACE FUNCTION pgmq.drop_queue(queue_name TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
    atable TEXT := pgmq.format_table_name(queue_name, 'a');
BEGIN
    PERFORM pgmq.acquire_queue_lock(queue_name);

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_name = qtable AND table_schema = 'pgmq'
    ) THEN
        RAISE NOTICE 'pgmq queue `%` does not exist', queue_name;
        RETURN FALSE;
    END IF;

    EXECUTE FORMAT('DROP TABLE IF EXISTS pgmq.%I', qtable);
    EXECUTE FORMAT('DROP TABLE IF EXISTS pgmq.%I', atable);

    DELETE FROM pgmq.meta WHERE meta.queue_name = drop_queue.queue_name;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Purge all messages from a queue
CREATE OR REPLACE FUNCTION pgmq.purge_queue(queue_name TEXT)
RETURNS BIGINT AS $$
DECLARE
    deleted_count INTEGER;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
BEGIN
    EXECUTE format('SELECT count(*) FROM pgmq.%I', qtable) INTO deleted_count;
    EXECUTE format('TRUNCATE TABLE pgmq.%I', qtable);
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- List all queues
CREATE OR REPLACE FUNCTION pgmq.list_queues()
RETURNS SETOF pgmq.queue_record AS $$
BEGIN
    RETURN QUERY SELECT * FROM pgmq.meta;
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------
-- Message Send Functions
------------------------------------------------------------

-- Send: actual implementation with headers and delay timestamp
CREATE OR REPLACE FUNCTION pgmq.send(
    queue_name TEXT,
    msg JSONB,
    headers JSONB,
    delay TIMESTAMP WITH TIME ZONE
)
RETURNS SETOF BIGINT AS $$
DECLARE
    sql TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
BEGIN
    sql := FORMAT(
        $QUERY$
        INSERT INTO pgmq.%I (vt, message, headers)
        VALUES ($2, $1, $3)
        RETURNING msg_id
        $QUERY$,
        qtable
    );
    RETURN QUERY EXECUTE sql USING msg, delay, headers;
END;
$$ LANGUAGE plpgsql;

-- Send: 2 args, no delay or headers
CREATE OR REPLACE FUNCTION pgmq.send(queue_name TEXT, msg JSONB)
RETURNS SETOF BIGINT AS $$
    SELECT * FROM pgmq.send(queue_name, msg, NULL, clock_timestamp());
$$ LANGUAGE sql;

-- Send: 3 args with integer delay
CREATE OR REPLACE FUNCTION pgmq.send(queue_name TEXT, msg JSONB, delay INTEGER)
RETURNS SETOF BIGINT AS $$
    SELECT * FROM pgmq.send(queue_name, msg, NULL, clock_timestamp() + make_interval(secs => delay));
$$ LANGUAGE sql;

-- Send: 3 args with headers
CREATE OR REPLACE FUNCTION pgmq.send(queue_name TEXT, msg JSONB, headers JSONB)
RETURNS SETOF BIGINT AS $$
    SELECT * FROM pgmq.send(queue_name, msg, headers, clock_timestamp());
$$ LANGUAGE sql;

-- Send: 4 args with integer delay
CREATE OR REPLACE FUNCTION pgmq.send(queue_name TEXT, msg JSONB, headers JSONB, delay INTEGER)
RETURNS SETOF BIGINT AS $$
    SELECT * FROM pgmq.send(queue_name, msg, headers, clock_timestamp() + make_interval(secs => delay));
$$ LANGUAGE sql;

-- Send: 3 args with timestamp delay (no headers)
CREATE OR REPLACE FUNCTION pgmq.send(queue_name TEXT, msg JSONB, delay TIMESTAMP WITH TIME ZONE)
RETURNS SETOF BIGINT AS $$
    SELECT * FROM pgmq.send(queue_name, msg, NULL, delay);
$$ LANGUAGE sql;

------------------------------------------------------------
-- Message Send Batch Functions
------------------------------------------------------------

-- Send batch: actual implementation with headers and delay timestamp
CREATE OR REPLACE FUNCTION pgmq.send_batch(
    queue_name TEXT,
    msgs JSONB[],
    headers JSONB[],
    delay TIMESTAMP WITH TIME ZONE
)
RETURNS SETOF BIGINT AS $$
DECLARE
    sql TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
BEGIN
    sql := FORMAT(
        $QUERY$
        INSERT INTO pgmq.%I (vt, message, headers)
        SELECT $2, unnest($1), unnest(coalesce($3, ARRAY[]::jsonb[]))
        RETURNING msg_id
        $QUERY$,
        qtable
    );
    RETURN QUERY EXECUTE sql USING msgs, delay, headers;
END;
$$ LANGUAGE plpgsql;

-- Send batch: 2 args (messages only)
CREATE OR REPLACE FUNCTION pgmq.send_batch(queue_name TEXT, msgs JSONB[])
RETURNS SETOF BIGINT AS $$
    SELECT * FROM pgmq.send_batch(queue_name, msgs, NULL, clock_timestamp());
$$ LANGUAGE sql;

-- Send batch: 3 args with headers
CREATE OR REPLACE FUNCTION pgmq.send_batch(queue_name TEXT, msgs JSONB[], headers JSONB[])
RETURNS SETOF BIGINT AS $$
    SELECT * FROM pgmq.send_batch(queue_name, msgs, headers, clock_timestamp());
$$ LANGUAGE sql;

-- Send batch: 3 args with integer delay
CREATE OR REPLACE FUNCTION pgmq.send_batch(queue_name TEXT, msgs JSONB[], delay INTEGER)
RETURNS SETOF BIGINT AS $$
    SELECT * FROM pgmq.send_batch(queue_name, msgs, NULL, clock_timestamp() + make_interval(secs => delay));
$$ LANGUAGE sql;

-- Send batch: 3 args with timestamp delay
CREATE OR REPLACE FUNCTION pgmq.send_batch(queue_name TEXT, msgs JSONB[], delay TIMESTAMP WITH TIME ZONE)
RETURNS SETOF BIGINT AS $$
    SELECT * FROM pgmq.send_batch(queue_name, msgs, NULL, delay);
$$ LANGUAGE sql;

-- Send batch: 4 args with integer delay
CREATE OR REPLACE FUNCTION pgmq.send_batch(queue_name TEXT, msgs JSONB[], headers JSONB[], delay INTEGER)
RETURNS SETOF BIGINT AS $$
    SELECT * FROM pgmq.send_batch(queue_name, msgs, headers, clock_timestamp() + make_interval(secs => delay));
$$ LANGUAGE sql;

------------------------------------------------------------
-- Message Read Functions
------------------------------------------------------------

-- Read messages with visibility timeout
CREATE OR REPLACE FUNCTION pgmq.read(
    queue_name TEXT,
    vt INTEGER,
    qty INTEGER,
    conditional JSONB DEFAULT '{}'
)
RETURNS SETOF pgmq.message_record AS $$
DECLARE
    sql TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
BEGIN
    sql := FORMAT(
        $QUERY$
        WITH cte AS (
            SELECT msg_id
            FROM pgmq.%I
            WHERE vt <= clock_timestamp() AND CASE
                WHEN %L != '{}'::jsonb THEN (message @> %2$L)::integer
                ELSE 1
            END = 1
            ORDER BY msg_id ASC
            LIMIT $1
            FOR UPDATE SKIP LOCKED
        )
        UPDATE pgmq.%I m
        SET
            vt = clock_timestamp() + %L,
            read_ct = read_ct + 1
        FROM cte
        WHERE m.msg_id = cte.msg_id
        RETURNING m.msg_id, m.read_ct, m.enqueued_at, m.vt, m.message, m.headers
        $QUERY$,
        qtable, conditional, qtable, make_interval(secs => vt)
    );
    RETURN QUERY EXECUTE sql USING qty;
END;
$$ LANGUAGE plpgsql;

-- Pop: read and delete in one operation
CREATE OR REPLACE FUNCTION pgmq.pop(queue_name TEXT, qty INTEGER DEFAULT 1)
RETURNS SETOF pgmq.message_record AS $$
DECLARE
    sql TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
BEGIN
    sql := FORMAT(
        $QUERY$
        WITH cte AS (
            SELECT msg_id
            FROM pgmq.%I
            WHERE vt <= clock_timestamp()
            ORDER BY msg_id ASC
            LIMIT $1
            FOR UPDATE SKIP LOCKED
        )
        DELETE FROM pgmq.%I
        WHERE msg_id IN (SELECT msg_id FROM cte)
        RETURNING *
        $QUERY$,
        qtable, qtable
    );
    RETURN QUERY EXECUTE sql USING qty;
END;
$$ LANGUAGE plpgsql;

-- Read with polling: long-poll for messages with configurable timeout
CREATE OR REPLACE FUNCTION pgmq.read_with_poll(
    queue_name TEXT,
    vt INTEGER,
    qty INTEGER,
    max_poll_seconds INTEGER DEFAULT 5,
    poll_interval_ms INTEGER DEFAULT 100,
    conditional JSONB DEFAULT '{}'
)
RETURNS SETOF pgmq.message_record AS $$
DECLARE
    r pgmq.message_record;
    stop_at TIMESTAMP;
    sql TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
BEGIN
    stop_at := clock_timestamp() + make_interval(secs => max_poll_seconds);
    LOOP
        IF (SELECT clock_timestamp() >= stop_at) THEN
            RETURN;
        END IF;

        sql := FORMAT(
            $QUERY$
            WITH cte AS (
                SELECT msg_id
                FROM pgmq.%I
                WHERE vt <= clock_timestamp() AND CASE
                    WHEN %L != '{}'::jsonb THEN (message @> %2$L)::integer
                    ELSE 1
                END = 1
                ORDER BY msg_id ASC
                LIMIT $1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE pgmq.%I m
            SET
                vt = clock_timestamp() + %L,
                read_ct = read_ct + 1
            FROM cte
            WHERE m.msg_id = cte.msg_id
            RETURNING m.msg_id, m.read_ct, m.enqueued_at, m.vt, m.message, m.headers
            $QUERY$,
            qtable, conditional, qtable, make_interval(secs => vt)
        );

        FOR r IN EXECUTE sql USING qty
        LOOP
            RETURN NEXT r;
        END LOOP;

        IF FOUND THEN
            RETURN;
        ELSE
            PERFORM pg_sleep(poll_interval_ms::numeric / 1000);
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------
-- Message Delete/Archive Functions
------------------------------------------------------------

-- Delete a single message
CREATE OR REPLACE FUNCTION pgmq.delete(queue_name TEXT, msg_id BIGINT)
RETURNS BOOLEAN AS $$
DECLARE
    sql TEXT;
    result BIGINT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
BEGIN
    sql := FORMAT(
        $QUERY$
        DELETE FROM pgmq.%I WHERE msg_id = $1 RETURNING msg_id
        $QUERY$,
        qtable
    );
    EXECUTE sql USING msg_id INTO result;
    RETURN NOT (result IS NULL);
END;
$$ LANGUAGE plpgsql;

-- Delete multiple messages
CREATE OR REPLACE FUNCTION pgmq.delete(queue_name TEXT, msg_ids BIGINT[])
RETURNS SETOF BIGINT AS $$
DECLARE
    sql TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
BEGIN
    sql := FORMAT(
        $QUERY$
        DELETE FROM pgmq.%I WHERE msg_id = ANY($1) RETURNING msg_id
        $QUERY$,
        qtable
    );
    RETURN QUERY EXECUTE sql USING msg_ids;
END;
$$ LANGUAGE plpgsql;

-- Archive a single message
CREATE OR REPLACE FUNCTION pgmq.archive(queue_name TEXT, msg_id BIGINT)
RETURNS BOOLEAN AS $$
DECLARE
    sql TEXT;
    result BIGINT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
    atable TEXT := pgmq.format_table_name(queue_name, 'a');
BEGIN
    sql := FORMAT(
        $QUERY$
        WITH archived AS (
            DELETE FROM pgmq.%I WHERE msg_id = $1
            RETURNING msg_id, vt, read_ct, enqueued_at, message, headers
        )
        INSERT INTO pgmq.%I (msg_id, vt, read_ct, enqueued_at, message, headers)
        SELECT msg_id, vt, read_ct, enqueued_at, message, headers FROM archived
        RETURNING msg_id
        $QUERY$,
        qtable, atable
    );
    EXECUTE sql USING msg_id INTO result;
    RETURN NOT (result IS NULL);
END;
$$ LANGUAGE plpgsql;

-- Archive multiple messages
CREATE OR REPLACE FUNCTION pgmq.archive(queue_name TEXT, msg_ids BIGINT[])
RETURNS SETOF BIGINT AS $$
DECLARE
    sql TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
    atable TEXT := pgmq.format_table_name(queue_name, 'a');
BEGIN
    sql := FORMAT(
        $QUERY$
        WITH archived AS (
            DELETE FROM pgmq.%I WHERE msg_id = ANY($1)
            RETURNING msg_id, vt, read_ct, enqueued_at, message, headers
        )
        INSERT INTO pgmq.%I (msg_id, vt, read_ct, enqueued_at, message, headers)
        SELECT msg_id, vt, read_ct, enqueued_at, message, headers FROM archived
        RETURNING msg_id
        $QUERY$,
        qtable, atable
    );
    RETURN QUERY EXECUTE sql USING msg_ids;
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------
-- Visibility Timeout Functions
------------------------------------------------------------

-- Set visibility timeout for a single message
CREATE OR REPLACE FUNCTION pgmq.set_vt(queue_name TEXT, msg_id BIGINT, vt INTEGER)
RETURNS SETOF pgmq.message_record AS $$
DECLARE
    sql TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
BEGIN
    sql := FORMAT(
        $QUERY$
        UPDATE pgmq.%I
        SET vt = (clock_timestamp() + %L)
        WHERE msg_id = %L
        RETURNING *
        $QUERY$,
        qtable, make_interval(secs => vt), msg_id
    );
    RETURN QUERY EXECUTE sql;
END;
$$ LANGUAGE plpgsql;

-- Set visibility timeout for multiple messages
CREATE OR REPLACE FUNCTION pgmq.set_vt(queue_name TEXT, msg_ids BIGINT[], vt INTEGER)
RETURNS SETOF pgmq.message_record AS $$
DECLARE
    sql TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
    new_vt TIMESTAMP WITH TIME ZONE;
BEGIN
    new_vt := clock_timestamp() + make_interval(secs => vt);
    sql := FORMAT(
        $QUERY$
        UPDATE pgmq.%I
        SET vt = $1
        WHERE msg_id = ANY($2)
        RETURNING *
        $QUERY$,
        qtable
    );
    RETURN QUERY EXECUTE sql USING new_vt, msg_ids;
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------
-- Metrics Functions
------------------------------------------------------------

-- Get metrics for a single queue
CREATE OR REPLACE FUNCTION pgmq.metrics(queue_name TEXT)
RETURNS pgmq.metrics_result AS $$
DECLARE
    result_row pgmq.metrics_result;
    query TEXT;
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
BEGIN
    query := FORMAT(
        $QUERY$
        WITH q_summary AS (
            SELECT
                count(*) as queue_length,
                count(CASE WHEN vt <= NOW() THEN 1 END) as queue_visible_length,
                EXTRACT(epoch FROM (NOW() - max(enqueued_at)))::int as newest_msg_age_sec,
                EXTRACT(epoch FROM (NOW() - min(enqueued_at)))::int as oldest_msg_age_sec,
                NOW() as scrape_time
            FROM pgmq.%I
        ),
        all_metrics AS (
            SELECT CASE
                WHEN is_called THEN last_value ELSE 0
                END as total_messages
            FROM pgmq.%I
        )
        SELECT
            %L as queue_name,
            q_summary.queue_length,
            q_summary.newest_msg_age_sec,
            q_summary.oldest_msg_age_sec,
            all_metrics.total_messages,
            q_summary.scrape_time,
            q_summary.queue_visible_length
        FROM q_summary, all_metrics
        $QUERY$,
        qtable, qtable || '_msg_id_seq', queue_name
    );
    EXECUTE query INTO result_row;
    RETURN result_row;
END;
$$ LANGUAGE plpgsql;

-- Get metrics for all queues
CREATE OR REPLACE FUNCTION pgmq.metrics_all()
RETURNS SETOF pgmq.metrics_result AS $$
DECLARE
    row_name RECORD;
    result_row pgmq.metrics_result;
BEGIN
    FOR row_name IN SELECT queue_name FROM pgmq.meta LOOP
        result_row := pgmq.metrics(row_name.queue_name);
        RETURN NEXT result_row;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------
-- Notification Functions
------------------------------------------------------------

-- Trigger function to notify listeners when messages are inserted
CREATE OR REPLACE FUNCTION pgmq.notify_queue_listeners()
RETURNS TRIGGER AS $$
DECLARE
    queue_name_extracted TEXT;
    updated_count INTEGER;
BEGIN
    queue_name_extracted := substring(TG_TABLE_NAME from 3);

    UPDATE pgmq.notify_insert_throttle
    SET last_notified_at = clock_timestamp()
    WHERE queue_name = queue_name_extracted
      AND (
          throttle_interval_ms = 0
          OR clock_timestamp() - last_notified_at >=
             (throttle_interval_ms * INTERVAL '1 millisecond')
      );

    GET DIAGNOSTICS updated_count = ROW_COUNT;

    IF updated_count > 0 THEN
        PERFORM pg_notify('pgmq.' || TG_TABLE_NAME || '.' || TG_OP, NULL);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Enable notifications on message insert for a queue
CREATE OR REPLACE FUNCTION pgmq.enable_notify_insert(queue_name TEXT, throttle_interval_ms INTEGER DEFAULT 250)
RETURNS void AS $$
DECLARE
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
    v_queue_name TEXT := queue_name;
    v_throttle_interval_ms INTEGER := throttle_interval_ms;
BEGIN
    IF v_throttle_interval_ms < 0 THEN
        RAISE EXCEPTION 'throttle_interval_ms must be non-negative';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'pgmq' AND table_name = qtable) THEN
        RAISE EXCEPTION 'Queue "%" does not exist. Create it first using pgmq.create()', v_queue_name;
    END IF;

    PERFORM pgmq.disable_notify_insert(v_queue_name);

    INSERT INTO pgmq.notify_insert_throttle (queue_name, throttle_interval_ms)
    VALUES (v_queue_name, v_throttle_interval_ms)
    ON CONFLICT ON CONSTRAINT notify_insert_throttle_queue_name_key DO UPDATE
        SET throttle_interval_ms = EXCLUDED.throttle_interval_ms,
            last_notified_at = to_timestamp(0);

    EXECUTE FORMAT(
        $QUERY$
        CREATE CONSTRAINT TRIGGER trigger_notify_queue_insert_listeners
        AFTER INSERT ON pgmq.%I
        DEFERRABLE FOR EACH ROW
        EXECUTE PROCEDURE pgmq.notify_queue_listeners()
        $QUERY$,
        qtable
    );
END;
$$ LANGUAGE plpgsql;

-- Disable notifications on message insert for a queue
CREATE OR REPLACE FUNCTION pgmq.disable_notify_insert(queue_name TEXT)
RETURNS void AS $$
DECLARE
    qtable TEXT := pgmq.format_table_name(queue_name, 'q');
    v_queue_name TEXT := queue_name;
BEGIN
    EXECUTE FORMAT(
        $QUERY$
        DROP TRIGGER IF EXISTS trigger_notify_queue_insert_listeners ON pgmq.%I
        $QUERY$,
        qtable
    );

    DELETE FROM pgmq.notify_insert_throttle nit WHERE nit.queue_name = v_queue_name;
END;
$$ LANGUAGE plpgsql;
