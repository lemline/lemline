---
name: sql-optimization-patterns
description: SQL query optimization, indexing strategies, and EXPLAIN analysis for Lemline's multi-database architecture (PostgreSQL, MySQL, H2). Use when debugging slow queries, designing indexes, optimizing outbox patterns, or ensuring cross-database compatibility.
---

# SQL Optimization for Lemline

Optimize Lemline's database queries while maintaining compatibility across PostgreSQL, MySQL, and H2.

## When to Use This Skill

- Debugging slow outbox processing queries
- Designing indexes for Lemline tables
- Optimizing `FOR UPDATE SKIP LOCKED` patterns
- Writing cross-database compatible SQL
- Analyzing EXPLAIN query plans
- Optimizing cleanup and batch operations
- Ensuring UUID v7 indexing efficiency

## Lemline Database Context

### Key Tables and Patterns

Lemline uses an outbox pattern with these core tables:

| Table | Purpose | Key Pattern |
|-------|---------|-------------|
| `lemline_waits` | Timer-based workflow delays | Outbox processing |
| `lemline_retries` | Task retry with backoff | Outbox processing |
| `lemline_parents` | Parent-child workflow tracking | Outbox processing |
| `lemline_schedules` | Cron-based workflow triggers | Outbox processing |
| `lemline_failures` | Terminal workflow failures | Outbox processing |
| `lemline_forks` | Parallel branch coordination | Fork completion |
| `lemline_fork_branches` | Individual branch state | FK to forks |
| `lemline_listeners` | Event listener registration | Event matching |
| `lemline_listener_events` | Pending events | Event processing |
| `lemline_definitions` | Workflow definitions cache | Definition lookup |

### Common Outbox Columns

All outbox tables share these columns:

```sql
id                      UUID PRIMARY KEY,        -- UUID v7 (time-sortable)
workflow_id             UUID NOT NULL,
workflow_namespace      VARCHAR(255) NOT NULL,
workflow_name           VARCHAR(255) NOT NULL,
workflow_version        VARCHAR(255) NOT NULL,
workflow_position       TEXT NOT NULL,
workflow_state          TEXT NOT NULL,
outbox_scheduled_for    TIMESTAMPTZ(6) NOT NULL,
outbox_delayed_until    TIMESTAMPTZ(6) NOT NULL,
outbox_attempt_count    INTEGER NOT NULL DEFAULT 0,
outbox_completed_at     TIMESTAMPTZ(6),
outbox_failed_at        TIMESTAMPTZ(6),
cleanup_after           TIMESTAMPTZ(6),
created_at              TIMESTAMPTZ(6) NOT NULL,
updated_at              TIMESTAMPTZ(6)
```

## Query Execution Plans (EXPLAIN)

### PostgreSQL EXPLAIN

```sql
-- Basic explain
EXPLAIN SELECT * FROM lemline_waits
WHERE workflow_id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11';

-- With actual execution stats (run the query)
EXPLAIN ANALYZE SELECT * FROM lemline_waits
WHERE outbox_completed_at IS NULL
  AND outbox_failed_at IS NULL
  AND outbox_delayed_until <= NOW();

-- Verbose with buffer analysis
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM lemline_retries
WHERE outbox_completed_at IS NULL
  AND outbox_failed_at IS NULL
  AND outbox_delayed_until <= NOW()
  AND outbox_attempt_count < 5
ORDER BY outbox_delayed_until ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

### MySQL EXPLAIN

```sql
-- Basic explain
EXPLAIN SELECT * FROM lemline_waits
WHERE workflow_id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11';

-- Extended format
EXPLAIN FORMAT=JSON SELECT * FROM lemline_waits
WHERE outbox_completed_at IS NULL
  AND outbox_failed_at IS NULL
  AND outbox_delayed_until <= NOW();
```

### Key Metrics to Watch

| Metric | Good | Bad |
|--------|------|-----|
| **Seq Scan** | Small tables (<1000 rows) | Large tables |
| **Index Scan** | Using index | - |
| **Index Only Scan** | Best case | - |
| **Rows** | Matches actual | Off by 10x+ |
| **Cost** | Low | High relative to alternatives |

## Index Strategies for Lemline

### Standard Outbox Processing Index

All outbox tables use this pattern for the main processing query:

```sql
-- PostgreSQL: Partial composite index for pending records
CREATE INDEX IF NOT EXISTS idx_lemline_waits_processing
    ON lemline_waits (outbox_completed_at, outbox_failed_at, outbox_delayed_until)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- MySQL: Cannot use partial indexes, use full composite index
CREATE INDEX idx_lemline_waits_processing
    ON lemline_waits (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);
```

### Cleanup Query Index

```sql
-- PostgreSQL: Partial index for cleanup
CREATE INDEX IF NOT EXISTS idx_lemline_waits_cleanup
    ON lemline_waits (cleanup_after)
    WHERE cleanup_after IS NOT NULL;

-- MySQL: Full index
CREATE INDEX idx_lemline_waits_cleanup
    ON lemline_waits (cleanup_after);
```

### Workflow Lookup Index

```sql
-- For finding all outbox records for a specific workflow
CREATE INDEX IF NOT EXISTS idx_lemline_waits_workflow_id
    ON lemline_waits (workflow_id);

-- Composite for workflow + status queries
CREATE INDEX IF NOT EXISTS idx_lemline_retries_workflow_status
    ON lemline_retries (workflow_id, outbox_completed_at, outbox_failed_at);
```

### UUID v7 Indexing Considerations

UUID v7 is time-sortable, so:

```sql
-- Good: Range queries on id are efficient
SELECT * FROM lemline_waits
WHERE id > '01912345-6789-7abc-8def-0123456789ab'
ORDER BY id ASC LIMIT 100;

-- Good: Natural ordering by creation time
SELECT * FROM lemline_failures
ORDER BY id DESC LIMIT 10;  -- Most recent failures

-- Avoid: Random lookups across entire table
-- UUID v7's time component clusters writes but random reads still need indexes
```

## Outbox Pattern Optimization

### The Core Outbox Query

This is the most critical query in Lemline - runs every few seconds:

```sql
-- PostgreSQL version (used in OutboxRepository.kt:73-84)
SELECT * FROM lemline_waits
WHERE outbox_completed_at IS NULL
  AND outbox_failed_at IS NULL
  AND outbox_delayed_until IS NOT NULL
  AND outbox_delayed_until <= ?
  AND outbox_attempt_count < ?
ORDER BY outbox_delayed_until ASC
LIMIT ?
FOR UPDATE SKIP LOCKED
```

### Optimization Tips

1. **Partial Index**: The `WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL` filter matches the partial index condition exactly

2. **SKIP LOCKED**: Enables parallel processing without contention - workers skip locked rows instead of waiting

3. **ORDER BY**: Ensures oldest-first processing, uses the index's column order

4. **LIMIT**: Batch processing - fetch N at a time for concurrent processing

### Cross-Database Compatibility

```sql
-- PostgreSQL: Full support for partial indexes and SKIP LOCKED
CREATE INDEX idx_processing ON lemline_waits (outbox_delayed_until)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- MySQL 8.0+: SKIP LOCKED supported, but no partial indexes
CREATE INDEX idx_processing ON lemline_waits
    (outbox_completed_at, outbox_failed_at, outbox_delayed_until);

-- H2: Limited SKIP LOCKED support, use for testing only
-- H2 implements FOR UPDATE but SKIP LOCKED behavior varies
```

## Batch Operations

### Efficient Batch Insert (Kotlin)

```kotlin
// Good: Batch insert with prepared statements
suspend fun insertBatch(models: List<WaitModel>, connection: Connection) {
    connection.prepareStatement(insertSQL).use { stmt ->
        models.forEach { model ->
            bindParameters(stmt, model)
            stmt.addBatch()
        }
        stmt.executeBatch()
    }
}

// Bad: Individual inserts in a loop
models.forEach { model ->
    repository.insert(model)  // N database round trips!
}
```

### Batch Update for Outbox Completion

```sql
-- Update multiple completed records efficiently
UPDATE lemline_waits
SET outbox_completed_at = NOW(),
    updated_at = NOW()
WHERE id IN (?, ?, ?, ...)
  AND outbox_completed_at IS NULL;
```

### Cleanup Batch Delete

```sql
-- PostgreSQL: Delete with RETURNING for logging
DELETE FROM lemline_waits
WHERE cleanup_after < ?
  AND outbox_completed_at IS NOT NULL
LIMIT 1000
RETURNING id;

-- Cross-database safe:
DELETE FROM lemline_waits
WHERE id IN (
    SELECT id FROM lemline_waits
    WHERE cleanup_after < ?
      AND outbox_completed_at IS NOT NULL
    LIMIT 1000
);
```

## Fork Pattern Optimization

### Checking Fork Completion

```sql
-- Efficient: Count pending branches with index
SELECT COUNT(*) FROM lemline_fork_branches
WHERE fork_id = ?
  AND completed_at IS NULL
  AND failed_at IS NULL;

-- Index to support this query
CREATE INDEX idx_fork_branches_pending
    ON lemline_fork_branches (fork_id, completed_at, failed_at);
```

### Compete Mode (First Wins)

```sql
-- Check if any branch has completed
SELECT 1 FROM lemline_fork_branches
WHERE fork_id = ?
  AND completed_at IS NOT NULL
LIMIT 1;
```

## Listener Event Matching

### Event Correlation Query

```sql
-- Find listeners matching an event
SELECT l.* FROM lemline_listeners l
WHERE l.workflow_namespace = ?
  AND l.workflow_name = ?
  AND l.workflow_version = ?
  AND l.event_type = ?
  AND l.completed_at IS NULL;

-- Supporting composite index
CREATE INDEX idx_listeners_matching
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, event_type, completed_at);
```

## Query Anti-Patterns to Avoid

### N+1 Queries in Kotlin

```kotlin
// Bad: N+1 pattern
val forks = forkRepository.findPendingForks()
forks.forEach { fork ->
    val branches = branchRepository.findByForkId(fork.id)  // N queries!
    // ...
}

// Good: Single JOIN query
suspend fun findForksWithBranches(): List<ForkWithBranches> =
    databaseConfig.withConnection { conn ->
        conn.prepareStatement("""
            SELECT f.*, b.name as branch_name, b.completed_at as branch_completed_at
            FROM lemline_forks f
            LEFT JOIN lemline_fork_branches b ON f.id = b.fork_id
            WHERE f.completed_at IS NULL
        """).use { stmt ->
            stmt.executeQuery().use { rs ->
                // Group branches by fork
            }
        }
    }
```

### Functions Preventing Index Usage

```sql
-- Bad: Function on indexed column
SELECT * FROM lemline_definitions
WHERE LOWER(workflow_name) = 'myworkflow';

-- Good: Store normalized or use expression index (PostgreSQL only)
CREATE INDEX idx_definitions_name_lower
    ON lemline_definitions (LOWER(workflow_name));
```

### Implicit Type Conversion

```sql
-- Bad: String comparison on UUID column (MySQL)
SELECT * FROM lemline_waits WHERE workflow_id = 'not-a-uuid';

-- Good: Proper UUID type
SELECT * FROM lemline_waits
WHERE workflow_id = CAST('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11' AS UUID);
```

## Monitoring Queries

### PostgreSQL: Find Slow Queries

```sql
-- Requires pg_stat_statements extension
SELECT query, calls, total_time, mean_time, rows
FROM pg_stat_statements
WHERE query LIKE '%lemline%'
ORDER BY mean_time DESC
LIMIT 10;
```

### PostgreSQL: Find Tables Needing Indexes

```sql
SELECT schemaname, tablename,
       seq_scan, seq_tup_read,
       idx_scan,
       CASE WHEN seq_scan > 0
            THEN seq_tup_read / seq_scan
            ELSE 0 END AS avg_seq_tup_read
FROM pg_stat_user_tables
WHERE tablename LIKE 'lemline%'
  AND seq_scan > 100
ORDER BY seq_tup_read DESC;
```

### PostgreSQL: Find Unused Indexes

```sql
SELECT schemaname, tablename, indexname,
       idx_scan, idx_tup_read,
       pg_relation_size(indexrelid) AS index_size
FROM pg_stat_user_indexes
WHERE tablename LIKE 'lemline%'
  AND idx_scan = 0
ORDER BY index_size DESC;
```

## Database-Specific Syntax Reference

### Timestamp Handling

```sql
-- PostgreSQL
NOW(), CURRENT_TIMESTAMP,
column + INTERVAL '5 minutes'

-- MySQL
NOW(), CURRENT_TIMESTAMP,
DATE_ADD(column, INTERVAL 5 MINUTE)

-- H2
NOW(), CURRENT_TIMESTAMP,
DATEADD('MINUTE', 5, column)
```

### LIMIT with OFFSET

```sql
-- PostgreSQL & MySQL & H2
SELECT * FROM lemline_waits LIMIT 100 OFFSET 50;

-- PostgreSQL alternative
SELECT * FROM lemline_waits LIMIT 100 OFFSET 50;
```

### UPSERT / ON CONFLICT

```sql
-- PostgreSQL
INSERT INTO lemline_definitions (id, name, content)
VALUES (?, ?, ?)
ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content;

-- MySQL
INSERT INTO lemline_definitions (id, name, content)
VALUES (?, ?, ?)
ON DUPLICATE KEY UPDATE content = VALUES(content);

-- H2
MERGE INTO lemline_definitions (id, name, content)
VALUES (?, ?, ?);
```

## Best Practices for Lemline

1. **Always use partial indexes in PostgreSQL** for outbox tables - they're much smaller and faster
2. **Test migrations on all three databases** before merging
3. **Use UUID v7 ordering** for pagination instead of OFFSET
4. **Keep outbox tables small** - aggressive cleanup of completed records
5. **Monitor FOR UPDATE SKIP LOCKED** - high skip rates indicate contention
6. **Use batch operations** in Kotlin repositories, not individual inserts
7. **Run ANALYZE after bulk operations** to update statistics

## Related Resources

- `lemline-runner-common/src/main/kotlin/com/lemline/runner/common/repositories/` - Repository base classes
- `lemline-runner-*/src/main/resources/db/migration/` - Migration scripts per database
- `/runner-dev` skill - Runner architecture including database patterns
