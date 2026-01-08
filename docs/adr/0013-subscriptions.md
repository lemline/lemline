# [ADR-0013] Subscription Event Queue Architecture

## Status

Proposed (Revised)

## Context

Subscriptions receive events and process them through configured operations.

**Hard requirement:**
Operations for an event must complete before the next event starts when FIFO ordering is enabled.

Some subscriptions may explicitly opt into parallel processing with bounded concurrency.

The system must support:

1. **FIFO ordering (completion-based):** Event N+1 must not start before N completes
2. **Parallel processing:** Multiple in-flight events per subscription, bounded
3. **Fan-out scenarios:** One event → 1 to millions of subscriptions
4. **Reliable delivery:** At-least-once delivery with deterministic idempotency
5. **Scalability:** No unbounded scans or hot-path aggregation

This ADR defines a database-backed subscription work queue with explicit ordering, concurrency tracking, and crash
recovery.

---

## Decision

We implement a sequence-based, completion-gated subscription queue with:

- Database-owned sequence allocation
- Explicit in-flight counters per subscription
- Completion acknowledgments
- Idempotent fan-out
- Fair scheduling

This design provides strict FIFO where required, bounded parallelism where allowed, and predictable performance under
load.

---

## Architecture Overview

```
EVENT ARRIVES
      │
      ▼
Filter evaluation (match subscriptions)
      │
      ▼
Idempotent fan-out (subscription_events)
      │
      ▼
Dispatcher (DB-backed work queue)
      │
      ▼
Message Broker
      │
      ▼
Subscription Worker
      │
      ▼
Completion ACK → DB
```

The database is the system of record for ordering and concurrency.
The broker provides transport only.

---

## Database Schema

### Subscriptions

```sql
CREATE TABLE subscriptions (
    id                UUID PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    filter            JSONB NULL,
    processing_mode   VARCHAR(10) NOT NULL DEFAULT 'FIFO',  -- FIFO | PARALLEL
    max_concurrent    INT NOT NULL DEFAULT 1,
    in_flight         INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### Sequence Allocation

```sql
CREATE TABLE subscription_sequences (
    subscription_id   UUID PRIMARY KEY REFERENCES subscriptions(id),
    next_sequence     BIGINT NOT NULL DEFAULT 1
);
```

> **Design note:** Sequences are stored in a separate table to minimize lock contention. When allocating sequences
> during high-throughput fan-out, this avoids locking the entire `subscriptions` row (which is also updated for
`in_flight` counters).

### Subscription Events

```sql
CREATE TABLE subscription_events (
    id                UUID PRIMARY KEY,
    event_id          UUID NOT NULL,
    subscription_id   UUID NOT NULL REFERENCES subscriptions(id),
    sequence          BIGINT NOT NULL,
    payload           JSONB NOT NULL,
    status            VARCHAR(20) NOT NULL
                      CHECK (status IN ('PENDING','PROCESSING','SENT','COMPLETED','FAILED')),
    retry_count       INT NOT NULL DEFAULT 0,
    claimed_at        TIMESTAMPTZ(6),
    sent_at           TIMESTAMPTZ(6),
    completed_at      TIMESTAMPTZ(6),
    created_at        TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (subscription_id, sequence),
    UNIQUE (subscription_id, event_id)
);
```

### Indexes

```sql
-- Optimized for the claim query ORDER BY
CREATE INDEX idx_events_pending
    ON subscription_events (status, created_at, sequence)
    WHERE status = 'PENDING';

CREATE INDEX idx_events_subscription
    ON subscription_events (subscription_id, status, sequence);

-- For timeout recovery scans
CREATE INDEX idx_events_processing_timeout
    ON subscription_events (status, claimed_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_events_sent_timeout
    ON subscription_events (status, sent_at)
    WHERE status = 'SENT';
```

---

## Key Concepts

### Sequence Numbers

Sequences are allocated atomically by the database per subscription and define arrival order.

```sql
UPDATE subscription_sequences
SET next_sequence = next_sequence + 1
WHERE subscription_id = :subscriptionId
RETURNING next_sequence - 1 AS allocated_sequence;
```

Gaps are allowed. Ordering is enforced by completion checks, not by contiguity.

### Event Identity

`event_id` ensures idempotent fan-out. Replaying the same event cannot create duplicates.

---

## Fan-Out (Idempotent)

```sql
INSERT INTO subscription_events (
    id, event_id, subscription_id, sequence, payload, status
) VALUES (
    :id, :eventId, :subscriptionId, :sequence, :payload, 'PENDING'
)
ON CONFLICT (subscription_id, event_id) DO NOTHING;
```

Fan-out is retry-safe and restartable.

---

## Dispatcher (Claiming Work)

The dispatcher is a database-backed work queue, not a transactional outbox.

### FIFO Claim Rule

For FIFO subscriptions, an event can be claimed only if no earlier event is incomplete:

```sql
AND NOT EXISTS (
    SELECT 1 FROM subscription_events e2
    WHERE e2.subscription_id = e.subscription_id
      AND e2.sequence < e.sequence
      AND e2.status IN ('PROCESSING', 'SENT')
)
```

### Parallel Claim Rule

For PARALLEL subscriptions, claiming is gated by the `in_flight` counter. The counter increment must be atomic with the
event claim (see below).

### Claim Query (PostgreSQL) — FIFO Mode

```sql
WITH next_event AS (
    SELECT e.id, e.subscription_id
    FROM subscription_events e
    JOIN subscriptions s ON s.id = e.subscription_id
    WHERE e.status = 'PENDING'
      AND s.processing_mode = 'FIFO'
      AND NOT EXISTS (
          SELECT 1 FROM subscription_events e2
          WHERE e2.subscription_id = e.subscription_id
            AND e2.sequence < e.sequence
            AND e2.status IN ('PROCESSING', 'SENT')
      )
    ORDER BY e.created_at, e.sequence
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
UPDATE subscription_events e
SET status = 'PROCESSING', claimed_at = NOW()
FROM next_event ne
WHERE e.id = ne.id
RETURNING e.*;
```

### Claim Query (PostgreSQL) — PARALLEL Mode

Parallel mode requires atomic increment of `in_flight`. This is done in a single transaction:

```sql
WITH eligible_events AS (
    SELECT e.id, e.subscription_id
    FROM subscription_events e
    JOIN subscriptions s ON s.id = e.subscription_id
    WHERE e.status = 'PENDING'
      AND s.processing_mode = 'PARALLEL'
      AND s.in_flight < s.max_concurrent
    ORDER BY e.created_at, e.sequence
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
),
increment_flight AS (
    UPDATE subscriptions s
    SET in_flight = in_flight + 1
    FROM eligible_events ee
    WHERE s.id = ee.subscription_id
      AND s.in_flight < s.max_concurrent
    RETURNING s.id AS subscription_id
)
UPDATE subscription_events e
SET status = 'PROCESSING', claimed_at = NOW()
FROM eligible_events ee
WHERE e.id = ee.id
  AND EXISTS (SELECT 1 FROM increment_flight inf WHERE inf.subscription_id = ee.subscription_id)
RETURNING e.*;
```

> **Note:** If multiple dispatchers are running, `FOR UPDATE SKIP LOCKED` ensures they don't contend on the same events.
> The `in_flight < max_concurrent` check is re-verified during the increment to prevent races.

---

## Sending and Completion

1. Dispatcher claims event → status = `PROCESSING`
2. Dispatcher sends event to broker → status = `SENT`, `sent_at = NOW()`
3. Worker processes event
4. Worker sends completion ACK
5. Status → `COMPLETED`, `completed_at = NOW()`
6. `in_flight` decremented (for PARALLEL mode)

FIFO progression depends on `COMPLETED`, not `SENT`.

---

## Failure Handling

### Event Failure (Worker Reports Failure)

When a worker reports that processing failed:

```sql
UPDATE subscription_events
SET status = 'FAILED',
    completed_at = NOW(),
    retry_count = retry_count + 1
WHERE id = :eventId;

-- Decrement in_flight for PARALLEL mode
UPDATE subscriptions
SET in_flight = in_flight - 1
WHERE id = :subscriptionId
  AND in_flight > 0;
```

### FAILED Event Semantics

FAILED events are treated as "completed" for ordering purposes:

- **FIFO mode:** Subsequent events will proceed. A failed event does not block the queue.
- **Retry:** Application-level retry logic can reset status to `PENDING` if desired.
- **Dead-letter:** Events exceeding a retry threshold should be moved to a dead-letter table or flagged for manual
  review.

```sql
-- Optional: Move to dead-letter after N retries
INSERT INTO subscription_events_dlq (...)
SELECT * FROM subscription_events
WHERE status = 'FAILED' AND retry_count >= :maxRetries;

DELETE FROM subscription_events
WHERE status = 'FAILED' AND retry_count >= :maxRetries;
```

---

## Crash Recovery

### Dispatcher Crash (PROCESSING Timeout)

Events stuck in `PROCESSING` are reclaimed after timeout:

```sql
UPDATE subscription_events
SET status = 'PENDING',
    claimed_at = NULL,
    retry_count = retry_count + 1
WHERE status = 'PROCESSING'
  AND claimed_at < NOW() - INTERVAL '5 minutes'
RETURNING subscription_id;

-- Decrement in_flight for affected PARALLEL subscriptions
UPDATE subscriptions
SET in_flight = GREATEST(0, in_flight - 1)
WHERE id IN (:affectedSubscriptionIds)
  AND processing_mode = 'PARALLEL';
```

### Worker Crash (SENT Timeout)

Events stuck in `SENT` (worker never ACKed) are reclaimed after timeout:

```sql
UPDATE subscription_events
SET status = 'PENDING',
    sent_at = NULL,
    claimed_at = NULL,
    retry_count = retry_count + 1
WHERE status = 'SENT'
  AND sent_at < NOW() - INTERVAL '10 minutes'
RETURNING subscription_id;

-- Decrement in_flight for affected PARALLEL subscriptions
UPDATE subscriptions
SET in_flight = GREATEST(0, in_flight - 1)
WHERE id IN (:affectedSubscriptionIds)
  AND processing_mode = 'PARALLEL';
```

### Idempotency Requirement

Duplicate delivery may occur after recovery.
Consumers **must** be idempotent on `(subscription_id, sequence)`.

Delivery semantics: **at-least-once**.

---

## Cleanup Strategy

### Correctness Note

The FIFO ordering check only considers `PROCESSING` and `SENT` statuses. Therefore, `COMPLETED` and `FAILED` rows can
technically be deleted without breaking ordering guarantees. However, retaining them (as tombstones) provides:

- Audit trail
- Easier debugging
- Protection against edge cases during recovery

### Recommended Approach: Tombstones

```sql
UPDATE subscription_events
SET payload = NULL
WHERE status IN ('COMPLETED', 'FAILED')
  AND completed_at < NOW() - INTERVAL '7 days';
```

### Alternative: Archive and Delete

```sql
-- Archive to cold storage
INSERT INTO subscription_events_archive
SELECT * FROM subscription_events
WHERE status IN ('COMPLETED', 'FAILED')
  AND completed_at < NOW() - INTERVAL '7 days';

-- Then delete
DELETE FROM subscription_events
WHERE status IN ('COMPLETED', 'FAILED')
  AND completed_at < NOW() - INTERVAL '7 days';
```

---

## Database Support Notes

### PostgreSQL

Full support. Reference implementation.

### MySQL

Limitations:

- No partial indexes
- No `RETURNING`
- Two-phase claims required (SELECT then UPDATE)

Guarantees:

- FIFO mode: Safe
- PARALLEL mode: Requires `SERIALIZABLE` isolation or single dispatcher

### H2

Supported for development/testing only.

---

## Consequences

### Positive

- True FIFO (completion-based, not send-based)
- Predictable parallelism with bounded concurrency
- No hot-path aggregation
- Idempotent fan-out
- Fair scheduling across subscriptions
- Explicit delivery semantics
- Observable via `retry_count`

### Trade-offs

- Requires completion ACKs from workers
- More state tracking than fire-and-forget
- Database remains a scaling boundary (by design)
- PARALLEL mode claim query is more complex

---

## Terminology

- **Dispatcher:** DB-backed work queue processor that claims and sends events
- **FIFO:** Completion-gated ordering (event N+1 waits for N to complete)
- **Delivery:** At-least-once semantics
- **Tombstone:** Completed row with `payload = NULL` for audit/safety

---

## References

- ADR-0003 Messaging Architecture
- ADR-0004 Database Storage Strategy
- ADR-0012 Listen Task CloudEvent Processing
