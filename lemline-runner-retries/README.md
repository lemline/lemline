# lemline-runner-retries

> Task retry scheduling with exponential backoff

## Purpose

This module implements the retry mechanism for failed tasks:
- **Scheduled retry** when a task fails and has retry policy
- **State preservation** during retry delay
- **Error tracking** for debugging and metrics

## Serverless Workflow DSL Reference

See [Retry Policy](https://serverlessworkflow.io/spec/latest/dsl-reference/#retry) in the Serverless Workflow specification:

```yaml
do:
  - callApi:
      call: http
      with:
        method: POST
        endpoint: https://api.example.com/orders
      retry:
        when: ${ .error.status >= 500 }
        limit:
          attempt:
            count: 3
          duration: PT1M
        delay: PT10S
        backoff:
          exponential:
            base: 2
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    lemline-runner-retries                       │
├─────────────────────────────────────────────────────────────────┤
│  RetryService               ← Business logic for retry events  │
│  └── handleRetryScheduled() ← Create retry record              │
│                                                                 │
│  RetryModel                 ← Retry state entity                │
│  ├── id                     ← Derived from position + step     │
│  ├── instanceMessage        ← Workflow state for retry         │
│  ├── outboxScheduledFor     ← When to attempt retry            │
│  ├── errorReason            ← Why task failed                  │
│  ├── errorClass             ← Exception class                  │
│  ├── errorMessage           ← Exception message                │
│  └── errorStackTrace        ← Full stack trace                 │
│                                                                 │
│  RetryOutbox                ← Process due retries              │
│  └── process()              ← Resume workflow for retry        │
│                                                                 │
│  RetryRepository            ← Database operations              │
│  RetryCleaner               ← Cleanup completed retries        │
└─────────────────────────────────────────────────────────────────┘
```

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Retry Policy** | DSL configuration for when/how to retry (attempts, delay, backoff) |
| **Scheduled Retry** | Retry attempt scheduled for future time |
| **Outbox Processing** | `RetryOutbox` polls for due retries and emits resume commands |
| **Error Preservation** | Original error stored for debugging |

## File Reference

| File | Responsibility |
|------|----------------|
| `RetryService.kt` | Handle retry scheduled events |
| `RetryModel.kt` | Retry state with error tracking |
| `RetryOutbox.kt` | Process due retries, resume workflows |
| `RetryRepository.kt` | Database operations for retry records |
| `RetryCleaner.kt` | Cleanup completed retry records |
| `RetryConfig.kt` | Configuration for retry feature |

## How It Works

### Retry Scheduling Flow

```
┌─────────────┐    TaskRetryScheduled   ┌─────────────────┐
│   Workflow  │ ───────────────────────▶│  RetryService   │
│  Processor  │    (task failed)        │                 │
└─────────────┘                         └────────┬────────┘
                                                 │
                                                 ▼
                                        ┌─────────────────┐
                                        │ Insert retry    │
                                        │ record with     │
                                        │ delay time      │
                                        └────────┬────────┘
                                                 │
                            [wait until delay passes]
                                                 │
                                                 ▼
                                        ┌─────────────────┐
                                        │  RetryOutbox    │
                                        │ (polls for due) │
                                        └────────┬────────┘
                                                 │
                                                 ▼
                                        ┌─────────────────┐
                                        │ Resume workflow │
                                        │ from failed     │
                                        │ task position   │
                                        └─────────────────┘
```

### Retry State Machine

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

## Dependencies

| Depends On | Used By |
|------------|---------|
| `lemline-runner-common` | `lemline-runner` (event handlers) |
| `lemline-core` | - |

## Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| **Custom retry logic** | Modify `RetryOutbox.process()` |
| **Retry metrics** | Add metrics in `RetryService` |

## Database Table

### `lemline_retries`

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Derived from position + step |
| `instance_message` | TEXT | Serialized workflow state |
| `error_reason` | VARCHAR(255) | Low-cardinality failure category |
| `error_class` | VARCHAR(255) | Exception class name |
| `error_message` | TEXT | Exception message |
| `error_stacktrace` | TEXT | Full stack trace |
| `outbox_scheduled_for` | TIMESTAMP | Original scheduled time |
| `outbox_delayed_until` | TIMESTAMP | When to attempt (with backoff) |
| `outbox_attempt_count` | INT | Outbox processing attempts |
| `outbox_completed_at` | TIMESTAMP | When retry was processed |
| `outbox_failed_at` | TIMESTAMP | When outbox gave up |
| `cleanup_after` | TIMESTAMP | Eligible for deletion |

### Note on Retry vs Outbox Attempts

- **Retry attempt** = DSL-level task retry (counted by `lemline-core`)
- **Outbox attempt** = Infrastructure-level message delivery retry
