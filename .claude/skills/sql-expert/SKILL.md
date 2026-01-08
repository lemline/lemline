---
name: sql-expert
description: SQL query optimization, indexing strategies, and EXPLAIN analysis for Lemline's multi-database
architecture (PostgreSQL, MySQL, H2). Use when debugging slow queries, designing indexes, optimizing outbox patterns, or
ensuring cross-database compatibility.
---

# SQL Optimization Expert for Lemline

You are a database performance expert. Your role is to analyze, optimize, and ensure cross-database compatibility for
Lemline's SQL queries across PostgreSQL, MySQL, and H2.

---

## Action Workflows

### Workflow 1: Query Review

When asked to review a query, follow these steps:

**Step 1: Understand Intent**

- What data is being retrieved/modified?
- How often does this query run? (once, periodic, every request)
- What's the expected data volume?

**Step 2: Check Cross-Database Compatibility**
Run through the [Cross-Database Pitfalls Checklist](#cross-database-pitfalls-checklist):

- [ ] NULL comparisons use `IS NULL` / `IS NOT NULL` (not `= NULL`)
- [ ] No database-specific functions without variants
- [ ] UUID handling is correct for each DB
- [ ] Timestamp arithmetic uses correct syntax per DB
- [ ] No partial indexes without MySQL/H2 alternatives

**Step 3: Analyze Index Usage**

- Does the query filter on indexed columns?
- Is the index order optimal for the WHERE clause?
- Are there functions on indexed columns preventing usage?

**Step 4: Check for Anti-Patterns**

- N+1 query patterns
- SELECT * when only specific columns needed
- Missing LIMIT on potentially large result sets
- Implicit type conversions

**Step 5: Suggest Optimizations**
Provide specific, actionable recommendations with:

- The optimized query
- Required index DDL (for all 3 databases)
- Expected improvement

---

### Workflow 2: Performance Diagnosis

When debugging slow database operations:

**Step 1: Identify the Slow Query**

```sql
-- PostgreSQL: Find slow queries
SELECT query, calls, mean_time, total_time
FROM pg_stat_statements
WHERE query LIKE '%lemline%'
ORDER BY mean_time DESC LIMIT 10;

-- MySQL: Enable slow query log
SET
GLOBAL slow_query_log = 'ON';
SET
GLOBAL long_query_time = 0.1;
```

**Step 2: Get Execution Plan**

```sql
-- PostgreSQL
EXPLAIN
(ANALYZE, BUFFERS, FORMAT TEXT) <your_query>;

-- MySQL
EXPLAIN
FORMAT=JSON <your_query>;
```

**Step 3: Diagnose Plan Issues**

| Symptom                      | Likely Cause                   | Fix                   |
|------------------------------|--------------------------------|-----------------------|
| Seq Scan on large table      | Missing index                  | Add appropriate index |
| High rows estimate vs actual | Stale statistics               | Run ANALYZE           |
| Index scan but slow          | Wrong index or low selectivity | Review index columns  |
| Nested Loop with high rows   | Missing join index             | Add FK index          |
| Sort operation               | Missing index for ORDER BY     | Add sorted index      |

**Step 4: Verify Fix**

- Re-run EXPLAIN after adding index
- Compare before/after metrics
- Test on all 3 databases

---

### Workflow 3: Index Design

When designing indexes for a new table or query:

**Step 1: Identify Query Patterns**

- What WHERE clauses are used?
- What ORDER BY clauses?
- What JOIN conditions?

**Step 2: Apply Index Selection Rules**

```
IF query has equality filters (=):
    Put equality columns FIRST in composite index

IF query has range filters (<, >, BETWEEN):
    Put range column AFTER equality columns
    Only ONE range column per index is effective

IF query has ORDER BY:
    Include ORDER BY columns AFTER filter columns
    Match ASC/DESC direction

IF filtering on NULL status (outbox pattern):
    PostgreSQL: Use partial index with WHERE clause
    MySQL/H2: Include status columns in composite index
```

**Step 3: Create Cross-Database DDL**

Always provide index DDL for all three databases:

```sql
-- PostgreSQL (with partial index)
CREATE INDEX idx_tablename_purpose ON tablename (col1, col2) WHERE status_col IS NULL;

-- MySQL (no partial indexes)
CREATE INDEX idx_tablename_purpose ON tablename (status_col, col1, col2);

-- H2 (similar to PostgreSQL)
CREATE INDEX idx_tablename_purpose ON tablename (col1, col2) WHERE status_col IS NULL;
```

---

### Workflow 4: Schema Design Review

When reviewing or designing a new table:

**Step 1: Check Required Patterns**

For outbox tables, verify these columns exist:

```sql
id
UUID PRIMARY KEY,        -- UUID v7
outbox_scheduled_for    TIMESTAMP NOT NULL,
outbox_delayed_until    TIMESTAMP NOT NULL,
outbox_attempt_count    INTEGER NOT NULL DEFAULT 0,
outbox_completed_at     TIMESTAMP,               -- NULL = pending
outbox_failed_at        TIMESTAMP,               -- NULL = not failed
cleanup_after           TIMESTAMP,
created_at              TIMESTAMP NOT NULL
```

**Step 2: Verify Data Types**

| Concept   | PostgreSQL       | MySQL                      | H2          |
|-----------|------------------|----------------------------|-------------|
| UUID      | `UUID`           | `CHAR(36)` or `BINARY(16)` | `UUID`      |
| Long text | `TEXT`           | `LONGTEXT`                 | `CLOB`      |
| Timestamp | `TIMESTAMPTZ(6)` | `DATETIME(6)`              | `TIMESTAMP` |
| Boolean   | `BOOLEAN`        | `TINYINT(1)`               | `BOOLEAN`   |

**Step 3: Check Indexes**

Every outbox table needs:

1. Processing index (for `FOR UPDATE SKIP LOCKED` queries)
2. Cleanup index (for old record deletion)
3. Workflow lookup index (for status queries)

---

## Cross-Database Pitfalls Checklist

### Critical: NULL Handling

```sql
-- WRONG: Will NEVER match (NULL = NULL is NULL, not TRUE)
WHERE column = NULL
WHERE column <> NULL

-- CORRECT: Explicit NULL checks
WHERE column IS NULL
WHERE column IS NOT NULL

-- CORRECT: Null-safe equality (when comparing two columns)
-- PostgreSQL
WHERE col1 IS NOT DISTINCT FROM col2

-- MySQL
WHERE col1 <=> col2

-- H2
WHERE col1 IS NOT DISTINCT FROM col2
```

### Timestamp Arithmetic

```sql
-- PostgreSQL
column + INTERVAL '5 minutes'
column - INTERVAL '1 hour'
NOW() + INTERVAL '30 seconds'

-- MySQL
DATE_ADD(column, INTERVAL 5 MINUTE)
DATE_SUB(column, INTERVAL 1 HOUR)
DATE_ADD(NOW(), INTERVAL 30 SECOND)

-- H2
DATEADD('MINUTE', 5, column)
DATEADD('HOUR', -1, column)
DATEADD('SECOND', 30, NOW())
```

### String Functions

```sql
-- String concatenation
-- PostgreSQL & H2
'prefix' || column || 'suffix'

-- MySQL
CONCAT('prefix', column, 'suffix')

-- Substring
-- All databases support:
SUBSTRING(column, start, length)
```

### UPSERT Patterns

```sql
-- PostgreSQL
INSERT INTO table (id, data)
VALUES (?, ?) ON CONFLICT (id) DO
UPDATE SET data = EXCLUDED.data;

-- MySQL
INSERT INTO table (id, data)
VALUES (?, ?) ON DUPLICATE KEY
UPDATE data =
VALUES (data);

-- H2
MERGE INTO table (id, data) VALUES (?, ?);
```

### Boolean Expressions

```sql
-- PostgreSQL & H2: Native boolean
WHERE is_active = TRUE
WHERE NOT is_deleted

-- MySQL: Use explicit comparison
WHERE is_active = 1
WHERE is_deleted = 0
```

### LIMIT with FOR UPDATE

```sql
-- PostgreSQL: LIMIT works with FOR UPDATE
SELECT *
FROM table
WHERE...LIMIT 10 FOR
UPDATE SKIP LOCKED;

-- MySQL: Subquery required for LIMIT with FOR UPDATE
SELECT *
FROM table
WHERE id IN (SELECT id
             FROM table
             WHERE...LIMIT 10
    ) FOR
UPDATE SKIP LOCKED;

-- H2: Similar to PostgreSQL
SELECT *
FROM table
WHERE...LIMIT 10 FOR
UPDATE;
```

---

## Lemline-Specific Patterns

### The Core Outbox Query

This is the most performance-critical query - runs every few seconds:

```sql
SELECT *
FROM lemline_waits
WHERE outbox_completed_at IS NULL
  AND outbox_failed_at IS NULL
  AND outbox_delayed_until IS NOT NULL
  AND outbox_delayed_until <= ?
  AND outbox_attempt_count < ?
ORDER BY outbox_delayed_until ASC LIMIT ?
FOR
UPDATE SKIP LOCKED
```

**Required indexes:**

```sql
-- PostgreSQL: Partial index (most efficient)
CREATE INDEX idx_lemline_waits_pending ON lemline_waits (outbox_delayed_until) WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- MySQL: Composite index (no partial index support)
CREATE INDEX idx_lemline_waits_pending ON lemline_waits
    (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);

-- H2: Partial index
CREATE INDEX idx_lemline_waits_pending ON lemline_waits (outbox_delayed_until) WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;
```

### UUID v7 Considerations

UUID v7 is time-sortable. Leverage this:

```sql
-- Good: Range queries on UUID v7 are efficient (time-based)
SELECT *
FROM lemline_failures
WHERE id > ? -- cursor-based pagination
ORDER BY id ASC LIMIT 100;

-- Good: Recent records query
SELECT *
FROM lemline_failures
ORDER BY id DESC -- most recent first
    LIMIT 10;

-- Avoid: OFFSET-based pagination on large tables
SELECT *
FROM lemline_failures
ORDER BY id LIMIT 100
OFFSET 10000; -- Slow! Scans 10100 rows
```

---

## Query Execution Plans (EXPLAIN)

### PostgreSQL EXPLAIN

```sql
-- Basic plan
EXPLAIN
SELECT *
FROM lemline_waits
WHERE workflow_id = ?;

-- With execution stats (actually runs the query)
EXPLAIN
ANALYZE
SELECT *
FROM lemline_waits
WHERE...;

-- Full analysis with buffer stats
EXPLAIN
(ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT)
SELECT *
FROM lemline_waits
WHERE outbox_completed_at IS NULL
  AND outbox_delayed_until <= NOW()
    FOR UPDATE SKIP LOCKED;
```

**Reading PostgreSQL EXPLAIN:**

| Term              | Meaning                        | Good/Bad            |
|-------------------|--------------------------------|---------------------|
| Seq Scan          | Full table scan                | Bad on large tables |
| Index Scan        | Uses index, fetches from table | Good                |
| Index Only Scan   | Uses index only                | Best                |
| Bitmap Index Scan | Multiple index conditions      | Good                |
| Nested Loop       | Join method                    | OK for small outer  |
| Hash Join         | Join method                    | Good for large sets |
| Sort              | Explicit sort operation        | Bad if large        |
| LockRows          | FOR UPDATE overhead            | Expected            |

### MySQL EXPLAIN

```sql
-- Basic
EXPLAIN
SELECT *
FROM lemline_waits
WHERE workflow_id = ?;

-- JSON format (more detail)
EXPLAIN
FORMAT=JSON
SELECT *
FROM lemline_waits
WHERE...;

-- Analyze (MySQL 8.0.18+)
EXPLAIN
ANALYZE
SELECT *
FROM lemline_waits
WHERE...;
```

**Reading MySQL EXPLAIN:**

| Column | Good Values               | Bad Values                      |
|--------|---------------------------|---------------------------------|
| type   | const, eq_ref, ref, range | ALL, index                      |
| key    | Index name                | NULL                            |
| rows   | Low number                | High number                     |
| Extra  | Using index               | Using filesort, Using temporary |

---

## Common Anti-Patterns

### N+1 Queries

```kotlin
// BAD: N+1 pattern
val forks = forkRepository.findPendingForks()
forks.forEach { fork ->
    val branches = branchRepository.findByForkId(fork.id)  // N extra queries!
}

// GOOD: Single JOIN query
suspend fun findForksWithBranches(): List<ForkWithBranches> {
    return pool.withConnection { conn ->
        conn.preparedQuery(
            """
            SELECT f.*, b.id as branch_id, b.name as branch_name
            FROM lemline_forks f
            LEFT JOIN lemline_fork_branches b ON f.id = b.fork_id
            WHERE f.outbox_completed_at IS NULL
        """
        ).execute().awaitSuspending()
            .groupBy { it.getUUID("id") }
            .map { (forkId, rows) -> ForkWithBranches(rows) }
    }
}
```

### Functions on Indexed Columns

```sql
-- BAD: Function prevents index usage
SELECT *
FROM lemline_definitions
WHERE LOWER(workflow_name) = 'myworkflow';

-- GOOD: Store normalized value
SELECT *
FROM lemline_definitions
WHERE workflow_name_lower = 'myworkflow';

-- GOOD (PostgreSQL only): Expression index
CREATE INDEX idx_def_name_lower ON lemline_definitions (LOWER(workflow_name));
```

### SELECT * in Production

```sql
-- BAD: Fetches all columns including large TEXT fields
SELECT *
FROM lemline_waits
WHERE...;

-- GOOD: Fetch only needed columns
SELECT id, workflow_id, outbox_delayed_until
FROM lemline_waits
WHERE...;
```

### Missing LIMIT on Unbounded Queries

```sql
-- BAD: Could return millions of rows
SELECT *
FROM lemline_failures
WHERE workflow_namespace = 'default';

-- GOOD: Always limit
SELECT *
FROM lemline_failures
WHERE workflow_namespace = 'default'
ORDER BY id DESC LIMIT 1000;
```

---

## Monitoring Queries

### PostgreSQL

```sql
-- Slow queries (requires pg_stat_statements)
SELECT query, calls, mean_time, total_time, rows
FROM pg_stat_statements
WHERE query LIKE '%lemline%'
ORDER BY mean_time DESC
    LIMIT 10;

-- Tables needing indexes (high seq scans)
SELECT schemaname, tablename, seq_scan, seq_tup_read, idx_scan
FROM pg_stat_user_tables
WHERE tablename LIKE 'lemline%'
  AND seq_scan > 100
ORDER BY seq_tup_read DESC;

-- Unused indexes (candidates for removal)
SELECT schemaname,
       tablename,
       indexname,
       idx_scan,
       pg_relation_size(indexrelid) AS index_size
FROM pg_stat_user_indexes
WHERE tablename LIKE 'lemline%'
  AND idx_scan = 0
ORDER BY index_size DESC;

-- Table bloat check
SELECT tablename,
       pg_size_pretty(pg_total_relation_size(tablename::regclass)) AS total_size,
       pg_size_pretty(pg_relation_size(tablename::regclass))       AS table_size
FROM pg_tables
WHERE tablename LIKE 'lemline%';
```

### MySQL

```sql
-- Enable slow query log
SET
GLOBAL slow_query_log = 'ON';
SET
GLOBAL long_query_time = 0.1;

-- Check index usage
SELECT table_name,
       index_name,
       stat_value AS pages_read
FROM mysql.innodb_index_stats
WHERE table_name LIKE 'lemline%'
  AND stat_name = 'n_leaf_pages';

-- Table sizes
SELECT table_name,
       ROUND(data_length / 1024 / 1024, 2)  AS data_mb,
       ROUND(index_length / 1024 / 1024, 2) AS index_mb
FROM information_schema.tables
WHERE table_name LIKE 'lemline%';
```

---

## Quick Reference: Database-Specific Syntax

| Operation     | PostgreSQL             | MySQL               | H2                     |
|---------------|------------------------|---------------------|------------------------|
| Current time  | `NOW()`                | `NOW()`             | `NOW()`                |
| UUID type     | `UUID`                 | `CHAR(36)`          | `UUID`                 |
| Boolean true  | `TRUE`                 | `1`                 | `TRUE`                 |
| String concat | `\|\|`                 | `CONCAT()`          | `\|\|`                 |
| Null-safe =   | `IS NOT DISTINCT FROM` | `<=>`               | `IS NOT DISTINCT FROM` |
| Partial index | Supported              | Not supported       | Supported              |
| SKIP LOCKED   | Full support           | Full support (8.0+) | Limited                |
| JSON type     | `JSONB`                | `JSON`              | `JSON`                 |
| Array type    | `ARRAY[]`              | Not native          | `ARRAY[]`              |

---

## Best Practices Summary

1. **Always test on all 3 databases** before merging SQL changes
2. **Use partial indexes in PostgreSQL/H2** for outbox tables
3. **Never use `= NULL`** - always `IS NULL` or `IS NOT NULL`
4. **Provide database variants** for any non-standard SQL
5. **Use UUID v7 ordering** for pagination instead of OFFSET
6. **Batch operations** - never loop with individual queries
7. **Keep outbox tables small** - aggressive cleanup of completed records
8. **Run EXPLAIN** on any query that runs frequently or on large tables
9. **Add indexes proactively** for new WHERE/ORDER BY patterns
10. **Monitor FOR UPDATE SKIP LOCKED** - high skip rates indicate contention

---

## Related Resources

- `lemline-runner-common/src/main/kotlin/com/lemline/runner/common/repositories/` - Repository base classes
- `lemline-runner-*/src/main/resources/db/migration/` - Migration scripts per database
- `/runner-dev` skill - Runner architecture including database patterns
