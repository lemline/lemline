# Lemline Runner - Developer Guide

This document explains the architecture of the lemline-runner module, which provides the Quarkus-based runtime for
workflow execution.

## Table of Contents

- [Configuration](#configuration)
- [Messaging Architecture](#messaging-architecture)
- [Database Tables](#database-tables)
- [CLI Commands](#cli-commands)

---

## Configuration

### Configuration File Search Order

Lemline searches for configuration files in the following order (first found wins):

1. **CLI argument**: `--config=<path>` or `-c <path>`
2. **Environment variable**: `LEMLINE_CONFIG`
3. **Current directory**: `.lemline.yaml`
4. **XDG config**: `~/.config/lemline/config.yaml`
5. **Home directory**: `~/.lemline.yaml`

See: [LemlineApplication.kt](src/main/kotlin/com/lemline/runner/LemlineApplication.kt)

### Configuration Transformation

Lemline uses a custom `ConfigSource` (ordinal 275) that transforms `lemline.*` properties into Quarkus-specific
properties at runtime.

**Key files:**

- [LemlineConfiguration.kt](src/main/kotlin/com/lemline/runner/config/LemlineConfiguration.kt) - Type-safe configuration
  mapping
- [LemlineConfigSource.kt](src/main/kotlin/com/lemline/runner/config/LemlineConfigSource.kt) - Property transformation
- [LemlineConfigConstants.kt](src/main/kotlin/com/lemline/runner/config/LemlineConfigConstants.kt) - Default values
- [ExtraFileConfigFactory.kt](src/main/kotlin/com/lemline/runner/config/ExtraFileConfigFactory.kt) - YAML/properties
  loader

**Transformation flow:**

```
lemline.yaml (user config)
    ↓
LemlineConfigSource reads lemline.* properties
    ↓
Auto-detects database type (postgresql/mysql) and messaging type (kafka/rabbitmq)
    ↓
Generates quarkus.datasource.* and mp.messaging.* properties
    ↓
Quarkus runtime uses generated properties
```

### Supported Infrastructure Types

| Component | Types                                   | Auto-detection                           |
|-----------|-----------------------------------------|------------------------------------------|
| Database  | `in-memory` (H2), `postgresql`, `mysql` | Presence of `lemline.database.<type>.*`  |
| Messaging | `in-memory`, `kafka`, `rabbitmq`        | Presence of `lemline.messaging.<type>.*` |

### Example Configuration

```yaml
lemline:
    database:
        postgresql:
            host: localhost
            port: 5432
            username: postgres
            password: ${LEMLINE_PG_PASSWORD}
            name: lemline

    messaging:
        kafka:
            brokers: localhost:9092
            topic: lemline
            group-id: lemline-worker-group
```

---

## Messaging Architecture

Lemline uses a **dual-channel design** that separates high-throughput workflow execution from durable database
operations.

### Channel Overview

| Channel  | Topics                      | Purpose                                  |
|----------|-----------------------------|------------------------------------------|
| Workflow | `lemline-commands` (in/out) | High-throughput, stateless message flow  |
| Database | `lemline-events` (in/out)   | Durable operations requiring persistence |

### Commands Channel

The workflow channel handles the main execution flow. Messages carry compressed workflow state, enabling stateless
workers.

**Components:**

- **Input**: `commands-in` topic
- **Output**: `commands-out` topic
- **Handler**: [WorkflowCommandHandler](src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt)
- **Subscriber
  **: [WorkflowCommandSubscriber](src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandSubscriber.kt)
- **Emitter**: [WorkflowCommandEmitter](src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandEmitter.kt)
- **Message Type**: `InstanceMessage<WorkflowCommand>`

**WorkflowCommand types** (
see [WorkflowState.kt](../lemline-core/src/main/kotlin/com/lemline/core/states/WorkflowState.kt)):

| Command                   | Purpose                                                                       |
|---------------------------|-------------------------------------------------------------------------------|
| `ResumeFromTask`          | Resume workflow execution from a specific task position                       |
| `ResumeWithCompletedTask` | Resume with a task that completed asynchronously (wait, fork, child workflow) |
| `ResumeWithFailedTask`    | Resume with a task that failed asynchronously                                 |

### Events Channel

The database channel handles operations that require persistence (timers, retries, parent-child relationships).

**Components:**

- **Input**: `events-in` topic
- **Output**: `events-out` topic
- **Handler**: [WorkflowEventHandler](src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt)
- **Subscriber
  **: [WorkflowEventSubscriber](src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventSubscriber.kt)
- **Emitter**: [WorkflowEventEmitter](src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventEmitter.kt)
- **Message Type**: `InstanceMessage<WorkflowEvent>`

**WorkflowEvent types** (
see [WorkflowState.kt](../lemline-core/src/main/kotlin/com/lemline/core/states/WorkflowState.kt)):

Events are categorized into **Outcomes** (terminal states) and **Suspensions** (workflow paused, waiting to resume).

| Event (Outcome)     | Persisted To               | Purpose                          |
|---------------------|----------------------------|----------------------------------|
| `WorkflowCompleted` | (triggers parent/schedule) | Workflow completed successfully  |
| `WorkflowFailed`    | `lemline_failures`         | Workflow failed (uncaught error) |
| `BranchCompleted`   | `lemline_fork_branches`    | One fork branch completed        |
| `BranchFailed`      | `lemline_fork_branches`    | One fork branch failed           |

| Event (Suspension)   | Persisted To                  | Purpose                                |
|----------------------|-------------------------------|----------------------------------------|
| `TaskScheduled`      | (emitted to commands channel) | Next task ready to execute             |
| `WaitStarted`        | `lemline_waits`               | Timer/delay task started               |
| `RetryScheduled`     | `lemline_retries`             | Task retry with backoff scheduled      |
| `RunWorkflowStarted` | `lemline_parents`             | Child workflow started, parent waiting |
| `ForkStarted`        | `lemline_forks`               | Fork (parallel branches) started       |

### Messages Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  COMMANDS CHANNEL                                           │
│                                                                             │
│  commands ──► WorkflowCommandHandler ──► commands                           │
│       ▲                 │                                                   │
│       │                 │ (needs persistence)                               │
│       │                 │                                                   │
└───────│─────────────────│───────────────────────────────────────────────────┘
        │                 │
        │                 │
┌───────│─────────────────│───────────────────────────────────────────────────┐
│       │                 │              EVENTS CHANNEL                       │
│       │                 ▼                                                   │
│       │               events ──► WorkflowEventHandler ──► Database Tables   │
│       │                                   │                   │             │
│       │                                   │        Outbox     │             │
│       │                                   │      (when ready) │             │
│       └───────────────────────────────────┘───────────────────┘             │
│                                                                             │
│  Outbox Processors (scheduled):                                             │
│    WaitOutbox ────────► commands (after delay)                              │
│    RetryOutbox ───────► commands (after backoff)                            │
│    ScheduleOutbox ────► commands (on cron/interval)                         │
│                                                                             │
│    WorkflowEventHandler ────► commands (on child completion/failure)        │
│    WorkflowEventHandler ────► commands (on fork completion/failure)         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Key Design Principles

1. **State travels with messages**: The entire workflow state is serialized into `InstanceMessage`, enabling any worker
   to process any message without shared state.

2. **Database is only used when necessary**: Timers, retries, parent-child coordination, schedules, and failures.

3. **Horizontal scaling**: Workers are stateless and can scale independently.

**Key files:**

- [InstanceMessage.kt](src/main/kotlin/com/lemline/runner/messaging/InstanceMessage.kt) - Core message type with
  compressed state
- [MessageSubscriber.kt](src/main/kotlin/com/lemline/runner/messaging/MessageSubscriber.kt) - Base reactive subscriber
- [MessageHandler.kt](src/main/kotlin/com/lemline/runner/messaging/MessageHandler.kt) - Handler interface with ACK/NACK

---

## Database Tables

All tables are prefixed with `lemline_` and are created via Flyway migrations.

**Migration locations:**

- PostgreSQL: [db/migration/postgresql/](src/main/resources/db/migration/postgresql/)
- MySQL: [db/migration/mysql/](src/main/resources/db/migration/mysql/)
- H2: [db/migration/h2/](src/main/resources/db/migration/h2/)

### Table Summary

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

### `lemline_definitions`

**Purpose:** Stores immutable workflow definitions (Serverless Workflow DSL v1.0 YAML).

**Key files:**

- Model: [DefinitionModel.kt](src/main/kotlin/com/lemline/runner/models/DefinitionModel.kt)
- Repository: [DefinitionRepository.kt](src/main/kotlin/com/lemline/runner/repositories/DefinitionRepository.kt)
-

Migration: [V1__Create_lemline_definitions_table.sql](src/main/resources/db/migration/postgresql/V1__Create_lemline_definitions_table.sql)

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

**Who writes:**

- CLI command `definition post` uploads workflow definitions from YAML files
- Supports single file or directory (recursive) upload

**Who reads:**

- [WorkflowCommandHandler](src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt) loads
  definitions during message processing
- [DefinitionCache](../lemline-core/src/main/kotlin/com/lemline/core/definitions/DefinitionCache.kt) caches definitions
  for execution
- CLI command `definition get` retrieves definitions for display

**Cleanup:** Manual deletion via `definition delete` CLI command. Definitions are immutable and versioned.

**Workflow feature:** Enables deterministic workflow execution by storing versioned, immutable workflow definitions.

---

### `lemline_waits`

**Purpose:** Implements the **Wait task** - pauses workflow execution for a specified duration before resuming.

**Key files:**

- Model: [WaitModel.kt](src/main/kotlin/com/lemline/runner/models/WaitModel.kt)
- Repository: [WaitRepository.kt](src/main/kotlin/com/lemline/runner/repositories/WaitRepository.kt)
- Outbox: [WaitOutbox.kt](src/main/kotlin/com/lemline/runner/outbox/WaitOutbox.kt)
-

Migration: [V2__Create_lemline_waits_table.sql](src/main/resources/db/migration/postgresql/V2__Create_lemline_waits_table.sql)

**Schema:**

| Column Group   | Columns                                                                                                                             |
|----------------|-------------------------------------------------------------------------------------------------------------------------------------|
| Identity       | `id` (UUID PK)                                                                                                                      |
| Workflow state | `workflow_id`, `workflow_namespace`, `workflow_name`, `workflow_version`, `workflow_position`, `workflow_state`                     |
| Outbox         | `outbox_scheduled_for`, `outbox_delayed_until`, `outbox_attempt_count`, `outbox_error_*`, `outbox_completed_at`, `outbox_failed_at` |

**Who writes:**

When a workflow encounters a `wait` task (e.g., `wait: { seconds: 300 }`):

1. [Processor](../lemline-core/src/main/kotlin/com/lemline/core/processor/Processor.kt) throws `WaitStartedException`
2. [StepByStepRunner](src/main/kotlin/com/lemline/runner/StepByStepRunner.kt) catches and creates `WaitStarted` event
3. [WorkflowEventHandler.handleWaitStarted()](src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt)
   inserts the record

**Who reads:**

[WaitOutbox](src/main/kotlin/com/lemline/runner/outbox/WaitOutbox.kt) polls for due waits:

```sql
SELECT *
FROM lemline_waits
WHERE outbox_completed_at IS NULL
  AND outbox_failed_at IS NULL
  AND outbox_delayed_until <= NOW()
    FOR UPDATE SKIP LOCKED
LIMIT ?
```

**Processing flow:**

```
Wait task reached → WaitStarted event → Insert with delayed_until = NOW() + duration
                                                    ↓
                                        [Time passes...]
                                                    ↓
WaitOutbox finds due record → Emits ResumeAfterWait command → Mark completed → Cleanup
```

**Cleanup:** [WaitOutbox](src/main/kotlin/com/lemline/runner/outbox/WaitOutbox.kt) extends `AbstractCleaner` and
periodically deletes records where `outbox_completed_at < (NOW() - configured_age)`.

**Workflow feature:** Enables `wait` tasks with durations (seconds, minutes, hours) for time-based workflow pauses.

---

### `lemline_retries`

**Purpose:** Implements **automatic task retry with exponential backoff** when tasks fail.

**Key files:**

- Model: [RetryModel.kt](src/main/kotlin/com/lemline/runner/models/RetryModel.kt)
- Repository: [RetryRepository.kt](src/main/kotlin/com/lemline/runner/repositories/RetryRepository.kt)
- Outbox: [RetryOutbox.kt](src/main/kotlin/com/lemline/runner/outbox/RetryOutbox.kt)
-

Migration: [V3__Create_lemline_retries_table.sql](src/main/resources/db/migration/postgresql/V3__Create_lemline_retries_table.sql)

**Schema:**

Same structure as `lemline_waits` plus error details:

| Column             | Type    | Description          |
|--------------------|---------|----------------------|
| `error_reason`     | VARCHAR | Error classification |
| `error_class`      | TEXT    | Exception class name |
| `error_message`    | TEXT    | Error message        |
| `error_stacktrace` | TEXT    | Full stack trace     |

**Who writes:**

When a task fails and has retry policy defined (e.g., `retry: { limit: { count: 3 } }`):

1. [Processor](../lemline-core/src/main/kotlin/com/lemline/core/processor/Processor.kt) throws `TaskRetriedException`
2. [StepByStepRunner](src/main/kotlin/com/lemline/runner/StepByStepRunner.kt) creates `RetryScheduled` event with
   calculated backoff
3. [WorkflowEventHandler.handleRetryScheduled()](src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt)
   inserts the record

**Backoff calculation:**

Exponential backoff with jitter (implemented
in [AbstractOutbox.calculateNextAttemptDelay()](src/main/kotlin/com/lemline/runner/outbox/AbstractOutbox.kt)):

```
baseDelay = initialDelay × 2^(attemptCount - 1)
actualDelay = baseDelay ± 20% (jitter)

Example with initialDelay = 10s:
  Attempt 1: 10s × 2^0 = 10s ± 2s
  Attempt 2: 10s × 2^1 = 20s ± 4s
  Attempt 3: 10s × 2^2 = 40s ± 8s
```

**Who reads:**

[RetryOutbox](src/main/kotlin/com/lemline/runner/outbox/RetryOutbox.kt) polls for due retries using
`FOR UPDATE SKIP LOCKED`.

**Processing flow:**

```
Task fails → TaskRetriedException → Insert with delayed_until = NOW() + backoff
                                                ↓
                                    [Backoff period passes...]
                                                ↓
RetryOutbox finds due record → Emits ResumeAfterRetry command → Task re-executes
                                                ↓
                                    Success: Mark completed
                                    Failure: Increment attempt, calculate next backoff
                                    Max attempts: Mark failed → FailureModel
```

**Cleanup:** Same as waits - old completed records are periodically deleted.

**Workflow feature:** Enables resilient task execution with configurable retry policies and exponential backoff.

---

### `lemline_parents`

**Purpose:** Implements the **RunWorkflow task** - tracks parent workflow state while waiting for child workflow
completion.

**Key files:**

- Model: [ParentModel.kt](src/main/kotlin/com/lemline/runner/models/ParentModel.kt)
- Repository: [ParentRepository.kt](src/main/kotlin/com/lemline/runner/repositories/ParentRepository.kt)
- Cleaner: [ParentCleaner.kt](src/main/kotlin/com/lemline/runner/cleaner/ParentCleaner.kt)
-

Migration: [V4__Create_lemline_parents_table.sql](src/main/resources/db/migration/postgresql/V4__Create_lemline_parents_table.sql)

**Schema:**

| Column                | Type      | Description                           |
|-----------------------|-----------|---------------------------------------|
| `id`                  | UUID      | Primary key                           |
| `child_id`            | UUID      | Child workflow ID (unique constraint) |
| `workflow_*`          | ...       | Parent workflow state (serialized)    |
| `outbox_completed_at` | TIMESTAMP | Set when child completes              |
| `created_at`          | TIMESTAMP | Creation timestamp                    |

**Who writes:**

When a workflow executes a `run` task (calling another workflow):

1. [Processor](../lemline-core/src/main/kotlin/com/lemline/core/processor/Processor.kt) throws
   `RunWorkflowStartedException`
2. [StepByStepRunner](src/main/kotlin/com/lemline/runner/StepByStepRunner.kt) creates `RunWorkflowStarted` event with
   child config
3. [WorkflowEventHandler.handleRunWorkflowStarted()](src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt):
    - Inserts parent record
    - Emits child workflow's first command to start execution

**Who reads:**

When child workflow completes (success or failure):

1. Child emits `WorkflowCompleted` or `WorkflowFailed` event with `hasWaitingParent = true`
2. [WorkflowEventHandler](src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt) calls
   `parentRepository.findByChildId()`
3. Resumes parent workflow with child's output or error
4. Marks parent record `outbox_completed_at = NOW()`

**Processing flow:**

```
Parent reaches RunWorkflow task → RunWorkflowStarted event
                                          ↓
                        Insert parent record + emit child workflow command
                                          ↓
                              [Child workflow executes...]
                                          ↓
                        Child completes → WorkflowCompleted event
                                          ↓
            Find parent by child_id → Resume parent with child output → Mark completed
```

**Cleanup:** [ParentCleaner](src/main/kotlin/com/lemline/runner/cleaner/ParentCleaner.kt) periodically deletes records
where `outbox_completed_at < (NOW() - configured_age)`.

**Workflow feature:** Enables asynchronous sub-workflow execution with automatic parent-child synchronization.

---

### `lemline_schedules`

**Purpose:** Implements **scheduled workflow execution** - cron expressions, intervals, or one-time delays.

**Key files:**

- Model: [ScheduleModel.kt](src/main/kotlin/com/lemline/runner/models/ScheduleModel.kt)
- Repository: [ScheduleRepository.kt](src/main/kotlin/com/lemline/runner/repositories/ScheduleRepository.kt)
- Outbox: [ScheduleOutbox.kt](src/main/kotlin/com/lemline/runner/outbox/ScheduleOutbox.kt)
-

Migration: [V5__Create_lemline_schedules_table.sql](src/main/resources/db/migration/postgresql/V5__Create_lemline_schedules_table.sql)

**Schema:**

| Column           | Type    | Description                        |
|------------------|---------|------------------------------------|
| `id`             | UUID    | Primary key                        |
| `workflow_id`    | UUID    | Unique workflow identifier         |
| `workflow_*`     | ...     | Workflow identity and input        |
| `schedule_after` | VARCHAR | ISO 8601 duration (one-time delay) |
| `schedule_every` | VARCHAR | ISO 8601 duration (interval)       |
| `schedule_cron`  | VARCHAR | Unix cron expression               |
| `schedule_zone`  | VARCHAR | IANA timezone for cron             |
| `outbox_*`       | ...     | Standard outbox columns            |

**Who writes:**

When workflow definition has a `schedule` block:

```yaml
schedule:
    every: PT1H          # Run every hour
    # OR
    cron: "0 9 * * MON"  # Run at 9 AM every Monday
    timezone: UTC
    # OR
    after: PT30M         # Run 30 minutes after previous completes
```

[Starter.getStartingMessages()](src/main/kotlin/com/lemline/runner/Starter.kt) creates `ScheduleModel`:

- `every`: Next execution = `NOW() + interval`
- `cron`: Next execution = next cron occurrence (using cronutils library)
- `after`: Deferred until workflow completes, then scheduled

**Who reads:**

[ScheduleOutbox](src/main/kotlin/com/lemline/runner/outbox/ScheduleOutbox.kt) polls for due schedules.

**Processing flow:**

```
Schedule becomes due → ScheduleOutbox.process()
                              ↓
            Generate new workflow ID → Emit ExecuteStep command
                              ↓
            Calculate next execution time → Update delayed_until
                              ↓
              (For cron with no more executions: mark completed)
```

**Special handling for `after`:**

When a scheduled workflow completes:

1. `WorkflowEventHandler.handleWorkflowCompleted()` checks for schedule
2. Calls `scheduleRepository.scheduleAfterCompletion()` to set next execution

**Cleanup:** Same as other outbox tables.

**Workflow feature:** Enables time-based workflow triggering with cron, interval, or completion-based scheduling.

---

### `lemline_failures`

**Purpose:** **Permanent audit log** for workflow failures, message processing errors, and outbox failures.

**Key files:**

- Model: [FailureModel.kt](src/main/kotlin/com/lemline/runner/models/FailureModel.kt)
- Repository: [FailureRepository.kt](src/main/kotlin/com/lemline/runner/repositories/FailureRepository.kt)
-

Migration: [V6__Create_lemline_failures_table.sql](src/main/resources/db/migration/postgresql/V6__Create_lemline_failures_table.sql)

**Schema:**

| Column             | Type      | Description                              |
|--------------------|-----------|------------------------------------------|
| `id`               | UUID      | Primary key                              |
| `workflow_id`      | UUID      | Workflow ID (if available)               |
| `workflow_*`       | ...       | Workflow identity (if available)         |
| `payload`          | TEXT      | Raw message payload (for deser failures) |
| `error_reason`     | VARCHAR   | Error classification                     |
| `error_class`      | TEXT      | Exception class name                     |
| `error_message`    | TEXT      | Error message                            |
| `error_stacktrace` | TEXT      | Full stack trace                         |
| `created_at`       | TIMESTAMP | Failure timestamp                        |

**Who writes:**

Three failure scenarios:

1. **Message deserialization failures:**
    - [WorkflowCommandHandler.deserialize()](src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt)
      catches JSON parsing errors
    - Stores raw `payload` (workflow fields are null)
    - Reason: `DESERIALIZATION_FAILURE`

2. **Workflow execution failures:**
    - [WorkflowEventHandler.handleWorkflowFailed()](src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt)
    - Contains complete workflow state
    - Reason: Error type from workflow exception

3. **Outbox processing failures:**
    - When outbox entity reaches `maxAttempts` without success
    - [AbstractOutbox.processEntity()](src/main/kotlin/com/lemline/runner/outbox/AbstractOutbox.kt)
      sets `outboxFailedAt` and may create failure record

**Who reads:**

- Primarily for debugging and auditing
- No automated processing - data is stored for inspection

**Cleanup:** **Never cleaned automatically.** This table serves as a permanent audit trail for:

- Debugging failed workflow executions
- Investigating message processing issues
- Compliance and audit requirements

**Workflow feature:** Provides observability and debugging capability for workflow failures.

---

### `lemline_forks` + `lemline_fork_branches`

**Purpose:** Implements the **Fork task** - parallel branch execution with two coordination modes.

**Key files:**

- Models: [ForkModel.kt](src/main/kotlin/com/lemline/runner/models/ForkModel.kt),
  [ForkBranchModel.kt](src/main/kotlin/com/lemline/runner/models/ForkBranchModel.kt)
- Repository: [ForkRepository.kt](src/main/kotlin/com/lemline/runner/repositories/ForkRepository.kt)
- Cleaner: [ForkCleaner.kt](src/main/kotlin/com/lemline/runner/cleaner/ForkCleaner.kt)
-

Migration: [V7__Create_lemline_forks_tables.sql](src/main/resources/db/migration/postgresql/V7__Create_lemline_forks_tables.sql)

**Schema (`lemline_forks`):**

| Column                | Type      | Description                        |
|-----------------------|-----------|------------------------------------|
| `id`                  | UUID      | Primary key                        |
| `workflow_*`          | ...       | Parent workflow state (serialized) |
| `position`            | TEXT      | Fork position in workflow tree     |
| `compete`             | BOOLEAN   | Competition mode flag              |
| `output`              | TEXT      | Fork output (JSON)                 |
| `outbox_completed_at` | TIMESTAMP | Set when fork completes            |
| `failed_at`           | TIMESTAMP | Set when fork fails                |
| `error_*`             | ...       | Error details if failed            |

**Unique constraint:** `(workflow_id, position)` - one fork per position per workflow instance.

**Schema (`lemline_fork_branches`):**

| Column         | Type      | Description                     |
|----------------|-----------|---------------------------------|
| `fork_id`      | UUID      | Foreign key to forks (CASCADE)  |
| `name`         | VARCHAR   | Branch name (from workflow def) |
| `output`       | TEXT      | Branch output (JSON)            |
| `completed_at` | TIMESTAMP | Branch completion timestamp     |
| `failed_at`    | TIMESTAMP | Branch failure timestamp        |
| `error_*`      | ...       | Error details if failed         |

**Primary key:** `(fork_id, name)`

**Who writes:**

When a workflow reaches a `fork` task:

1. [Processor](../lemline-core/src/main/kotlin/com/lemline/core/processor/Processor.kt) throws `ForkStartedException`
2. [StepByStepRunner](src/main/kotlin/com/lemline/runner/StepByStepRunner.kt) creates `ForkStarted` event
3. [WorkflowEventHandler.handleForkStarted()](src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt):
    - Creates `ForkBranchModel` for each branch
    - Atomically inserts fork + branches in single transaction
    - Emits command for each branch to start parallel execution

**Who reads and updates:**

Branch completion uses **pessimistic locking** to handle concurrent updates:

```sql
SELECT *
FROM lemline_forks
WHERE workflow_id = ?
  AND position = ?
    FOR UPDATE -- Lock the row
```

[WorkflowEventHandler.handleBranchCompleted()](src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt)
and `handleBranchFailed()`:

1. Lock fork row with `FOR UPDATE`
2. Update branch status (`completed_at` or `failed_at`, output/error)
3. Check fork completion based on mode
4. If fork completes: emit resume command with aggregated output

**Competition modes:**

| Mode                      | Fork Completes When    | Fork Output               | Fork Fails When    |
|---------------------------|------------------------|---------------------------|--------------------|
| **Compete** (`true`)      | First branch completes | Single output from winner | All branches fail  |
| **Cooperative** (`false`) | All branches complete  | JSON array of all outputs | First branch fails |

**Processing flow (cooperative mode):**

```
Fork task reached → ForkStarted event → Insert fork + branches
                                              ↓
                    Emit command for each branch (parallel execution)
                                              ↓
Branch 1 completes → BranchCompleted → Update branch, check if all done
Branch 2 completes → BranchCompleted → Update branch, check if all done
Branch 3 completes → BranchCompleted → All done! Emit ResumeAfterFork
                                              ↓
                            Fork output = [branch1_output, branch2_output, branch3_output]
```

**Processing flow (compete mode):**

```
Fork task reached → ForkStarted event → Insert fork + branches
                                              ↓
                    Emit command for each branch (parallel execution)
                                              ↓
Branch 2 completes first → BranchCompleted → First completion! Emit ResumeAfterFork
                                              ↓
                            Fork output = branch2_output (winner)
                            (Other branches continue but their results are ignored)
```

**Error handling:**

- Fork errors can be caught by `try/catch` blocks above the fork
- Fork failure does not immediately fail the workflow if caught
- Error details stored inline in fork/branch tables (no FK to failures table)

**Cleanup:** [ForkCleaner](src/main/kotlin/com/lemline/runner/cleaner/ForkCleaner.kt) deletes completed forks.
Branch records are cascade-deleted via `ON DELETE CASCADE`.

**Workflow feature:** Enables parallel task execution with two coordination strategies - race (compete) or aggregate
(cooperative).

---

### Outbox Pattern

The outbox pattern ensures reliable message delivery by persisting before sending.

**Used by:** `lemline_waits`, `lemline_retries`, `lemline_schedules`

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

- [AbstractOutbox.kt](src/main/kotlin/com/lemline/runner/outbox/AbstractOutbox.kt) - Base outbox processor with backoff
- [OutboxRepository.kt](src/main/kotlin/com/lemline/runner/repositories/OutboxRepository.kt) - Query interface
- [AbstractCleaner.kt](src/main/kotlin/com/lemline/runner/cleaner/AbstractCleaner.kt) - Cleanup logic

**Benefits:**

- **At-least-once delivery**: Messages survive crashes
- **No double-processing**: `FOR UPDATE SKIP LOCKED` prevents concurrent handling
- **Automatic retry**: Exponential backoff with jitter
- **Graceful degradation**: Failed entities are tracked, not lost

---

## CLI Commands

Lemline provides a CLI for managing workflows and the runtime.

**Entry point**: [LemlineApplication.kt](src/main/kotlin/com/lemline/runner/LemlineApplication.kt)

### Command Structure

```
lemline [global-options] <command> [command-options]
```

### Global Options

| Option                | Description                 |
|-----------------------|-----------------------------|
| `-c, --config <path>` | Configuration file location |
| `--debug`             | Set log level to DEBUG      |
| `--info`              | Set log level to INFO       |
| `--warn`              | Set log level to WARN       |
| `--error`             | Set log level to ERROR      |
| `-h, --help`          | Show help                   |
| `-V, --version`       | Show version                |

See: [GlobalMixin.kt](src/main/kotlin/com/lemline/runner/cli/GlobalMixin.kt)

### Commands

#### `listen`

Starts the workflow and database message consumers. This is the main runtime mode.

```bash
lemline listen [--port <port>]
```

| Option       | Description                           |
|--------------|---------------------------------------|
| `-p, --port` | Metrics endpoint port (default: 8080) |

See: [ListenCommand.kt](src/main/kotlin/com/lemline/runner/cli/ListenCommand.kt)

#### `config`

Displays the current configuration.

```bash
lemline config [-f yaml|properties] [-a]
```

| Option | Description                               |
|--------|-------------------------------------------|
| `-f`   | Output format: `yaml` or `properties`     |
| `-a`   | Show all properties, not just `lemline.*` |

See: [ConfigCommand.kt](src/main/kotlin/com/lemline/runner/cli/ConfigCommand.kt)

#### `definition`

Manage workflow definitions.

```bash
# Get definition(s)
lemline definition get [namespace] [name] [version] [-f yaml|json]

# Upload definition
lemline definition post <file>

# Delete definition
lemline definition delete <namespace> <name> <version>
```

See: [cli/definition/](src/main/kotlin/com/lemline/runner/cli/definition/)

#### `instance`

Manage workflow instances.

```bash
# Start a new instance
lemline instance start <namespace> <name> [version] [-i <input-json>] [-z <timezone>]
```

| Option        | Description               |
|---------------|---------------------------|
| `-i, --input` | Input data as JSON string |
| `-z, --zone`  | Timezone for the workflow |

See: [cli/instance/](src/main/kotlin/com/lemline/runner/cli/instance/)

#### `migrate`

Run database migrations.

```bash
# Run migrations
lemline migrate [--pretend] [--force]

# Show migration status
lemline migrate status
```

| Option      | Description                               |
|-------------|-------------------------------------------|
| `--pretend` | Show what would be done without executing |
| `--force`   | Force migration even if validation fails  |

See: [cli/migrate/](src/main/kotlin/com/lemline/runner/cli/migrate/)
