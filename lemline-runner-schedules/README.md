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

```
┌─────────────────────────────────────────────────────────────────┐
│                   lemline-runner-schedules                      │
├─────────────────────────────────────────────────────────────────┤
│  ScheduleService            ← Business logic for schedules     │
│  └── scheduleAfterCompletion() ← Reschedule after workflow ends│
│                                                                 │
│  ScheduleModel              ← Schedule state entity             │
│  ├── id                     ← Schedule identifier              │
│  ├── instanceMessage        ← Workflow init command            │
│  ├── scheduleAfter          ← ISO 8601 duration (e.g., PT1H)   │
│  ├── scheduleEvery          ← ISO 8601 duration (e.g., PT5M)   │
│  ├── scheduleCron           ← Unix cron expression             │
│  ├── scheduleZone           ← IANA timezone                    │
│  └── outbox fields          ← Processing state                 │
│                                                                 │
│  ScheduleOutbox             ← Trigger scheduled workflows      │
│  └── process()              ← Emit workflow init command       │
│                                                                 │
│  ScheduleRepository         ← Database operations              │
│  ScheduleCleaner            ← Cleanup expired schedules        │
└─────────────────────────────────────────────────────────────────┘
```

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
| **Every** | `scheduleEvery` | ISO 8601 duration | Now + duration |
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

### Cron/Every Schedule Flow

```
┌─────────────────┐   definition post   ┌─────────────────┐
│ Workflow with   │ ─────────────────▶  │ Insert schedule │
│ schedule config │                     │ with next time  │
└─────────────────┘                     └────────┬────────┘
                                                 │
                          [wait until scheduled time]
                                                 │
                                                 ▼
                                        ┌─────────────────┐
                                        │ ScheduleOutbox  │
                                        │ (polls for due) │
                                        └────────┬────────┘
                                                 │
                                                 ▼
                                        ┌─────────────────┐
                                        │ Emit workflow   │
                                        │ init command    │
                                        └────────┬────────┘
                                                 │
                                                 ▼
                                        ┌─────────────────┐
                                        │ Calculate next  │
                                        │ scheduled time  │
                                        └─────────────────┘
```

### After-Completion Schedule Flow

```
┌─────────────────┐   workflow completed   ┌─────────────────┐
│    Workflow     │ ─────────────────────▶ │ ScheduleService │
│   Completes     │                        │                 │
└─────────────────┘                        └────────┬────────┘
                                                    │
                                                    ▼
                                           ┌─────────────────┐
                                           │ Update schedule │
                                           │ delayed_until = │
                                           │ now + after     │
                                           └────────┬────────┘
                                                    │
                             [wait until delayed time]
                                                    │
                                                    ▼
                                           ┌─────────────────┐
                                           │ ScheduleOutbox  │
                                           │ triggers next   │
                                           └─────────────────┘
```

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
| `id` | UUID | Schedule identifier |
| `instance_message` | TEXT | Serialized workflow init command |
| `schedule_after` | VARCHAR(255) | ISO 8601 duration (PT1H) |
| `schedule_every` | VARCHAR(255) | ISO 8601 duration (PT5M) |
| `schedule_cron` | VARCHAR(255) | Unix cron expression |
| `schedule_zone` | VARCHAR(255) | IANA timezone (America/New_York) |
| `outbox_scheduled_for` | TIMESTAMP | Next scheduled execution |
| `outbox_delayed_until` | TIMESTAMP | When to process |
| `outbox_attempt_count` | INT | Processing attempts |
| `outbox_completed_at` | TIMESTAMP | When schedule exhausted |
| `outbox_failed_at` | TIMESTAMP | When outbox gave up |
| `cleanup_after` | TIMESTAMP | Eligible for deletion |

### Schedule Lifecycle

| Type | Completed When |
|------|----------------|
| **Cron** | No more future executions (e.g., specific date passed) |
| **Every** | Never (runs forever until deleted) |
| **After** | Never (runs forever until deleted) |
