# [ADR-0013] Subscription Event Queue Architecture

## Status

Proposed

## Context

Subscriptions receive events and process them through configured operations. The key requirement is that operations for
a message must complete before moving on to the next one (FIFO ordering). However, some subscriptions may benefit from
parallel processing where ordering is less critical.

The system must support:

1. **FIFO ordering**: Events processed strictly in arrival order, one at a time
2. **Parallel processing**: Multiple events processed concurrently up to a configurable limit
3. **Fan-out scenarios**: A single event may match one subscription (common case) or millions (rare case)
4. **Reliable delivery**: Events must not be lost or processed twice
5. **Scalability**: Handle high throughput without database bottlenecks

This ADR describes the database schema and processing architecture for subscription event queues.

## Decision

We implement a **sequence-based queue with outbox pattern** that supports both FIFO and parallel processing modes per
subscription.

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              EVENT ARRIVES                                  │
└─────────────────────────────────────┬───────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │   Find matching subscriptions   │
                    │   (filter evaluation)           │
                    └─────────────────┬───────────────┘
                                      │
                    ┌─────────────────┴──────────────┐
                    │                                │
                    ▼                                ▼
            ┌────────────────┐               ┌────────────────┐
            │ 1 subscription │               │ N subscriptions│
            │ (common case)  │               │ (rare spike)   │
            └───────┬────────┘               └───────┬────────┘
                    │                                │
                    ▼                                ▼
            ┌───────────────┐                ┌────────────────┐
            │ Single INSERT │                │ Batch INSERT   │
            │ into queue    │                │ (chunked)      │
            └───────┬───────┘                └───────┬────────┘
                    │                                │
                    └─────────────────┬──────────────┘
                                      │
                                      ▼
                    ┌───────────────────────────────┐
                    │      subscription_events      │
                    │  ┌────┬────┬────┬────┬────┐   │
                    │  │ P  │ P  │ P  │ P  │ P  │   │  (status = PENDING)
                    │  └────┴────┴────┴────┴────┘   │
                    └─────────────────┬─────────────┘
                                      │
                                      │ Outbox Relay (periodic)
                                      ▼
                    ┌──────────────────────────────────┐
                    │  1. Claim: UPDATE ... PROCESSING │
                    │     (respects FIFO/PARALLEL)     │
                    │  2. Send to broker               │
                    │  3. Mark: UPDATE ... SENT        │
                    └─────────────────┬────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │         MESSAGE BROKER          │
                    │   (processes subscription work) │
                    └─────────────────────────────────┘
```

### Database Schema

```sql
-- Subscription configuration
CREATE TABLE subscriptions
(
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    filter          JSONB NULL,                           -- Criteria for matching events
    processing_mode VARCHAR(10)  NOT NULL DEFAULT 'FIFO', -- 'FIFO' or 'PARALLEL'
    max_concurrent  INT          NOT NULL DEFAULT 1,      -- Max in-flight events (PARALLEL mode)
    created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Queued events per subscription
CREATE TABLE subscription_events
(
    id              UUID PRIMARY KEY,
    subscription_id UUID        NOT NULL REFERENCES subscriptions (id),
    sequence        BIGINT      NOT NULL,                   -- Order within subscription (1, 2, 3...)
    payload         JSONB       NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING → PROCESSING → SENT
    claimed_at      TIMESTAMPTZ(6) NULL,
    sent_at         TIMESTAMPTZ(6) NULL,
    created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (subscription_id, sequence)
);

-- Index for efficient claiming (PostgreSQL - partial index)
CREATE INDEX idx_subscription_events_claimable
    ON subscription_events (subscription_id, status, sequence) WHERE status = 'PENDING';
```

### Database-Specific Considerations

The schema above is PostgreSQL-specific. Here are the required adaptations:

| Feature | PostgreSQL | MySQL | H2 |
|---------|------------|-------|-----|
| JSON column | `JSONB` | `JSON` | `TEXT` |
| Timestamp | `TIMESTAMPTZ(6)` | `DATETIME(6)` | `TIMESTAMP(6)` |
| Partial index | Supported | **Not supported** | Supported |
| `FOR UPDATE SKIP LOCKED` | 9.5+ | 8.0+ | 2.2.220+ |
| `RETURNING *` | Supported | **Not supported** | Supported |

#### MySQL Schema Variant

```sql
CREATE TABLE subscription_events
(
    id              CHAR(36) PRIMARY KEY,
    subscription_id CHAR(36)    NOT NULL,
    sequence        BIGINT      NOT NULL,
    payload         JSON        NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    claimed_at      DATETIME(6) NULL,
    sent_at         DATETIME(6) NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    UNIQUE (subscription_id, sequence),
    FOREIGN KEY (subscription_id) REFERENCES subscriptions (id)
);

-- MySQL: No partial index, use regular index
CREATE INDEX idx_subscription_events_claimable
    ON subscription_events (subscription_id, status, sequence);
```

#### H2 Schema Variant

```sql
CREATE TABLE subscription_events
(
    id              UUID PRIMARY KEY,
    subscription_id UUID        NOT NULL REFERENCES subscriptions (id),
    sequence        BIGINT      NOT NULL,
    payload         TEXT        NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    claimed_at      TIMESTAMP(6) NULL,
    sent_at         TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (subscription_id, sequence)
);

-- H2: Partial index supported
CREATE INDEX idx_subscription_events_claimable
    ON subscription_events (subscription_id, status, sequence) WHERE status = 'PENDING';
```

### Key Concept: Sequence Numbers

A **sequence** is an incrementing number per subscription that establishes arrival order:

```
Subscription A:
  Event arrives → sequence = 1
  Event arrives → sequence = 2
  Event arrives → sequence = 3

Subscription B (independent):
  Event arrives → sequence = 1
  Event arrives → sequence = 2
```

The sequence ensures FIFO processing: sequence 1 completes before sequence 2 starts. Each subscription maintains its own
independent sequence.

### Processing Modes

**FIFO Mode** (`processing_mode = 'FIFO'`):

```
Subscription A: [1:SENT] [2:PROCESSING] [3:PENDING] [4:PENDING]
                                ↑
                         Only one in-flight
                         Must complete before 3 starts
```

**PARALLEL Mode** (`processing_mode = 'PARALLEL'`, `max_concurrent = 3`):

```
Subscription B: [1:SENT] [2:PROCESSING] [3:PROCESSING] [4:PROCESSING] [5:PENDING]
                                ↑              ↑              ↑
                              Three in-flight simultaneously
                              5 waits until one completes
```

### Event Arrival and Fan-Out

When an event arrives, determine matching subscriptions and insert into the queue:

```kotlin
suspend fun onEventArrived(event: Event) {
    // 1. Find matching subscriptions
    val matchingSubscriptions = subscriptionRepository.findMatching(event)

    // 2. Fan-out: insert one row per subscription
    if (matchingSubscriptions.size > 1000) {
        // Large fan-out: batch insert to handle rare spikes
        matchingSubscriptions.chunked(1000).forEach { batch ->
            subscriptionEventRepository.insertBatch(
                batch.map { sub ->
                    SubscriptionEvent(
                        id = IDV7.generate(),
                        subscriptionId = sub.id,
                        sequence = nextSequenceFor(sub.id),
                        payload = event.payload,
                        status = "PENDING"
                    )
                }
            )
        }
    } else {
        // Common case: single or few subscriptions
        matchingSubscriptions.forEach { sub ->
            subscriptionEventRepository.insert(
                SubscriptionEvent(
                    id = IDV7.generate(),
                    subscriptionId = sub.id,
                    sequence = nextSequenceFor(sub.id),
                    payload = event.payload,
                    status = "PENDING"
                )
            )
        }
    }
}
```

### Outbox Relay (Merged Claim + Send)

A single relay process handles claiming, sending, and marking complete:

```kotlin
class SubscriptionEventOutbox(
    private val repository: SubscriptionEventRepository,
    private val emitter: BrokerEmitter
) {

    // Runs periodically (e.g., every 100ms)
    suspend fun processBatch(batchSize: Int = 100) {
        repeat(batchSize) {
            val claimed = claimNext() ?: return
            try {
                emitter.send(claimed.toBrokerMessage())
                repository.markSent(claimed.id)
            } catch (e: Exception) {
                // On failure: release claim or mark for retry
                repository.releaseClaim(claimed.id)
            }
        }
    }

    private suspend fun claimNext(): SubscriptionEvent? {
        return repository.claimNextEvent()
    }
}
```

### Claim Query (Unified for Both Modes)

The claim query respects both FIFO and PARALLEL constraints.

#### PostgreSQL

```sql
UPDATE subscription_events
SET status     = 'PROCESSING',
    claimed_at = NOW()
WHERE id = (SELECT e.id
            FROM subscription_events e
                     JOIN subscriptions s ON s.id = e.subscription_id
            WHERE e.status = 'PENDING'
              AND (SELECT COUNT(*)
                   FROM subscription_events
                   WHERE subscription_id = e.subscription_id
                     AND status = 'PROCESSING') < CASE
                                                      WHEN s.processing_mode = 'FIFO' THEN 1
                                                      ELSE s.max_concurrent
                                                  END
            ORDER BY e.subscription_id, e.sequence
            LIMIT 1
            FOR UPDATE SKIP LOCKED)
RETURNING *;
```

#### MySQL

MySQL doesn't support `RETURNING`, so use a two-step approach:

```sql
-- Step 1: Claim with SELECT ... FOR UPDATE SKIP LOCKED
START TRANSACTION;

SELECT e.id INTO @claimed_id
FROM subscription_events e
         JOIN subscriptions s ON s.id = e.subscription_id
WHERE e.status = 'PENDING'
  AND (SELECT COUNT(*)
       FROM subscription_events
       WHERE subscription_id = e.subscription_id
         AND status = 'PROCESSING') < CASE
                                          WHEN s.processing_mode = 'FIFO' THEN 1
                                          ELSE s.max_concurrent
                                      END
ORDER BY e.subscription_id, e.sequence
LIMIT 1
FOR UPDATE SKIP LOCKED;

-- Step 2: Update if found
UPDATE subscription_events
SET status     = 'PROCESSING',
    claimed_at = NOW()
WHERE id = @claimed_id;

COMMIT;

-- Step 3: Fetch the claimed row
SELECT * FROM subscription_events WHERE id = @claimed_id;
```

#### H2 (2.2.220+)

H2 supports `SKIP LOCKED` since version 2.2.220 (July 2023). Use the same query as PostgreSQL:

```sql
UPDATE subscription_events
SET status     = 'PROCESSING',
    claimed_at = NOW()
WHERE id = (SELECT e.id
            FROM subscription_events e
                     JOIN subscriptions s ON s.id = e.subscription_id
            WHERE e.status = 'PENDING'
              AND (SELECT COUNT(*)
                   FROM subscription_events
                   WHERE subscription_id = e.subscription_id
                     AND status = 'PROCESSING') < CASE
                                                      WHEN s.processing_mode = 'FIFO' THEN 1
                                                      ELSE s.max_concurrent
                                                  END
            ORDER BY e.subscription_id, e.sequence
            LIMIT 1
            FOR UPDATE SKIP LOCKED);
```

**Note**: H2 versions prior to 2.2.220 do not support `SKIP LOCKED`. For older versions, use `FOR UPDATE` without
`SKIP LOCKED`, but this limits you to single-worker scenarios (concurrent workers will block on the same row).

#### Key Aspects

- **FIFO constraint**: `CASE WHEN s.processing_mode = 'FIFO' THEN 1` ensures only one in-flight event
- **PARALLEL constraint**: `s.max_concurrent` allows multiple in-flight up to the configured limit
- **FOR UPDATE SKIP LOCKED**: Prevents contention between multiple outbox workers (PostgreSQL/MySQL only)
- **ORDER BY sequence**: Always prefers older events first

### Crash Recovery

If a worker crashes while processing, the `claimed_at` timestamp enables recovery:

```sql
-- PostgreSQL / H2: Release stale claims (e.g., older than 5 minutes)
UPDATE subscription_events
SET status     = 'PENDING',
    claimed_at = NULL
WHERE status = 'PROCESSING'
  AND claimed_at < NOW() - INTERVAL '5 minutes';

-- MySQL: Different interval syntax
UPDATE subscription_events
SET status     = 'PENDING',
    claimed_at = NULL
WHERE status = 'PROCESSING'
  AND claimed_at < NOW() - INTERVAL 5 MINUTE;
```

This can run periodically or be integrated into the outbox relay startup.

### Cleanup

Old completed events should be cleaned up periodically:

```sql
-- PostgreSQL / H2
DELETE FROM subscription_events
WHERE status = 'SENT'
  AND sent_at < NOW() - INTERVAL '7 days';

-- MySQL
DELETE FROM subscription_events
WHERE status = 'SENT'
  AND sent_at < NOW() - INTERVAL 7 DAY;
```

## Consequences

### Positive

- **Flexible ordering**: Supports both FIFO and parallel modes per subscription
- **Scalability**: Handles common case (1:1) efficiently, rare spikes (1:M) via batching
- **No double processing**: `FOR UPDATE SKIP LOCKED` + status tracking prevents duplicates
- **Simple architecture**: Single outbox relay, no separate claim/send processes
- **Crash resilient**: `claimed_at` timestamp enables recovery from worker failures
- **Multi-database support**: Works with PostgreSQL, MySQL, and H2 (2.2.220+) with full `SKIP LOCKED` support

### Negative

- **Sequence assignment overhead**: Must track next sequence per subscription
- **Fan-out spikes cause lag**: Inserting millions of rows takes time, outbox catches up
- **Single database dependency**: Database becomes the bottleneck under extreme load
- **H2 version requirement**: Requires H2 2.2.220+ for `SKIP LOCKED` support (older versions limited to single-worker)
- **Database-specific SQL**: Requires maintaining separate queries for PostgreSQL, MySQL, and H2

### Neutral

- **Polling-based**: Outbox relay polls periodically rather than event-driven
- **Eventual consistency**: Events may not be sent immediately (depends on polling interval)

## Alternatives Considered

### 1. Two-Phase Outbox (Separate Claim and Send)

Claim events in one process, send in another.

**Rejected because:**

- Adds latency and complexity with no benefit
- Single merged relay is simpler and equally reliable

### 2. Lazy Evaluation (No Fan-Out)

Store events once, evaluate subscription matches at read time.

**Rejected because:**

- Slower reads (filter evaluation on every poll)
- Better suited when fan-out is always large, not our common case (1:1)

### 3. Partitioned Fan-Out

Fan-out to a small number of partitions, evaluate subscriptions within each partition.

**Rejected because:**

- Adds partition management complexity
- Our common case (1:1 fan-out) doesn't benefit
- Could be reconsidered if fan-out spikes become more frequent

### 4. Head-of-Line Tracking on Subscription

Track `processing_sequence` and `next_sequence` on the subscription table instead of counting PROCESSING events.

**Rejected because:**

- Requires updating two tables (subscription + subscription_events) per claim
- COUNT-based approach is simpler and equally performant with proper indexing
- Could be reconsidered if subscription table updates become acceptable

## References

- [ADR-0003 Messaging Architecture](./0003-messaging-architecture.md)
- [ADR-0004 Database Storage Strategy](./0004-database-storage-strategy.md)
- [ADR-0012 Listen Task CloudEvent Processing](./0012-listen-task-cloudevent-processing.md)
- [Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
