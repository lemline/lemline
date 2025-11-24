# Lemline Runner - Messaging Architecture

This document covers the messaging architecture of the lemline-runner module.

## Dual-Channel Design

Lemline uses a **dual-channel design** that separates high-throughput workflow execution from durable database operations.

| Channel  | Topics                      | Purpose                                  |
|----------|-----------------------------|------------------------------------------|
| Commands | `lemline-commands` (in/out) | High-throughput, stateless message flow  |
| Events   | `lemline-events` (in/out)   | Durable operations requiring persistence |

---

## Commands Channel

The commands channel handles the main execution flow. Messages carry compressed workflow state, enabling stateless workers.

**Components:**

| Component  | File |
|------------|------|
| Handler    | [WorkflowCommandHandler.kt](../src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt) |
| Subscriber | [WorkflowCommandSubscriber.kt](../src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandSubscriber.kt) |
| Emitter    | [WorkflowCommandEmitter.kt](../src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandEmitter.kt) |

**Message Type**: `InstanceMessage<WorkflowCommand>`

### WorkflowCommand Types

Defined in [WorkflowState.kt](../../lemline-core/src/main/kotlin/com/lemline/core/states/WorkflowState.kt):

| Command                   | Purpose                                                                       |
|---------------------------|-------------------------------------------------------------------------------|
| `ResumeFromTask`          | Resume workflow execution from a specific task position                       |
| `ResumeWithCompletedTask` | Resume with a task that completed asynchronously (wait, fork, child workflow) |
| `ResumeWithFailedTask`    | Resume with a task that failed asynchronously                                 |

---

## Events Channel

The events channel handles operations that require persistence (timers, retries, parent-child relationships).

**Components:**

| Component  | File |
|------------|------|
| Handler    | [WorkflowEventHandler.kt](../src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt) |
| Subscriber | [WorkflowEventSubscriber.kt](../src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventSubscriber.kt) |
| Emitter    | [WorkflowEventEmitter.kt](../src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventEmitter.kt) |

**Message Type**: `InstanceMessage<WorkflowEvent>`

### WorkflowEvent Types (Outcomes)

Terminal states for workflows/branches:

| Event             | Persisted To            | Purpose                          |
|-------------------|-------------------------|----------------------------------|
| `WorkflowCompleted` | (triggers parent/schedule) | Workflow completed successfully |
| `WorkflowFailed`    | `lemline_failures`     | Workflow failed (uncaught error) |
| `BranchCompleted`   | `lemline_fork_branches` | One fork branch completed       |
| `BranchFailed`      | `lemline_fork_branches` | One fork branch failed          |

### WorkflowEvent Types (Suspensions)

Workflow paused, waiting to resume:

| Event                | Persisted To             | Purpose                                |
|----------------------|--------------------------|----------------------------------------|
| `TaskScheduled`      | (emits to commands)      | Next task ready to execute             |
| `WaitStarted`        | `lemline_waits`          | Timer/delay task started               |
| `RetryScheduled`     | `lemline_retries`        | Task retry with backoff scheduled      |
| `RunWorkflowStarted` | `lemline_parents`        | Child workflow started, parent waiting |
| `ForkStarted`        | `lemline_forks`          | Fork (parallel branches) started       |

---

## Message Flow Diagram

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

---

## Key Design Principles

### 1. State Travels with Messages

The entire workflow state is serialized into `InstanceMessage`, enabling any worker to process any message without shared state.

**Key file**: [InstanceMessage.kt](../src/main/kotlin/com/lemline/runner/messaging/InstanceMessage.kt)

### 2. Database Used Only When Necessary

Database is only used for:
- Timers (`lemline_waits`)
- Retries (`lemline_retries`)
- Parent-child coordination (`lemline_parents`)
- Schedules (`lemline_schedules`)
- Failures (`lemline_failures`)
- Forks (`lemline_forks`, `lemline_fork_branches`)

### 3. Horizontal Scaling

Workers are stateless and can scale independently. Multiple workers can consume from the same topic.

---

## Core Messaging Components

### MessageSubscriber

Base reactive subscriber with backpressure support.

**Key file**: [MessageSubscriber.kt](../src/main/kotlin/com/lemline/runner/messaging/MessageSubscriber.kt)

Features:
- Requests N messages (maxConcurrency)
- Processes messages asynchronously
- Requests next only after processing current
- Graceful shutdown waits for active messages with timeout

### MessageHandler

Handler interface with ACK/NACK support.

**Key file**: [MessageHandler.kt](../src/main/kotlin/com/lemline/runner/messaging/MessageHandler.kt)

---

## Execution Flows

### Normal Task Execution

```
InstanceMessage (commands-in)
→ WorkflowCommandHandler (deserialize, load definition)
→ StepByStepRunner.run() (execute one step)
→ Processor.run() (execute task)
→ onTaskCompleted throws TaskCompletedException
→ Update InstanceMessage with new position/state
→ Emit to commands-out
```

### Wait Task Execution

```
Processor reaches WaitInstance
→ onTaskStarted throws WaitStartedException(delay)
→ StepByStepRunner creates WaitStarted event
→ Send to events-out
→ WorkflowEventHandler inserts into lemline_waits
[Time passes...]
→ WaitOutbox scheduler finds due wait
→ Sends ResumeWithCompletedTask to commands-in
```

### Child Workflow Execution

```
Processor reaches RunInstance (RunWorkflow)
→ onTaskStarted throws RunWorkflowStartedException
→ StepByStepRunner creates RunWorkflowStarted event
→ Send to events-out
→ WorkflowEventHandler inserts parent, emits child workflow command
→ Child executes independently
→ On completion: WorkflowCompleted event triggers parent lookup
→ WorkflowEventHandler finds parent, updates with child output
→ Sends parent ResumeWithCompletedTask to commands-out
```

### Fork Task Execution

```
Processor reaches ForkInstance
→ onTaskStarted throws ForkStartedException
→ StepByStepRunner creates ForkStarted event
→ Send to events-out
→ WorkflowEventHandler inserts fork + branches, emits branch commands
→ Branches execute in parallel
→ On branch completion: BranchCompleted event
→ WorkflowEventHandler updates branch, checks fork completion
→ If fork complete: sends parent ResumeWithCompletedTask to commands-out
```

---

## Adding a New Event Type

1. Add event class in [WorkflowState.kt](../../lemline-core/src/main/kotlin/com/lemline/core/states/WorkflowState.kt)
2. Add exception in lemline-core if triggered by processor
3. Handle in [StepByStepRunner.kt](../src/main/kotlin/com/lemline/runner/StepByStepRunner.kt)
4. Add handler method in [WorkflowEventHandler.kt](../src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt)
5. Create database table/repository if persistence needed
6. Add outbox processor if delayed processing needed
