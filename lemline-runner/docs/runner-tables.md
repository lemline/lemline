# Lemline Runner - Database Tables

This document covers the database tables used by the lemline-runner module.

## Overview

All tables are prefixed with `lemline_` and created via Flyway migrations.

**Migration locations:**
- PostgreSQL: `src/main/resources/db/migration/postgresql/`
- MySQL: `src/main/resources/db/migration/mysql/`
- H2: `src/main/resources/db/migration/h2/`

## Table Summary

| Table                   | Purpose                         | Pattern          | Cleanup        |
|-------------------------|---------------------------------|------------------|----------------|
| `lemline_definitions`   | Workflow YAML definitions       | Direct storage   | Manual via CLI |
| `lemline_waits`         | Timer/delay tasks               | Outbox           | Auto cleanup   |
| `lemline_retries`       | Task retry with backoff         | Outbox           | Auto cleanup   |
| `lemline_parents`       | Parent-child workflow tracking  | Event-driven     | Auto cleanup   |
| `lemline_schedules`     | Cron/interval scheduling        | Outbox           | Auto cleanup   |
| `lemline_failures`      | Permanent failure audit log     | Direct storage   | **Never**      |
| `lemline_forks`         | Fork (parallel branch) metadata | Pessimistic lock | Auto cleanup   |
| `lemline_fork_branches` | Individual branch status        | Part of fork     | Cascade delete |

---

## `lemline_definitions`

**Purpose:** Stores immutable workflow definitions (Serverless Workflow DSL v1.0 YAML).

**Key files:**
- Model: [DefinitionModel.kt](../src/main/kotlin/com/lemline/runner/models/DefinitionModel.kt)
- Repository: [DefinitionRepository.kt](../src/main/kotlin/com/lemline/runner/repositories/DefinitionRepository.kt)

**Schema:**

| Column       | Type         | Description                  |
|--------------|--------------|------------------------------|
| `namespace`  | VARCHAR(255) | Workflow namespace (PK part) |
| `name`       | VARCHAR(255) | Workflow name (PK part)      |
| `version`    | VARCHAR(255) | Workflow version (PK part)   |
| `definition` | TEXT         | Full YAML definition         |
| `created_at` | TIMESTAMP    | Creation timestamp           |
| `updated_at` | TIMESTAMP    | Last update timestamp        |

**Primary key:** `(namespace, name, version)`

**Writers:** CLI `definition post` command
**Readers:** `WorkflowCommandHandler`, `DefinitionCache`
**Cleanup:** Manual deletion via `definition delete` CLI

---

## `lemline_waits`

**Purpose:** Implements the **Wait task** - pauses workflow execution for a specified duration.

**Key files:**
- Model: [WaitModel.kt](../src/main/kotlin/com/lemline/runner/models/WaitModel.kt)
- Repository: [WaitRepository.kt](../src/main/kotlin/com/lemline/runner/repositories/WaitRepository.kt)
- Outbox: [WaitOutbox.kt](../src/main/kotlin/com/lemline/runner/outbox/WaitOutbox.kt)

**Schema:**

| Column Group   | Columns                                                                     |
|----------------|-----------------------------------------------------------------------------|
| Identity       | `id` (UUID PK)                                                              |
| Workflow state | `workflow_id`, `workflow_namespace`, `workflow_name`, `workflow_version`, `workflow_position`, `workflow_state` |
| Outbox         | `outbox_scheduled_for`, `outbox_delayed_until`, `outbox_attempt_count`, `outbox_error_*`, `outbox_completed_at`, `outbox_failed_at` |

**Processing flow:**

```
Wait task reached → WaitStarted event → Insert with delayed_until = NOW() + duration
                                                ↓
                                    [Time passes...]
                                                ↓
WaitOutbox finds due record → Emits ResumeWithCompletedTask → Mark completed → Cleanup
```

**Cleanup:** Auto-deleted after `outbox_completed_at < (NOW() - configured_age)`

---

## `lemline_retries`

**Purpose:** Implements **automatic task retry with exponential backoff**.

**Key files:**
- Model: [RetryModel.kt](../src/main/kotlin/com/lemline/runner/models/RetryModel.kt)
- Repository: [RetryRepository.kt](../src/main/kotlin/com/lemline/runner/repositories/RetryRepository.kt)
- Outbox: [RetryOutbox.kt](../src/main/kotlin/com/lemline/runner/outbox/RetryOutbox.kt)

**Additional columns (beyond waits):**

| Column             | Type    | Description          |
|--------------------|---------|----------------------|
| `error_reason`     | VARCHAR | Error classification |
| `error_class`      | TEXT    | Exception class name |
| `error_message`    | TEXT    | Error message        |
| `error_stacktrace` | TEXT    | Full stack trace     |

**Backoff calculation:**

```
baseDelay = initialDelay × 2^(attemptCount - 1)
actualDelay = baseDelay ± 20% (jitter)

Example with initialDelay = 10s:
  Attempt 1: 10s × 2^0 = 10s ± 2s
  Attempt 2: 10s × 2^1 = 20s ± 4s
  Attempt 3: 10s × 2^2 = 40s ± 8s
```

---

## `lemline_parents`

**Purpose:** Implements **RunWorkflow task** - tracks parent state while waiting for child workflow.

**Key files:**
- Model: [ParentModel.kt](../src/main/kotlin/com/lemline/runner/models/ParentModel.kt)
- Repository: [ParentRepository.kt](../src/main/kotlin/com/lemline/runner/repositories/ParentRepository.kt)
- Cleaner: [ParentCleaner.kt](../src/main/kotlin/com/lemline/runner/cleaner/ParentCleaner.kt)

**Schema:**

| Column                | Type      | Description                           |
|-----------------------|-----------|---------------------------------------|
| `id`                  | UUID      | Primary key                           |
| `child_id`            | UUID      | Child workflow ID (unique constraint) |
| `workflow_*`          | ...       | Parent workflow state (serialized)    |
| `outbox_completed_at` | TIMESTAMP | Set when child completes              |

**Processing flow:**

```
Parent reaches RunWorkflow → RunWorkflowStarted event
                                    ↓
                  Insert parent + emit child workflow command
                                    ↓
                        [Child workflow executes...]
                                    ↓
                  Child completes → WorkflowCompleted event
                                    ↓
        Find parent by child_id → Resume parent → Mark completed
```

---

## `lemline_schedules`

**Purpose:** Implements **scheduled workflow execution** - cron, intervals, or one-time delays.

**Key files:**
- Model: [ScheduleModel.kt](../src/main/kotlin/com/lemline/runner/models/ScheduleModel.kt)
- Repository: [ScheduleRepository.kt](../src/main/kotlin/com/lemline/runner/repositories/ScheduleRepository.kt)
- Outbox: [ScheduleOutbox.kt](../src/main/kotlin/com/lemline/runner/outbox/ScheduleOutbox.kt)

**Schema:**

| Column           | Type    | Description                        |
|------------------|---------|------------------------------------|
| `schedule_after` | VARCHAR | ISO 8601 duration (one-time delay) |
| `schedule_every` | VARCHAR | ISO 8601 duration (interval)       |
| `schedule_cron`  | VARCHAR | Unix cron expression               |
| `schedule_zone`  | VARCHAR | IANA timezone for cron             |

---

## `lemline_failures`

**Purpose:** **Permanent audit log** for workflow failures and message processing errors.

**Key files:**
- Model: [FailureModel.kt](../src/main/kotlin/com/lemline/runner/models/FailureModel.kt)
- Repository: [FailureRepository.kt](../src/main/kotlin/com/lemline/runner/repositories/FailureRepository.kt)

**Schema:**

| Column             | Type      | Description                              |
|--------------------|-----------|------------------------------------------|
| `id`               | UUID      | Primary key                              |
| `workflow_id`      | UUID      | Workflow ID (if available)               |
| `payload`          | TEXT      | Raw message payload (for deser failures) |
| `error_*`          | ...       | Error details                            |
| `created_at`       | TIMESTAMP | Failure timestamp                        |

**Cleanup:** **Never** - serves as permanent audit trail.

---

## `lemline_forks` + `lemline_fork_branches`

**Purpose:** Implements **Fork task** - parallel branch execution with two coordination modes.

**Key files:**
- Models: [ForkModel.kt](../src/main/kotlin/com/lemline/runner/models/ForkModel.kt), [ForkBranchModel.kt](../src/main/kotlin/com/lemline/runner/models/ForkBranchModel.kt)
- Repository: [ForkRepository.kt](../src/main/kotlin/com/lemline/runner/repositories/ForkRepository.kt)
- Cleaner: [ForkCleaner.kt](../src/main/kotlin/com/lemline/runner/cleaner/ForkCleaner.kt)

**Fork schema:**

| Column    | Type    | Description                    |
|-----------|---------|--------------------------------|
| `id`      | UUID    | Primary key                    |
| `compete` | BOOLEAN | Competition mode flag          |
| `output`  | TEXT    | Fork output (JSON)             |

**Unique constraint:** `(workflow_id, position)`

**Branch schema:**

| Column    | Type    | Description                     |
|-----------|---------|---------------------------------|
| `fork_id` | UUID    | Foreign key to forks (CASCADE)  |
| `name`    | VARCHAR | Branch name (from workflow def) |
| `output`  | TEXT    | Branch output (JSON)            |

**Primary key:** `(fork_id, name)`

**Competition modes:**

| Mode                      | Completes When         | Output                    | Fails When         |
|---------------------------|------------------------|---------------------------|--------------------|
| **Compete** (`true`)      | First branch completes | Single output from winner | All branches fail  |
| **Cooperative** (`false`) | All branches complete  | JSON array of all outputs | First branch fails |

---

## Outbox Pattern

Used by: `lemline_waits`, `lemline_retries`, `lemline_schedules`

**Implementation:**

```
1. Insert with outbox_completed_at = NULL, outbox_failed_at = NULL
                    ↓
2. Scheduled processor queries with FOR UPDATE SKIP LOCKED
                    ↓
3. Process entity (send message, etc.)
                    ↓
   Success: Set outbox_completed_at = NOW()
   Failure: Increment attempt_count, set delayed_until = NOW() + backoff
                    ↓
   Max attempts exceeded: Set outbox_failed_at = NOW()
                    ↓
4. Cleanup: Periodically delete WHERE outbox_completed_at < (NOW() - age)
```

**Key files:**
- [AbstractOutbox.kt](../src/main/kotlin/com/lemline/runner/outbox/AbstractOutbox.kt)
- [OutboxRepository.kt](../src/main/kotlin/com/lemline/runner/repositories/OutboxRepository.kt)
- [AbstractCleaner.kt](../src/main/kotlin/com/lemline/runner/cleaner/AbstractCleaner.kt)

**Benefits:**
- At-least-once delivery (messages survive crashes)
- No double-processing (`FOR UPDATE SKIP LOCKED`)
- Automatic retry with exponential backoff
- Graceful degradation (failed entities tracked, not lost)

---

## Adding a New Table

1. Create migration in `db/migration/{postgresql,mysql,h2}/V{N}__description.sql`
2. Create model in `models/` with data class
3. Create repository in `repositories/` implementing `Repository<T>`
4. If outbox pattern needed:
   - Extend `AbstractOutbox` for processing
   - Extend `AbstractCleaner` for cleanup
5. Add handler in `WorkflowEventHandler` or appropriate handler
6. Test with all supported databases
