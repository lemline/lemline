# Lifecycle timestamps: created → started

This document defines when `workflow.created`, `workflow.started`, `task.created`, and
`task.started` are stamped. It also says what the interval between a `created` event and its
`started` event measures, and what it does not. Issue: lemline/lemline#70.

## What the interval measures

`created → started` measures **waiting for execution**: the time between the moment a command
becomes eligible to run and the moment a worker begins it.

It is **not** strict broker residence time. The interval includes:

| Contribution                                                        | Included? |
|---------------------------------------------------------------------|:---------:|
| Completion of the accepting database transaction (commit latency)   |    Yes    |
| Outbox relay delay before the command is published (gateway outbox) |    Yes    |
| Publication to the broker and broker transport                      |    Yes    |
| Time spent queued in the broker                                     |    Yes    |
| Worker-side setup before task entry (deserialization, definition lookup, secret resolution) | Yes |
| Scheduler lag between an occurrence's deadline and its acceptance   |    No     |
| Intentional retry backoff (before `retryAt`)                        |    No     |

If strict broker residence time is ever needed, it should be exposed as a separate metric. The
meaning of the lifecycle events must stay as defined here.

## The boundaries

Each command carries its creation time (`ResumeFromTask.created_at`, protobuf field 6). The
`created` event is stamped with that exact value. Neither redelivery nor relay retries change
it. The `started` event is read from the worker clock when the worker enters the task.

### `workflow.created` and the initial `/do` `task.created`

These two events mark the **durable acceptance of an initial command that is eligible for
dispatch**. Both carry the same timestamp, which is read in-process **just before the accepting
insert**. The commit itself therefore falls inside the interval.

| Start path              | Creation time                                                            | Persisted with                         |
|-------------------------|--------------------------------------------------------------------------|----------------------------------------|
| Gateway                 | read before inserting the gateway command outbox row                    | that row (same transaction)            |
| CLI (`instance start`)  | read before enqueuing; the command is published inside the transaction | the CLI's own transaction, with the schedule row if any |
| Event-triggered (`schedule.on`) | read before inserting the event-schedule row                    | that row (same transaction)            |
| Child workflow (sync and async) | read in the parent-record transaction                          | the parent row (same transaction)      |
| Cron / every / after occurrence | **acceptance** of the due occurrence (see below)                | the occurrence's completion            |

The worker that begins the instance emits `workflow.started` and the `/do` `task.started`. It no
longer emits the `/do` `task.created`.

`RootState.startedAt`, which backs `$workflow.startedAt` in expressions, keeps its meaning. It is
not the creation time.

#### Scheduled occurrences

A schedule involves three distinct moments:

1. **Registration**: the schedule row is inserted. No lifecycle event is emitted, and the row
   carries no creation time.
2. **Deadline** (`outbox_scheduled_for`): the earliest time the occurrence may run.
3. **Acceptance**: the relay claims the due occurrence. Its creation time is frozen and
   **committed before the command is published**, in a separate transaction. A failed
   publication, a relay retry, or a crash after publication reuses that value; it is never
   recomputed.

`workflow.created` is stamped at acceptance. The time between the deadline and acceptance is
scheduler lag, and the waiting interval does not include it. The creation events are written
in the transaction that marks the occurrence as published. An occurrence whose publication
failed has no creation event yet.

### Subsequent tasks

`task.created` marks the point where a task's command becomes **eligible for execution**:

- **Sequential tasks**: at the transition that produces the task's `TaskScheduled` event,
  normally when the predecessor completes. Processors stamp `TaskScheduled.created_at`, and the
  resulting command carries it.
  - In `ALL` mode, every task is a separate command. The interval covers publication, transport
    and pickup.
  - In `ACTION` mode, control-flow tasks run inline in the same worker. Their interval is near
    zero, and zero is a valid value at clock precision.
- **Fork branches** (production path, `ForkService`): when the fork is accepted on the events
  channel. Each branch's `task.created` is stamped with that instant and persisted durably with
  the fork rows. Each branch command carries the same instant.

**Returning to an existing task** after a wait, a listener event, a fork, or a child workflow
does not create a new task invocation. No `task.created` is emitted, and the task keeps its
original creation time.

### Retries

A retry attempt is not a new invocation of the retried block:

- The block gets **no new `task.created`**.
- Its attempt boundary is **`task.retried`**, stamped with `retryAt`.
- The retry command carries `created_at = retryAt`.

The time from `task.faulted` to `retryAt` is intentional backoff. The time from `retryAt` to the
attempt's `task.started` is waiting for execution. Tasks inside a retried block are scheduled
again by each attempt: they are new invocations, with a new `task.created` and new event IDs.

## Durability of creation events

Creation events are written to `lemline_creation_event_outbox` in the transaction that accepts
the command. A relay (`CreationEventOutbox`) publishes them independently of command dispatch:

- **Observable without workers**: the events are published even if no worker ever consumes the
  command. The relay runs in every process with a database, including gateway-only and
  CLI processes, which publish their own rows at once.
- **Independent channels**: an unavailable lifecycle channel never blocks execution, and an
  unavailable command channel never blocks the creation events.
- **Failures are not hidden**: a row is completed only after the broker acknowledges it.
  Failed publications are retried with exponential backoff (default: 400 ms polling, 1 s base delay,
  17 attempts, so about 18 hours of retries in all), then marked failed (`outbox_failed_at`) and left in the table for inspection.
  This matters because the instance list only shows instances that have a `workflow.created`:
  until its creation event is published, a running instance does not appear there. After a
  longer outage, operators can queue the failed rows again:

  ```sql
  UPDATE lemline_creation_event_outbox
  SET outbox_failed_at = NULL, outbox_attempt_count = 0, outbox_delayed_until = CURRENT_TIMESTAMP
  WHERE outbox_failed_at IS NOT NULL AND outbox_completed_at IS NULL;
  ```
- **Frozen content**: the CloudEvent is fully built and serialized at enqueue. Its ID (the
  table's primary key) and its time never change.

Other lifecycle events (`*.started`, `*.completed`, subsequent `task.created`, …) are still
emitted fire-and-forget by workers. No database write is added per inline transition.

Configuration: `lemline.outbox.creation-events` (`enabled`, `outbox.*`, `cleanup.*`). Declaring
this section, even for a single key, replaces the creation-specific retry defaults above with
the generic outbox defaults (10 s interval, 30 s base delay, 5 attempts, i.e. about 7.5 minutes of
retries): set `outbox.*` explicitly when you configure it. Nothing is
enqueued, and no creation event is produced at all, when the lifecycle events producer or this
outbox is disabled.

## Identity and ordering

- **Event IDs** are derived deterministically from the workflow ID, the stack's execution key
  (one counter per frame), and the event kind and position. Command redelivery therefore
  re-derives the same IDs, and consumers deduplicate on them. Loop iterations and retry
  attempts increment a frame counter, so they get distinct IDs.
- **A redelivered predecessor** re-stamps the next task's `created_at`. Its event ID and its
  command's idempotency key are unchanged, so the first publication wins at the consumer.
  No write per transition is added to prevent this.
- **Causality**: within one instance, "created before started" holds by construction. Across
  hosts, it holds only up to clock skew between the accepting process and the worker.
  Analytics ingestion order, equal timestamps, and UUID order do **not** prove causality.
  Creation events are relayed separately, so they often arrive *after* the `started` event.
  Consumers must place events by their own timestamps and kinds, not by arrival. The dashboard
  breaks ties at equal times as "created before started", and clamps negative waits from skewed
  clocks to zero.

## Compatibility

- `created_at` is an additive protobuf field with message presence: field 6 on
  `ResumeFromTaskProto` and `TaskScheduledProto`. `buf breaking` reports no break.
- Messages and persisted `workflow_state` rows written before this change decode with
  `createdAt = null`, meaning **unknown**. It is never defaulted to the epoch or to "now", and
  re-encoding keeps the field absent.
- For an initial command without `created_at` (in flight during an upgrade), the worker still
  emits the `/do` `task.created` at entry. It stamps that event with `RootState.startedAt`, the
  value older runtimes used for `workflow.created`, and not with the worker clock, so the wait
  does not collapse to zero. Its event ID matches the one the new acceptance path derives.
- Schedule rows written before the change have no creation time: they are accepted at their
  next relay pass.
- Migration `V907__Create_lemline_creation_event_outbox_table` (PostgreSQL, MySQL, H2) adds the
  table. No data migration is needed. It must be applied before an upgraded gateway, CLI, or
  runner accepts starts: their accepting transaction writes to this table.
- **Rolling upgrade**: older workers still emit the `/do` `task.created` when they enter `/do`,
  stamped with their own clock and with the same event ID. Analytics keeps the first event it
  receives for an ID, so instances in flight during the upgrade may show a zero initial wait.

## Residual limitations

- **Clock skew** between the accepting host and the worker shifts the interval, and can make it
  negative (displayed as zero).
- **Precision**: SQL timestamp columns store microseconds. The CloudEvent payload keeps the
  full precision of the process clock.
- **CLI**: the command is published inside the transaction. If the commit fails *after* a
  successful publication, the instance runs without creation events.
- **Child workflows**: a child is published inside the parent-record transaction. If the
  commit fails after publication, the redelivered event creates the child again with a later
  creation time.
- **Fork branches**: if the fork rows commit but a branch command fails to publish, the branch
  is never dispatched. This gap existed before this change, but the branch now shows a creation
  event and stays "waiting to start".
- **Eventual visibility**: creation events reach analytics after one relay pass at most (the
  default interval is 400 ms, or immediately via the post-commit kick). The broker is
  at-least-once.
- **Persistence overhead**: two rows per accepted instance and one row per fork branch, deleted
  after the cleanup retention period.
- **Watch streams**: a client that stops reading at the instance's terminal event can miss a
  creation event that arrives after it (the relay may publish after a fast worker finished).
