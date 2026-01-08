# lemline-runner-common

> Shared infrastructure for all lemline-runner-* modules

## Purpose

This module provides the foundational building blocks used across all runner modules:
- **Outbox pattern** implementation for reliable message delivery
- **Cleaner pattern** for scheduled cleanup of completed records
- **Repository abstractions** for database operations
- **Model interfaces** for consistent entity design

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    lemline-runner-common                        │
├─────────────────────────────────────────────────────────────────┤
│  outbox/                                                        │
│  ├── AbstractOutbox<T>      ← Base for outbox processors        │
│                                                                 │
│  cleaner/                                                       │
│  ├── AbstractCleaner<T>     ← Base for cleanup schedulers       │
│                                                                 │
│  repositories/                                                  │
│  ├── ops/                   ← Repository operation helpers      │
│  │   ├── OutboxRepository   ← FOR UPDATE SKIP LOCKED queries    │
│  │   ├── CleanerRepository  ← Batch deletion queries            │
│  │   ├── CrudRepository     ← Insert/Update/Delete operations   │
│  │   └── IdRepository       ← UUID lookups                      │
│  └── with/                  ← Mixin interfaces for repositories │
│                                                                 │
│  models/                                                        │
│  ├── WithId                 ← Entity with IDV7 primary key      │
│  ├── WithOutbox             ← Outbox pattern fields             │
│  ├── WithCleanup            ← Cleanup scheduling field          │
│  ├── WithCompletedAt        ← Completion timestamp              │
│  └── WithInstanceMessage    ← Workflow state carrier            │
│                                                                 │
│  messaging/                                                     │
│  ├── InstanceMessage        ← Workflow state message            │
│  └── CommandEmitter         ← Interface for emitting commands   │
│                                                                 │
│  config/                                                        │
│  ├── DatabaseConfig         ← Connection and transaction mgmt   │
│  ├── OutboxAndCleanupConfig ← Outbox/cleaner configuration      │
│  └── MigrationManager       ← Flyway migration utilities        │
└─────────────────────────────────────────────────────────────────┘
```

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Outbox Pattern** | Store-then-send pattern ensuring at-least-once delivery. Messages are persisted before sending, processed via scheduled polling with `FOR UPDATE SKIP LOCKED`. |
| **Cleaner Pattern** | Scheduled batch deletion of old completed/failed records based on `cleanup_after` timestamp. |
| **IDV7** | UUID v7 (time-ordered) identifiers for all entities - globally unique and sortable by creation time. |
| **WithOutbox** | Interface adding outbox fields: `outboxDelayedUntil`, `outboxAttemptCount`, `outboxCompletedAt`, `outboxFailedAt`, error tracking. |
| **WithCleanup** | Interface adding `cleanupAfter` timestamp for scheduled deletion. |

## File Reference

| File | Responsibility |
|------|----------------|
| `outbox/AbstractOutbox.kt` | Base class for outbox processors with batch processing, exponential backoff, and retry logic |
| `cleaner/AbstractCleaner.kt` | Base class for cleanup schedulers with batch deletion |
| `scheduled/AbstractScheduledTask.kt` | Base class for Quarkus-scheduled tasks with graceful shutdown |
| `repositories/ops/OutboxRepository.kt` | `FOR UPDATE SKIP LOCKED` query implementation |
| `repositories/helpers/ColumnBindings.kt` | PreparedStatement column binding helpers |
| `models/WithOutbox.kt` | Outbox pattern interface (8 fields) |
| `models/WithCleanup.kt` | Cleanup interface (`cleanupAfter` field) |
| `messaging/InstanceMessage.kt` | Workflow state message carrying position and state |
| `config/DatabaseConfig.kt` | Connection pooling and transaction management |

## How It Works

### Outbox Processing Flow

1. **Insert** - Entity inserted with `outbox_delayed_until = scheduled_time`
2. **Poll** - `AbstractOutbox.doWork()` runs on schedule
3. **Lock** - `findEntitiesToProcess()` uses `FOR UPDATE SKIP LOCKED`
4. **Process** - Subclass `process(entity)` transforms and sends message
5. **Mark** - On success: `outbox_completed_at = now`, on failure: increment attempt, apply backoff
6. **Cleanup** - `AbstractCleaner` deletes where `cleanup_after < now - retention`

### Model Composition

```kotlin
// Typical model using common interfaces
data class MyModel(
    override val id: IDV7,                    // WithId
    override val instanceMessage: InstanceMessage<MyEvent>,  // WithInstanceMessage
    override val outboxScheduledFor: Instant, // WithOutbox
) : WithId, WithInstanceMessage, WithOutbox, WithCleanup {
    // WithOutbox fields
    override var outboxDelayedUntil: Instant? = outboxScheduledFor
    override var outboxAttemptCount: Int = 0
    // ... other outbox fields

    // WithCleanup field
    override var cleanupAfter: Instant? = null
}
```

## Dependencies

| Depends On | Used By |
|------------|---------|
| `lemline-common` | All `lemline-runner-*` modules |
| `lemline-core` | - |

## Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| **New outbox processor** | Extend `AbstractOutbox<T>`, implement `process(entity)` |
| **New cleaner** | Extend `AbstractCleaner<T>`, provide repository and config |
| **New model interface** | Create interface extending existing `With*` interfaces |
| **New repository operations** | Add to `repositories/ops/` and `repositories/with/` |

## Database Columns

### Outbox Columns (added to any outbox-enabled table)

| Column | Type | Description |
|--------|------|-------------|
| `outbox_scheduled_for` | TIMESTAMP | Original intended processing time |
| `outbox_delayed_until` | TIMESTAMP | Next processing attempt time (with backoff) |
| `outbox_attempt_count` | INT | Number of processing attempts |
| `outbox_completed_at` | TIMESTAMP | Successful completion timestamp |
| `outbox_failed_at` | TIMESTAMP | Permanent failure timestamp |
| `outbox_error_class` | VARCHAR | Exception class name |
| `outbox_error_message` | VARCHAR | Exception message |
| `outbox_error_stacktrace` | TEXT | Full stack trace |

### Cleanup Column

| Column | Type | Description |
|--------|------|-------------|
| `cleanup_after` | TIMESTAMP | Eligible for deletion after this time |
