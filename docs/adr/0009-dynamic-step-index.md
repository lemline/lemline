# [ADR-0009] Idempotent Message and Database IDs

## Status

Accepted

## Context

Lemline needs idempotent identification for two critical purposes:

1. **Broker message deduplication** - Prevent double-processing when Kafka/RabbitMQ redeliver messages
2. **Database idempotency** - Prevent duplicate rows in outbox tables when the same event is processed twice

### The Problem

Currently, Lemline generates random `IDV7` values for:

- Message IDs in `MessageEmitter.sendPayload()`
- Database primary keys in outbox models (`WaitModel`, `RetryModel`, `ParentModel`, `ForkModel`, `FailureModel`)

This causes issues when messages are redelivered:

```
Message delivered → ID=abc123 → Processed successfully
Message redelivered → ID=xyz789 → Processed AGAIN (duplicate!)
```

The same problem affects database inserts. While `ON CONFLICT DO NOTHING` exists, it doesn't help when IDs are random.

### Uniqueness Requirements

The challenge is creating identifiers that are:

- **Deterministic**: Same execution context → same identifier (enables idempotency)
- **Unique across positions**: Different tasks get different IDs
- **Unique across time**: Multiple visits to the same node (loops, retries) get different IDs
- **Unique across branches**: Parallel fork branches get different IDs

Example collision scenarios without proper identification:

```yaml
# Loop: Same NodePosition "/for/do/wait" on every iteration
for:
  in: [ 1, 2, 3 ]
  do:
    - wait: PT1M

# Fork: Same step counter in parallel branches
fork:
  branches:
    - name: branchA
      do:
        - taskA: { set: { x: 1 } }
    - name: branchB
      do:
        - taskB: { set: { y: 2 } }
```

## Decision

We use a combination of **`NodePosition`** and **`workflowStep`** counter to generate deterministic, idempotent IDs.

### Key Insight

The `NodePosition` encodes the full path in the workflow tree, including branch names:

```
/do/forkTask/fork/branchA/do/task1  (step=N)
/do/forkTask/fork/branchB/do/task1  (step=N)  ← same step, different position
```

Fork branches share the same `nodeStack` (including `workflowStep`), but the position path makes them distinct.

### ID Derivation Formula

```
idempotentId = derive(workflowId, position + ":" + workflowStep + suffix)
```

Where:

- `workflowId`: The workflow instance's unique IDV7
- `position`: The `NodePosition` (e.g., `/do/task1`)
- `workflowStep`: Integer counter that increments on each task entry
- `suffix`: Type-specific string (e.g., `-wait`, `-retry`, `-parent`)

### Examples

| Context         | Position                   | Step | Salt                          | Unique? |
|-----------------|----------------------------|------|-------------------------------|---------|
| Main workflow   | `/do/task1`                | 0    | `/do/task1:0`                 | ✓       |
| Main workflow   | `/do/task2`                | 1    | `/do/task2:1`                 | ✓       |
| Loop iteration  | `/for/do/wait`             | 0    | `/for/do/wait:0`              | ✓       |
| Loop iteration  | `/for/do/wait`             | 1    | `/for/do/wait:1`              | ✓       |
| Fork branch A   | `/do/fork/a/do/task`       | 5    | `/do/fork/a/do/task:5`        | ✓       |
| Fork branch B   | `/do/fork/b/do/task`       | 5    | `/do/fork/b/do/task:5`        | ✓       |
| Retry attempt 1 | `/try/do/failing`          | 3    | `/try/do/failing:3`           | ✓       |
| Retry attempt 2 | `/try/do/failing`          | 4    | `/try/do/failing:4`           | ✓       |

## Implementation

### 1. Workflow Step Counter

The `workflowStep` counter in `RootState` increments each time a task is entered:

```kotlin
@Serializable
data class RootState(
    val workflowId: WorkflowId,
    val workflowStep: Int = 0,  // Increments on each task entry
    // ...
) : NodeState()
```

**Increment logic** (in `NodeStack`):

```kotlin
fun incrementStep(): NodeStack = withRootState(
    rootState.copy(workflowStep = rootState.workflowStep + 1)
)
```

**Key behaviors:**

- **Task entry**: Counter increments each time `Processor.run()` enters a task
- **Async resume**: Counter increments when resuming from wait/retry (new execution step)
- **Fork branches**: Branches share the parent's `nodeStack` - uniqueness comes from position

### 2. ID Derivation Helper

**In `IDV7.kt`:**

```kotlin
companion object {
    /**
     * Derives a deterministic IDV7 from a base ID, position, step, and suffix.
     */
    fun deriveFromPositionAndStep(
        baseId: IDV7,
        position: NodePosition,
        step: Int,
        suffix: String = ""
    ): IDV7 {
        val salt = "$position:$step$suffix"
        return IDV7(IdGenerator.deriveUuidV7FromV7(baseId.value, salt))
    }
}

/**
 * Derives a new IDV7 from this ID with the given suffix.
 */
fun derive(suffix: String): IDV7 {
    return IDV7(IdGenerator.deriveUuidV7FromV7(value, suffix))
}
```

**In `NodeStack.kt`:**

```kotlin
/**
 * Derives a deterministic IDV7 for the current execution context.
 */
fun deriveIdempotentId(suffix: String = ""): IDV7 {
    return IDV7.deriveFromPositionAndStep(
        baseId = rootState.workflowId,
        position = lastPosition,
        step = rootState.workflowStep,
        suffix = suffix
    )
}
```

### 3. Message ID Generation

**Commands channel** (`WorkflowCommandHandler`):

```kotlin
val messageId = current.nodeStack.deriveIdempotentId()
commandEmitter.sendPayload(payload, messageId)
```

**Events channel** - derived from model IDs (see below).

### 4. Database Model IDs

Each outbox model uses a type-specific suffix:

| Model        | Suffix      | Example Salt                    |
|--------------|-------------|---------------------------------|
| WaitModel    | `-wait`     | `/do/wait:3-wait`               |
| RetryModel   | `-retry`    | `/try/do/task:5-retry`          |
| ParentModel  | `-parent`   | `/do/runWorkflow:2-parent`      |
| ForkModel    | `-fork`     | `/do/fork:7-fork`               |
| FailureModel | `-failure`  | `/do/task:4-failure`            |

```kotlin
// In WorkflowEventHandler
val waitId = instance.nodeStack.deriveIdempotentId("-wait")
WaitModel(id = waitId, ...)

val retryId = instance.nodeStack.deriveIdempotentId("-retry")
RetryModel(id = retryId, ...)
```

### 5. Outbox Processor Message IDs

When outbox processors emit messages, they derive message IDs from the entity's ID:

```kotlin
// In WaitOutbox
val messageId = entity.id.derive("-resume")
instanceEmitter.send(resumeMessage, messageId)

// In RetryOutbox
val messageId = entity.id.derive("-resume")
instanceEmitter.send(resumeMessage, messageId)
```

### 6. Transaction-Scoped Emissions

Messages sent inside database transactions derive IDs from the parent model:

```kotlin
// Parent resume after child completes
parentRepository.withTransaction { conn ->
    val parent = parentRepository.findByChildId(childId, conn)
    val resumeMessageId = parent.id.derive("-resume")
    instanceEmitter.send(resumeMessage, resumeMessageId)
}

// Fork branch messages
branches.forEach { branchNode ->
    val branchMessageId = IDV7.deriveFromPositionAndStep(
        baseId = instance.workflowId,
        position = branchNode.position,
        step = instance.workflowStep,
        suffix = "-branch-init"
    )
    instanceEmitter.send(branchMessage, branchMessageId)
}
```

## Execution Examples

### Sequential Tasks

```yaml
do:
  - taskA: { set: { x: 1 } }
  - taskB: { set: { y: 2 } }
```

| Step | Position    | Salt           |
|------|-------------|----------------|
| 0    | `/do/taskA` | `/do/taskA:0`  |
| 1    | `/do/taskB` | `/do/taskB:1`  |

### Foreach Loop

```yaml
for:
  in: [ 1, 2, 3 ]
  do:
    - wait: PT1M
```

| Iteration | Step | Position       | Salt              |
|-----------|------|----------------|-------------------|
| 0         | 0    | `/for/do/wait` | `/for/do/wait:0`  |
| 1         | 1    | `/for/do/wait` | `/for/do/wait:1`  |
| 2         | 2    | `/for/do/wait` | `/for/do/wait:2`  |

### Fork (Parallel Branches)

```yaml
fork:
  branches:
    - name: branchA
      do:
        - taskA: { set: { x: 1 } }
    - name: branchB
      do:
        - taskB: { set: { y: 2 } }
```

| Context   | Step | Position                     | Salt                             |
|-----------|------|------------------------------|----------------------------------|
| Fork      | 5    | `/do/fork`                   | `/do/fork:5-fork`                |
| Branch A  | 5    | `/do/fork/branchA/do/taskA`  | `/do/fork/branchA/do/taskA:5`    |
| Branch B  | 5    | `/do/fork/branchB/do/taskB`  | `/do/fork/branchB/do/taskB:5`    |

Note: Branches share the same `workflowStep` (5) but have different positions.

### Try with Retry

```yaml
try:
  - failing: { call: { http: ... } }
catch:
  errors: ...
  retry:
    maxAttempts: 3
```

| Attempt | Step | Position          | Salt                   |
|---------|------|-------------------|------------------------|
| 1       | 0    | `/try/do/failing` | `/try/do/failing:0`    |
| 2       | 1    | `/try/do/failing` | `/try/do/failing:1`    |
| 3       | 2    | `/try/do/failing` | `/try/do/failing:2`    |

## Intentional Exclusions

The following use random IDs intentionally:

| Item                   | Reason                                                   |
|------------------------|----------------------------------------------------------|
| `ScheduleModel.id`     | Each scheduled execution is a new workflow instance      |
| `DefinitionModel`      | Uses composite key (namespace, name, version)            |
| `ForkBranchModel`      | Uses composite key (fork_id, branch_name)                |
| CLI-initiated workflows| User starts a new workflow - random WorkflowId is correct|

## Consequences

### Positive

1. **True idempotency**: Redelivered messages produce the same IDs → no duplicate processing
2. **Database safety**: `ON CONFLICT DO NOTHING` now works correctly with deterministic IDs
3. **No new state fields**: Position already exists, step counter is minimal overhead
4. **Fork-safe**: Position-based uniqueness handles parallel branches naturally
5. **Retry-safe**: Step counter distinguishes retry attempts

### Negative

1. **Longer salts**: Position strings can be long for deeply nested workflows
    - Mitigation: SHA-256 handles arbitrary length; no functional impact
2. **Position changes break idempotency**: Renaming tasks changes positions
    - Mitigation: Expected - renamed tasks are semantically different

## Alternatives Considered

### 1. Random IDs with Deduplication Table

Track processed message IDs in a separate table.

**Rejected because:**

- Requires additional database table and queries
- Doesn't solve database primary key collisions
- More complex to implement correctly

### 2. Synthetic Fork ID

Add a `forkId` field to `RootState` that gets derived for each branch.

**Rejected because:**

- Adds complexity to state management
- Breaking serialization change
- Position already contains branch information

### 3. Hash-Based with Timestamp

`hash(workflowId + position + timestamp)`

**Rejected because:**

- Timestamp makes it non-deterministic
- Can't guarantee idempotency on redelivery

## References

- [ADR-0003: Messaging Architecture](0003-messaging-architecture.md) - Dual-channel design
- [ADR-0004: Database Storage Strategy](0004-database-storage-strategy.md) - Outbox pattern
- `IdGenerator.deriveUuidV7FromV7()` - Deterministic UUID derivation using SHA-256
- Serverless Workflow DSL: https://serverlessworkflow.io/
