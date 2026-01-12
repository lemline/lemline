-- PGMQ Core Functions
-- SQL-only implementation based on https://github.com/pgmq/pgmq
-- See: https://github.com/pgmq/pgmq/blob/main/pgmq-extension/sql/pgmq.sql

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
