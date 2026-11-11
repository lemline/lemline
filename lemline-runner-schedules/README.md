# lemline-runner-schedules

> Scheduled workflow execution (cron, interval, after)

## Purpose

This module implements scheduled workflow execution:
- **Cron schedules** - Unix cron expressions (e.g., `0 9 * * *` for daily at 9 AM)
- **Interval schedules** - Recurring execution (e.g., every 5 minutes)
- **After schedules** - Execute again after workflow completes

## Serverless Workflow DSL Reference

See [Schedule](https://serverlessworkflow.io/spec/latest/dsl-reference/#schedule) in the Serverless Workflow specification:

```yaml
document:
  name: dailyReport
  version: "1.0.0"
schedule:
  # Cron-based scheduling
  cron: "0 9 * * *"  # Daily at 9 AM

  # OR interval-based scheduling
  every: PT5M        # Every 5 minutes

  # OR after-completion scheduling
  after: PT1H        # 1 hour after completion
```

## Architecture

| Component | Responsibility |
|-----------|----------------|
| `ScheduleService` | Create a successor when a workflow completes |
| `ScheduleModel` | One occurrence, with a stable `scheduleId` for its recurrence chain |
| `ScheduleOutbox` | Publish due occurrences and create fixed-rate successors |
| `ScheduleRepository` | Persist occurrences and lock completion transitions |
| `ScheduleCleaner` | Remove occurrences after handoff and retention |

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Cron Schedule** | Unix cron expression with optional timezone |
| **Every Schedule** | Fixed interval between executions |
| **After Schedule** | Delay after previous execution completes |
| **New Workflow ID** | Each scheduled execution gets a unique workflow ID |

## Schedule Types

| Type | Field | Description | Next Calculation |
|------|-------|-------------|------------------|
| **Cron** | `scheduleCron` | Unix cron expression | Next cron match |
| **Every** | `scheduleEvery` | ISO 8601 duration | Previous deadline + duration |
| **After** | `scheduleAfter` | ISO 8601 duration | Completion + duration |

## File Reference

| File | Responsibility |
|------|----------------|
| `ScheduleService.kt` | Handle schedule-after-completion events |
| `ScheduleModel.kt` | Schedule entity with cron parsing and next time calculation |
| `ScheduleOutbox.kt` | Process due schedules, emit workflow messages |
| `ScheduleRepository.kt` | Database operations for schedule records |
| `ScheduleCleaner.kt` | Cleanup completed/expired schedules |
| `ScheduleConfig.kt` | Configuration for schedule feature |

## How It Works

Each row in `lemline_schedules` represents one occurrence. `schedule_id` identifies
its recurrence chain; `id` identifies the occurrence. Each successor copies the
schedule definition and input, so no separate schedule-definition table is required.

`outbox_scheduled_for` is the immutable occurrence deadline. `outbox_delayed_until`
can change during publication retries without changing the workflow or message ID.
IDs are deterministic and timestamp-based derivation uses SQL microsecond precision.

### Fixed-rate schedules

For `every` and `cron`, `ScheduleOutbox` publishes the current occurrence through the
commands channel. Once publication and its lifecycle hook succeed, it inserts the
next occurrence using the same transaction as the current row's completion update.
An insertion failure rolls back the batch; a broker publication may therefore be
replayed with the same identifiers. This is at-least-once publication, not an atomic
transaction spanning the broker and database.

The next deadline is calculated from the current deadline. Missed occurrences are
caught up in order; they are not skipped. Each occurrence has an independent retry
budget. A permanently failed publication stops that chain for operator inspection.

### Completion-based schedules

For `after`, the first immediate workflow has a tracking row with no outbox deadline.
After any occurrence is published, its row remains available until the corresponding
workflow completion is handled through the events channel.

`ScheduleService` locks the occurrence by workflow ID, inserts a deterministic
successor, and sets the current occurrence's `cleanup_after` in one transaction.
That marker also prevents duplicate completion events from creating another
successor, including after the successor has itself been cleaned up. A completion
received after the source row was cleaned up does not recreate the chain.

Published `after` occurrences are not eligible for cleanup while their workflow is
still running. Failed workflow handling is unchanged: only successful workflow
completion schedules the next occurrence. Failed workflows can therefore leave a
retained tracking row requiring operational intervention.

### Retention and upgrades

Fixed-rate occurrences become eligible for retention cleanup after successful
publication and successor creation. Completion-based occurrences become eligible
only after completion has created their successor. `AbstractOutbox` retains its
standard rule: successful publication completes the current row.

Migration V401 backfills `schedule_id` from existing row IDs on PostgreSQL, MySQL,
and H2. It does not automatically reactivate rows already marked completed by the
old implementation: those require review to avoid replaying previously executed
workflows.

### Cron Parsing

Uses [cron-utils](https://github.com/jmrozanec/cron-utils) for Unix cron expression parsing:

```kotlin
val cron = cronParser.parse("0 9 * * *")  // Daily at 9 AM
val next = ExecutionTime.forCron(cron)
    .nextExecution(now.atZone(zoneId))
    .map { it.toInstant() }
```

## Dependencies

| Depends On | Used By |
|------------|---------|
| `lemline-runner-common` | `lemline-runner` (event handlers) |
| `lemline-core` | `lemline-runner-parents` |

## Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| **Custom cron dialect** | Modify `CronType` in `ScheduleModel` |
| **Schedule pausing** | Add pause/resume logic in repository |

## Database Table

### `lemline_schedules`

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Occurrence identifier |
| `schedule_id` | UUID | Stable recurrence-chain identifier |
| `workflow_state` | TEXT | Workflow init command state encoded as ProtoJSON |
| `schedule_after` | VARCHAR(255) | ISO 8601 duration (PT1H) |
| `schedule_every` | VARCHAR(255) | ISO 8601 duration (PT5M) |
| `schedule_cron` | VARCHAR(255) | Unix cron expression |
| `schedule_zone` | VARCHAR(64) | IANA timezone (America/New_York) |
| `outbox_scheduled_for` | TIMESTAMP | Immutable occurrence deadline |
| `outbox_delayed_until` | TIMESTAMP | When to process |
| `outbox_attempt_count` | INT | Processing attempts |
| `outbox_completed_at` | TIMESTAMP | When occurrence publication succeeded |
| `outbox_failed_at` | TIMESTAMP | When outbox gave up |
| `cleanup_after` | TIMESTAMP | Eligible for deletion |

### Occurrence Lifecycle

| Type | Publication completed | Eligible for cleanup |
|------|-----------------------|----------------------|
| **Cron/Every** | Command and lifecycle hook succeed | Same transaction as successor creation, or when no next cron occurrence exists |
| **After** | Command and lifecycle hook succeed | Workflow completion has created the successor |

## Regression coverage

`ScheduleRecurrenceTestBase` runs against H2, PostgreSQL and MySQL. It covers repeated
cron/every occurrences, stable publication retries, lifecycle publication failure,
transaction rollback on successor insertion failure, and completion-based recurrence
with duplicate completion handling. Container-backed tests require Docker.
