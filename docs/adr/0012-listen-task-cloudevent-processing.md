# [ADR-0012] Listen Task CloudEvent Processing

## Status

Accepted

## Context

The `listen` task in Serverless Workflow DSL enables workflows to wait for CloudEvents before continuing execution.
When a CloudEvent arrives, the system must:

1. Match the event against workflow definitions to find relevant listen tasks
2. Route the event to active listener instances waiting for it
3. Handle multiple consumption strategies (ONE, ANY, ALL, ANY+until)
4. Ensure exactly-once semantics despite concurrent processing and retries
5. Scale to potentially millions of active listeners

This ADR describes the architecture for processing incoming CloudEvents and completing listener instances.

### Feature Overview

The listen task supports four consumption strategies:

| Strategy                    | Description                                    | Completion Condition                              |
|-----------------------------|------------------------------------------------|---------------------------------------------------|
| **ONE**                     | Wait for a single event matching one filter    | First matching event                              |
| **ANY**                     | Wait for first event matching any of N filters | First matching event from any filter              |
| **ANY + until(expression)** | Accumulate events until expression is true     | Expression evaluates to true on accumulated array |
| **ANY + until(event)**      | Accumulate events until termination event      | Termination event type arrives                    |
| **ALL**                     | Wait for one event per filter                  | One event received for each of N filters          |

Each strategy has different requirements for event storage and completion detection.

## Decision

We implement a **direct database operation architecture** that avoids loading listeners into memory:

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            CloudEvent Arrives                                   │
│                                                                                 │
│  CloudEventHandler.handleCloudEvent(event)                                      │
└─────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    STEP 1: Definition Matching (In-Memory)                      │
│                                                                                 │
│  DefinitionListenService.findMatchingDefinitions(event)                         │
│                                                                                 │
│  For each workflow definition in DefinitionCache:                               │
│    - Check if event matches any filter (type, source, data expressions)         │
│    - Extract correlation values using correlate.from expressions                │
│    - Return DefinitionMatch with (workflowInfo, position, correlation, strategy)│
│                                                                                 │
│  Also: findDefinitionsUntilEvent(event) for termination filters                 │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                    STEP 2: Strategy-Specific Processing                          │
│                                                                                  │
│  ┌──────────────────────────────────────────────────────────────────────────┐    │
│  │ ONE / ANY (without until)                                                │    │
│  │                                                                          │    │
│  │ processOneAnyDirect():                                                   │    │
│  │   Direct UPDATE without SELECT                                           │    │
│  │   Sets event + outbox_delayed_until in single atomic operation           │    │
│  │                                                                          │    │
│  │   UPDATE lemline_listeners                                               │    │
│  │   SET event = '[{...}]',                                                 │    │
│  │       outbox_delayed_until = NOW()                                       │    │
│  │   WHERE outbox_delayed_until IS NULL                                     │    │
│  │     AND outbox_completed_at IS NULL                                      │    │
│  │     AND (workflow_namespace, name, version, position, correlation)       │    │
│  │                                                                          │    │
│  └──────────────────────────────────────────────────────────────────────────┘    │
│                                                                                  │
│  ┌──────────────────────────────────────────────────────────────────────────┐    │
│  │ ANY + until(expression)                                                  │    │
│  │                                                                          │    │
│  │ processAnyWithUntilExpressionDirect():                                   │    │
│  │   1. Bulk INSERT events via INSERT...SELECT (no memory load)             │    │
│  │   2. Stream listeners with accumulated events (cursor-based)             │    │
│  │   3. Evaluate JQ expression on each listener's accumulated events        │    │
│  │   4. Batch UPDATE listeners where expression evaluates to true           │    │
│  │                                                                          │    │
│  └──────────────────────────────────────────────────────────────────────────┘    │
│                                                                                  │
│  ┌──────────────────────────────────────────────────────────────────────────┐    │
│  │ ANY + until(event)                                                       │    │
│  │                                                                          │    │
│  │ processAnyWithUntilEventDirect():                                        │    │
│  │   Bulk INSERT events for accumulation (no completion here)               │    │
│  │                                                                          │    │
│  │ processTerminationEventsDirect():                                        │    │
│  │   When termination event arrives:                                        │    │
│  │   UPDATE with subquery to aggregate accumulated events                   │    │
│  │                                                                          │    │
│  └──────────────────────────────────────────────────────────────────────────┘    │
│                                                                                  │
│  ┌──────────────────────────────────────────────────────────────────────────┐    │
│  │ ALL                                                                      │    │
│  │                                                                          │    │
│  │ processAllDirect():                                                      │    │
│  │   1. Bulk INSERT events with filter_index via INSERT...SELECT            │    │
│  │   2. Direct UPDATE with COUNT subquery:                                  │    │
│  │      WHERE (SELECT COUNT(*) FROM events) >= filters_count                │    │
│  │                                                                          │    │
│  └──────────────────────────────────────────────────────────────────────────┘    │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                    STEP 3: Outbox Completion                                     │
│                                                                                  │
│  ListenerCompletionOutbox polls for listeners with:                              │
│    outbox_delayed_until <= NOW()                                                 │
│    outbox_completed_at IS NULL                                                   │
│                                                                                  │
│  For each ready listener:                                                        │
│    1. Read event column (contains JSON array of matched events)                  │
│    2. Create ResumeWithCompletedTask command                                     │
│    3. Send to workflow command channel                                           │
│    4. Mark outbox_completed_at = NOW()                                           │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Database Schema

Two tables support the listen task:

**lemline_listeners** (main listener state):

```sql
CREATE TABLE lemline_listeners
(
    id                   UUID PRIMARY KEY,

    -- Workflow definition reference
    workflow_namespace   VARCHAR(255) NOT NULL,
    workflow_name        VARCHAR(255) NOT NULL,
    workflow_version     VARCHAR(255) NOT NULL,
    workflow_id          UUID         NOT NULL,
    workflow_position    TEXT         NOT NULL,
    workflow_state       TEXT         NOT NULL,

    -- Correlation (for event routing)
    correlation_values   TEXT,           -- JSON: {"orderId":"123"}

    -- Single event storage (ONE/ANY) or aggregated events (ALL/ANY+until)
    event                TEXT,

    -- ALL strategy completion tracking
    filters_count        INT,

    -- Timeout
    timeout_at           TIMESTAMPTZ(6),

    -- Outbox pattern fields
    outbox_delayed_until TIMESTAMPTZ(6), -- NULL = waiting, NOT NULL = ready
    outbox_completed_at  TIMESTAMPTZ(6),
    outbox_failed_at     TIMESTAMPTZ(6),
    -- ... other outbox fields
);
```

**lemline_listener_events** (event accumulation for ALL/ANY+until):

```sql
CREATE TABLE lemline_listener_events
(
    id            UUID PRIMARY KEY,
    listener_id   UUID NOT NULL REFERENCES lemline_listeners (id) ON DELETE CASCADE,

    -- Idempotency columns (mutually exclusive usage)
    filter_index  INT,                  -- For ALL strategy (which filter matched)
    cloudevent_id VARCHAR(255),         -- For ANY+until (prevent duplicate events)

    event         TEXT NOT NULL,
    created_at    TIMESTAMPTZ(6) NOT NULL,

    UNIQUE (listener_id, filter_index), -- ONE event per filter for ALL
    UNIQUE (listener_id, cloudevent_id) -- No duplicate CloudEvents for ANY+until
);
```

### Key Design Decisions

#### 1. Direct UPDATE Without SELECT (ONE/ANY)

For strategies that complete on the first event, we avoid loading listeners into memory:

```kotlin
suspend fun markReadyForCompletionByKeys(keys: List<ListenerQueryKey>, event: String): Int {
    val sql = """
        UPDATE lemline_listeners
        SET event = ?,
            outbox_delayed_until = ?,
            updated_at = ?
        WHERE outbox_delayed_until IS NULL
          AND outbox_completed_at IS NULL
          AND outbox_failed_at IS NULL
          AND (workflow conditions...)
    """.trimIndent()
    // Execute and return affected row count
}
```

This single UPDATE can mark millions of listeners for completion without loading any into memory.

#### 2. INSERT...SELECT for Bulk Event Insertion

For strategies that accumulate events, we use INSERT...SELECT to avoid N+1 queries:

```kotlin
val sql = databaseManager.insertIgnoreSelect(
    tableName = "lemline_listener_events",
    columns = "id, listener_id, filter_index, cloudevent_id, event, created_at",
    selectSql = """
        SELECT gen_random_uuid(), l.id, ?, ?, ?, CURRENT_TIMESTAMP
        FROM lemline_listeners l
        WHERE l.outbox_delayed_until IS NULL
          AND l.outbox_completed_at IS NULL
          AND (workflow conditions...)
    """.trimIndent()
)
```

This generates:

- PostgreSQL: `INSERT INTO ... SELECT ... ON CONFLICT DO NOTHING`
- MySQL: `INSERT IGNORE INTO ... SELECT ...`

#### 3. Cursor-Based Streaming for Expression Evaluation

For ANY+until(expression), we need to evaluate JQ expressions against accumulated events.
Instead of loading all listeners, we stream them:

```kotlin
fun streamListenersWithEvents(keys: List<ListenerQueryKey>): Flow<ListenerWithEvents> = flow {
    conn.autoCommit = false  // Enable cursor
    stmt.fetchSize = 500     // Stream in batches

    while (rs.next()) {
        val listener = createModel(rs)
        val events = parseJsonArrayToList(rs.getString("accumulated_events"))
        emit(ListenerWithEvents(listener, events))
    }
}.flowOn(Dispatchers.IO)
```

This maintains constant memory regardless of how many listeners match.

#### 4. Subquery-Based Completion Check for ALL Strategy

Instead of loading listeners to check completion, we use a subquery:

```sql
UPDATE lemline_listeners l
SET event                = (SELECT json_agg(e.event::json)
                            FROM lemline_listener_events e
                            WHERE e.listener_id = l.id),
    outbox_delayed_until = NOW()
WHERE outbox_delayed_until IS NULL
  AND outbox_completed_at IS NULL
  AND filters_count IS NOT NULL
  AND (SELECT COUNT(*)
       FROM lemline_listener_events e
       WHERE e.listener_id = l.id) >= filters_count
  AND (workflow conditions...)
```

## Idempotency Guarantees

Idempotency ensures that processing the same event multiple times produces the same result.

### ONE/ANY Strategy

**Mechanism**: WHERE guards on UPDATE

```sql
WHERE outbox_delayed_until IS NULL   -- Not already marked ready
  AND outbox_completed_at IS NULL    -- Not already completed
  AND outbox_failed_at IS NULL       -- Not failed
```

**Behavior**: If the same event is processed twice (e.g., due to consumer rebalance):

1. First processing: UPDATE succeeds, sets `outbox_delayed_until`
2. Second processing: UPDATE affects 0 rows (guard condition fails)

**Result**: Only one completion message is sent.

### ANY + until Strategies

**Mechanism**: UNIQUE constraint on `cloudevent_id`

```sql
UNIQUE (listener_id, cloudevent_id)
```

**Behavior**: If the same CloudEvent is processed twice:

1. First processing: INSERT succeeds
2. Second processing: INSERT fails with conflict, `ON CONFLICT DO NOTHING` ignores it

**Result**: Each CloudEvent is stored exactly once per listener.

### ALL Strategy

**Mechanism**: UNIQUE constraint on `filter_index`

```sql
UNIQUE (listener_id, filter_index)
```

**Behavior**: If the same filter matches twice (impossible per spec, but handled):

1. First processing: INSERT succeeds for filter_index=0
2. Second processing: INSERT fails with conflict, ignored

**Result**: Each filter can only contribute one event per listener.

### Completion Idempotency

**Mechanism**: Outbox pattern with idempotent message IDs

```kotlin
val messageId = entity.id.derive("-listen-complete")
instanceEmitter.send(resumeMessage, messageId)
```

**Behavior**: If completion is sent twice:

1. Message broker deduplicates based on message ID
2. Workflow consumer handles duplicate commands gracefully

## Race Condition Prevention

### Scenario 1: Concurrent Events for ONE Listener

Two different events arrive simultaneously, both matching a ONE listener.

```
Thread A: Event type=order.created
Thread B: Event type=order.created

Both call markReadyForCompletionByKeys() concurrently
```

**Protection**: Atomic UPDATE with WHERE guard

```sql
-- Thread A executes:
UPDATE...SET outbox_delayed_until = NOW()
WHERE outbox_delayed_until IS NULL
-- Result: 1 row affected

-- Thread B executes (nanoseconds later):
UPDATE...SET outbox_delayed_until = NOW()
WHERE outbox_delayed_until IS NULL
-- Result: 0 rows affected (guard condition fails)
```

**Outcome**: Only one event completes the listener.

### Scenario 2: Same Event Processed by Multiple Workers

Due to Kafka consumer group rebalance, the same event is processed by two workers.

```
Worker A: Processes event id=evt-123
Worker B: Processes event id=evt-123 (redelivery)
```

**Protection (ANY+until)**: cloudevent_id UNIQUE constraint

```sql
-- Worker A executes:
INSERT INTO lemline_listener_events (..., cloudevent_id='evt-123', ...)
ON CONFLICT DO NOTHING
-- Result: 1 row inserted

-- Worker B executes:
INSERT INTO lemline_listener_events (..., cloudevent_id='evt-123', ...)
ON CONFLICT DO NOTHING
-- Result: 0 rows inserted (conflict ignored)
```

**Outcome**: Event stored exactly once.

### Scenario 3: ALL Strategy Concurrent Filter Matches

Filter 1 and Filter 2 events arrive simultaneously for the same listener.

```
Thread A: Event matches filter_index=0
Thread B: Event matches filter_index=1
```

**Protection**: Atomic UPDATE with COUNT subquery

```sql
-- Both threads eventually call:
UPDATE...WHERE (SELECT COUNT (*) FROM events) >= filters_count

-- Database evaluates atomically:
-- If Thread A sees count=2, Thread B also sees count=2
-- Both try UPDATE with WHERE outbox_delayed_until IS NULL
-- Only one succeeds
```

**Outcome**: Listener completed exactly once with both events.

### Scenario 4: Termination Event During Accumulation

Accumulation event and termination event arrive simultaneously.

```
Thread A: Accumulation event (temperature reading)
Thread B: Termination event (shift ended)
```

**Protection**: INSERT before UPDATE ordering + WHERE guards

```
Thread A: INSERT event into lemline_listener_events (succeeds)
Thread B: UPDATE with subquery aggregating all events

The UPDATE's subquery includes Thread A's event if it was committed first.
If not, Thread A's event is lost (acceptable - shift already ended).
```

**Outcome**: Termination captures all events committed before its UPDATE.

## Performance Characteristics

### Scalability Analysis

| Operation                     | Time Complexity | Database Calls                      | Memory Usage  |
|-------------------------------|-----------------|-------------------------------------|---------------|
| ONE/ANY (N listeners)         | O(1)            | 1 UPDATE                            | O(1)          |
| ALL (N listeners, M filters)  | O(M)            | M INSERTs + 1 UPDATE                | O(M)          |
| ANY+until(expr) (N listeners) | O(N)            | 1 INSERT + stream + batched UPDATEs | O(batch_size) |
| ANY+until(event) accumulation | O(1)            | 1 INSERT                            | O(1)          |
| Termination event             | O(1)            | 1 UPDATE with subquery              | O(1)          |

### Index Strategy

```sql
-- Primary lookup index (partial for efficiency)
CREATE INDEX idx_listeners_workflow_position
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version,
                          workflow_position) WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Correlation-based lookup
CREATE INDEX idx_listeners_correlation
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version,
                          workflow_position,
                          correlation_values) WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Outbox processing
CREATE INDEX idx_listeners_processing
    ON lemline_listeners (outbox_delayed_until) WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;
```

Partial indexes ensure only active listeners are indexed, keeping index size small.

### Batch Processing

For ANY+until(expression), we batch UPDATE operations:

```kotlin
val batchSize = 1000
val readyListeners = mutableMapOf<IDV7, String>()

flow.collect { (listener, events) ->
    if (evaluateExpression(events)) {
        readyListeners[listener.id] = Json.encodeToString(events)

        if (readyListeners.size >= batchSize) {
            listenerRepository.batchMarkReadyForCompletionFromEvents(readyListeners)
            readyListeners.clear()
        }
    }
}
```

This reduces database round-trips while maintaining bounded memory.

### Potential Bottlenecks

1. **JQ Expression Evaluation**: CPU-bound for complex expressions on large event arrays.
   Mitigation: Streaming limits concurrent evaluations.

2. **Large WHERE Clauses**: Many ListenerQueryKey entries create large OR conditions.
   Mitigation: Consider batching if keys exceed ~100.

3. **JSON Aggregation**: `json_agg()` subqueries for ALL strategy completion.
   Mitigation: Database optimizers handle this efficiently with proper indexing.

## Consequences

### Positive

- **Scalability**: Handles millions of listeners without loading into memory
- **Reliability**: Database constraints guarantee exactly-once semantics
- **Performance**: Bulk operations minimize database round-trips
- **Simplicity**: Single responsibility - CloudEventHandler only marks listeners ready
- **Decoupling**: Outbox pattern separates event matching from workflow resumption

### Negative

- **Complexity**: Multiple code paths for different strategies
- **Database Dependency**: Relies on database-specific features (ON CONFLICT, json_agg)
- **Eventual Consistency**: Listeners may not be completed immediately (outbox polling interval)
- **Expression Streaming**: ANY+until(expression) requires streaming, adding latency

### Neutral

- **Two Tables**: Separation of single events (listeners.event) and accumulated events (listener_events)
- **Cursor Streaming**: Requires `autoCommit=false` which holds connections longer

## Alternatives Considered

### 1. Load All Listeners Into Memory

Load matching listeners, process in-memory, batch update.

**Rejected because:**

- Memory explosion with millions of listeners
- Out-of-memory crashes under high load
- No benefit for simple strategies (ONE/ANY)

### 2. Message Queue Per Listener

Create a dedicated queue/topic per listener for event delivery.

**Rejected because:**

- Millions of queues not practical
- Complex queue lifecycle management
- Broker resource exhaustion

### 3. Polling-Based Completion

Listeners poll for their events instead of push-based update.

**Rejected because:**

- Inefficient for rare events
- Increased database load
- Higher latency to completion

### 4. Stored Procedures

Move all logic into database stored procedures.

**Rejected because:**

- Harder to test and maintain
- Database-specific code duplication
- JQ expression evaluation not available in SQL

## References

- [Serverless Workflow Specification - Listen Task](https://github.com/serverlessworkflow/specification)
- [ADR-0003 Messaging Architecture](./0003-messaging-architecture.md)
- [ADR-0011 Listen Task Correlation Matching](./0011-listen-correlation-matching.md)
- [Listen Task Documentation](../listen-task.md)
- `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/cloudevents/CloudEventHandler.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/ListenerRepository.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/ListenerEventRepository.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/ListenerCompletionOutbox.kt`
