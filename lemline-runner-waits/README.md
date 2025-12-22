# lemline-runner-waits

> Wait/sleep functionality for workflows

## Purpose

This module implements the `wait` task from Serverless Workflow DSL:
- **Duration-based waits** - Wait for a specified duration (e.g., PT10S)
- **Timestamp-based waits** - Wait until a specific time

## Serverless Workflow DSL Reference

See [Wait Task](https://serverlessworkflow.io/spec/latest/dsl-reference/#wait) in the Serverless Workflow specification:

```yaml
do:
  - pauseExecution:
      wait:
        # Duration-based wait
        duration: PT30S  # Wait 30 seconds

        # OR timestamp-based wait
        until: "2024-12-31T23:59:59Z"  # Wait until specific time
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     lemline-runner-waits                        │
├─────────────────────────────────────────────────────────────────┤
│  WaitService                ← Business logic for wait events   │
│  └── handleWaitStarted()    ← Create wait record               │
│                                                                 │
│  WaitModel                  ← Wait state entity                 │
│  ├── id                     ← Derived from position + step     │
│  ├── instanceMessage        ← Workflow state for resumption    │
│  ├── outboxScheduledFor     ← When wait should end             │
│  └── outbox fields          ← Processing state                 │
│                                                                 │
│  WaitOutbox                 ← Process due waits                 │
│  └── process()              ← Resume workflow after wait       │
│                                                                 │
│  WaitRepository             ← Database operations              │
│  WaitCleaner                ← Cleanup completed waits          │
└─────────────────────────────────────────────────────────────────┘
```

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Wait Task** | Pauses workflow execution for a duration or until a timestamp |
| **State Preservation** | Workflow state stored in database during wait |
| **Outbox Processing** | `WaitOutbox` polls for due waits and resumes workflows |
| **Idempotent Resume** | Wait only processed once due to ID derivation |

## File Reference

| File | Responsibility |
|------|----------------|
| `WaitService.kt` | Handle wait started events, create wait records |
| `WaitModel.kt` | Wait state with scheduled end time |
| `WaitOutbox.kt` | Process due waits, resume workflows |
| `WaitRepository.kt` | Database operations for wait records |
| `WaitCleaner.kt` | Cleanup completed wait records |
| `WaitConfig.kt` | Configuration for wait feature |

## How It Works

### Wait Execution Flow

```
┌─────────────┐     WaitStarted      ┌─────────────────┐
│   Workflow  │ ───────────────────▶ │   WaitService   │
│  Processor  │   (wait task hit)    │                 │
└─────────────┘                      └────────┬────────┘
                                              │
                                              ▼
                                     ┌─────────────────┐
                                     │ Insert wait     │
                                     │ record with     │
                                     │ end time        │
                                     └────────┬────────┘
                                              │
                       [wait period passes - workflow paused]
                                              │
                                              ▼
                                     ┌─────────────────┐
                                     │   WaitOutbox    │
                                     │ (polls for due) │
                                     └────────┬────────┘
                                              │
                                              ▼
                                     ┌─────────────────┐
                                     │ Resume workflow │
                                     │ from wait       │
                                     │ position        │
                                     └─────────────────┘
```

### Wait State Machine

```
┌─────────┐    insert    ┌─────────┐    outbox    ┌───────────┐
│ (start) │ ───────────▶ │ PENDING │ ───────────▶ │ COMPLETED │
└─────────┘              └────┬────┘              └───────────┘
                              │
                              │ outbox fails
                              ▼
                         ┌─────────┐
                         │ FAILED  │ (after max attempts)
                         └─────────┘
```

### Duration Calculation

Wait times are calculated by `lemline-core` before the event reaches this module:

```kotlin
// In lemline-core Processor
val waitUntil = when {
    wait.duration != null -> Clock.System.now() + wait.duration.toDuration()
    wait.until != null -> Instant.parse(wait.until)
    else -> error("Wait must have duration or until")
}
```

## Dependencies

| Depends On | Used By |
|------------|---------|
| `lemline-runner-common` | `lemline-runner` (event handlers) |
| `lemline-core` | - |

## Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| **Wait cancellation** | Add cancel method in `WaitService` |
| **Wait metrics** | Add metrics in `WaitOutbox` |

## Database Table

### `lemline_waits`

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Derived from position + step |
| `instance_message` | TEXT | Serialized workflow state |
| `outbox_scheduled_for` | TIMESTAMP | When wait should end |
| `outbox_delayed_until` | TIMESTAMP | When to process (with backoff) |
| `outbox_attempt_count` | INT | Processing attempts |
| `outbox_completed_at` | TIMESTAMP | When wait was processed |
| `outbox_failed_at` | TIMESTAMP | When outbox gave up |
| `outbox_error_*` | Various | Error tracking |
| `cleanup_after` | TIMESTAMP | Eligible for deletion |

### Index

```sql
CREATE INDEX idx_lemline_waits_pending
ON lemline_waits (outbox_delayed_until)
WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;
```

Used for efficient polling of due waits.
