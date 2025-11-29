# Plan: Idempotent Message and Database IDs using Position + Step

## Goal

Use the `workflowStep` counter from `RootState` (ADR-0009) combined with `NodePosition` to generate deterministic,
idempotent IDs for:

1. Broker message IDs (deduplication in Kafka/RabbitMQ)
2. Database primary keys (idempotent inserts)

## User Decisions

- **Fork handling**: Position-based derivation (position path contains branch names, no synthetic forkId needed)
- **Scope**: All messages (both commands-out and events-out channels)

## Current State

### Message IDs

- `MessageEmitter.sendPayload()` generates `IDV7.random()` for each message
- No deduplication - redelivered messages get new IDs

### Database IDs

- All outbox models use `IDV7.random()`: `WaitModel`, `RetryModel`, `ParentModel`, `ForkModel`, `FailureModel`
- `ON CONFLICT DO NOTHING` exists but doesn't help since IDs are random

### Outbox Processor Emissions

- `WaitOutbox`, `RetryOutbox`, `ScheduleOutbox` emit messages with `IDV7.random()` message IDs
- Reprocessing the same outbox entity generates a new message ID

### Transaction-Scoped Emissions

- `WorkflowEventHandler` emits messages inside database transactions (parent/fork resume)
- If transaction commits but message send fails, retry generates new message ID

### Existing Infrastructure

- `IdGenerator.deriveUuidV7FromV7(sourceV7, salt)` - deterministically derives UUIDv7 using SHA-256
- `NodePosition` encodes full path including branch names (e.g., `/do/fork/branches/branchA/do/task1`)
- Fork branches share the same `nodeStack` (including `workflowId` and `workflowStep`) - uniqueness comes from position

## Approach: Position-Based ID Derivation

### Key Insight

The `NodePosition` already contains the full branch hierarchy. For fork branches:

```
/do/forkTask/fork/branchA/do/task1  (step=N)
/do/forkTask/fork/branchB/do/task1  (step=N)  ← same step, different position
```

These positions are **inherently unique** across branches. Fork branches share the same `nodeStack` (including
`workflowStep`), but the position path makes them distinct. By including position in the salt, we avoid collisions
without needing a synthetic `forkId` or resetting the step counter.

### ID Derivation Formula

```
messageId = derive(workflowId, position + ":" + workflowStep + suffix)
```

Examples:
| Context | Position | Step | Salt | Unique? |
|---------|----------|------|------|---------|
| Main workflow | `/do/task1` | 0 | `/do/task1:0` | ✓ |
| Main workflow | `/do/task2` | 1 | `/do/task2:1` | ✓ |
| Fork branch A | `/do/fork/a/do/task` | 5 | `/do/fork/a/do/task:5` | ✓ |
| Fork branch B | `/do/fork/b/do/task` | 5 | `/do/fork/b/do/task:5` | ✓ (different position) |
| Nested fork | `/do/fork/a/fork/x/do/t` | 5 | `/do/fork/a/fork/x/do/t:5` | ✓ |

### Advantages Over forkId Approach

1. **No new fields** - RootState unchanged
2. **No breaking changes** - No serialization changes
3. **Simpler logic** - No synthetic ID derivation chains
4. **Uses existing data** - Position is already available in NodeStack

## Implementation Steps

### Step 1: Add IDV7 Derivation Method (lemline-common)

**File: `IDV7.kt`**

```kotlin
companion object {
    // Existing methods...

    /**
     * Derives a deterministic IDV7 from a base ID, position, step, and optional suffix.
     * Used for generating idempotent message and database IDs.
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
```

### Step 2: Add Helper to NodeStack (lemline-core)

**File: `NodeStack.kt`**

```kotlin
/**
 * Derives a deterministic IDV7 for the current execution context.
 * Uses workflowId + position + step to ensure uniqueness across:
 * - Different positions in the workflow
 * - Multiple executions of the same position (via step counter)
 * - Parallel fork branches (position contains branch name)
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

### Step 3: Update MessageEmitter (lemline-runner)

**File: `MessageEmitter.kt`**

```kotlin
suspend fun sendPayload(payload: String, idempotentKey: IDV7? = null) {
    val md = MetaData(messageId = idempotentKey ?: IDV7.random())
    // ... rest unchanged
}
```

### Step 4: Update WorkflowCommandHandler

**File: `WorkflowCommandHandler.kt`**

- When emitting to `commands-out`, derive message ID from position and step:

```kotlin
val messageId = current.nodeStack.deriveIdempotentId()
commandEmitter.sendPayload(payload, messageId)
```

### Step 5: Update WorkflowEventHandler

**File: `WorkflowEventHandler.kt`**

#### 5a. Database Model IDs

Generate deterministic IDs using position and step with type-specific suffixes:

```kotlin
// WaitModel
val waitId = instance.nodeStack.deriveIdempotentId("-wait")
WaitModel(id = waitId, instanceMessage = instance, ...)

// RetryModel
val retryId = instance.nodeStack.deriveIdempotentId("-retry")
RetryModel(id = retryId, ...)

// ParentModel
val parentId = instance.nodeStack.deriveIdempotentId("-parent")
ParentModel(id = parentId, ...)

// ForkModel
val forkId = instance.nodeStack.deriveIdempotentId("-fork")
ForkModel(id = forkId, ...)

// FailureModel (workflow failures)
val failureId = instance.nodeStack.deriveIdempotentId("-failure")
FailureModel(id = failureId, ...)
```

#### 5b. Fork Branch Messages (handleForkStarted)

Branch messages are naturally unique because each branch has a different position:

```kotlin
private suspend fun handleForkStarted(instance: InstanceMessage<WorkflowEvent.ForkStarted>) {
    val forkId = instance.nodeStack.deriveIdempotentId("-fork")

    val forkModel = ForkModel(
        id = forkId,
        instanceMessage = instance,
        ...
    )

    // Each branch gets a unique position like /fork/branches/branchName/...
    // No need to reset workflowStep - position alone ensures uniqueness across branches
    branches.forEach { branchNode ->
        val branchMessage = InstanceMessage(
            workflowInfo = instance.workflowInfo,
            workflowState = WorkflowCommand.ResumeFromTask(
                nodeStack = instance.workflowState.nodeStack,  // Same nodeStack, not copied
                nodePosition = branchNode.position,  // e.g., /fork/branches/branchA/do
                rawInput = instance.workflowState.rawInput
            ),
        )

        // Position already includes branch name, so IDs are unique even with same workflowStep
        val branchMessageId = IDV7.deriveFromPositionAndStep(
            baseId = instance.workflowId,
            position = branchNode.position,
            step = instance.workflowState.nodeStack.rootState.workflowStep,
            suffix = "-branch-init"
        )
        instanceEmitter.send(branchMessage, branchMessageId)
    }
}
```

#### 5c. Transaction-Scoped Emissions (Parent/Fork Resume)

Messages sent inside database transactions need idempotent IDs to handle the case where the transaction commits but
message send fails, then retries.

**Parent workflow resume (child completed/failed):**

```kotlin
// In handleRunWorkflowCompleted / handleRunWorkflowFailed
parentRepository.withTransaction { conn ->
    val parent = parentRepository.findByChildId(childId, conn)
    // Derive message ID from parent model ID (already deterministic)
    val resumeMessageId = parent.id.derive("-resume")
    instanceEmitter.send(resumeMessage, resumeMessageId)
}
```

**Fork workflow resume (all branches completed/failed):**

```kotlin
// In handleForkBranchCompleted / handleForkBranchFailed
forkRepository.withTransaction { conn ->
    val fork = forkRepository.findByWorkflowIdAndPosition(...)
    // Derive message ID from fork model ID
    val resumeMessageId = fork.id.derive("-resume")
    instanceEmitter.send(resumeMessage, resumeMessageId)
}
```

**Child workflow start:**

```kotlin
// In handleRunWorkflowStarted
parentRepository.withTransaction { conn ->
    val parentId = instance.nodeStack.deriveIdempotentId("-parent")
    // Child message ID derived from parent ID
    val childMessageId = parentId.derive("-child-init")
    instanceEmitter.send(childMessage, childMessageId)
}
```

### Step 6: Update Outbox Processors

Outbox processors emit messages when timers/delays expire. These need idempotent message IDs derived from the outbox
entity's ID.

**File: `WaitOutbox.kt`**

```kotlin
override suspend fun processEntity(entity: WaitModel): OutboxResult {
    // Derive message ID from the wait model's ID
    val messageId = entity.id.derive("-resume")
    instanceEmitter.send(resumeMessage, messageId)
    return OutboxResult.Success
}
```

**File: `RetryOutbox.kt`**

```kotlin
override suspend fun processEntity(entity: RetryModel): OutboxResult {
    // Derive message ID from the retry model's ID
    val messageId = entity.id.derive("-resume")
    instanceEmitter.send(resumeMessage, messageId)
    return OutboxResult.Success
}
```

**File: `ScheduleOutbox.kt`**

```kotlin
override suspend fun processEntity(entity: ScheduleModel): OutboxResult {
    // Schedule uses random WorkflowId intentionally - each run is a new workflow
    // But message ID should be deterministic for this specific schedule execution
    val messageId = entity.id.derive("-scheduled-${entity.nextExecutionTime}")
    instanceEmitter.send(entity.instanceMessage, messageId)
    return OutboxResult.Success
}
```

### Step 7: Add IDV7.derive() Helper

**File: `IDV7.kt`**

```kotlin
/**
 * Derives a new IDV7 from this ID with the given suffix.
 * Useful for creating related IDs (e.g., model ID → message ID).
 */
fun derive(suffix: String): IDV7 {
    return IDV7(IdGenerator.deriveUuidV7FromV7(value, suffix))
}
```

### Step 8: Update Model Constructors

Remove `= IDV7.random()` defaults and require explicit IDs:

**WaitModel.kt**, **RetryModel.kt**, **ParentModel.kt**, **ForkModel.kt**:

```kotlin
data class WaitModel(
    override val id: IDV7,  // Remove default
    ...
)
```

### Step 9: Tests

1. **Unit tests** for `IDV7.deriveFromPositionAndStep()`:
    - Same inputs → same output (determinism)
    - Different positions → different output
    - Different steps → different output
    - Different suffixes → different output

2. **Unit tests** for `IDV7.derive()`:
    - Same suffix → same output
    - Different suffixes → different outputs

3. **Integration tests**:
    - Message redelivery produces same ID
    - Fork branches get unique IDs (different positions)
    - Nested forks get unique IDs
    - Database inserts are idempotent
    - Outbox processor redelivery produces same message ID
    - Transaction-scoped emissions are idempotent on retry

## Critical Files to Modify

### lemline-common

- `lemline-common/src/main/kotlin/com/lemline/common/values/IDV7.kt`

### lemline-core

- `lemline-core/src/main/kotlin/com/lemline/core/states/NodeStack.kt` (add deriveIdempotentId helper)

### lemline-runner

**Messaging:**
- `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/MessageEmitter.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt`

**Outbox Processors:**
- `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/WaitOutbox.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/RetryOutbox.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/ScheduleOutbox.kt`

**Models:**
- `lemline-runner/src/main/kotlin/com/lemline/runner/models/WaitModel.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/models/RetryModel.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/models/ParentModel.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/models/ForkModel.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/models/FailureModel.kt`

## Risks and Mitigations

1. **Position string length**: Long nested positions increase salt size
    - Mitigation: SHA-256 handles arbitrary length inputs; no functional impact

2. **Position changes during refactoring**: Renaming tasks changes positions
    - Mitigation: This is expected - renamed tasks are semantically different workflows

## Intentional Exclusions

The following are intentionally NOT covered by this plan:

| Item | Reason |
|------|--------|
| `ScheduleModel.id` | Each schedule execution is a new workflow instance; random ID is correct |
| `DefinitionModel` | Uses composite key (namespace, name, version); no IDV7 field |
| `ForkBranchModel` | Uses composite key (fork_id, branch_name); no IDV7 field |
| `InstanceStartCommand` (CLI) | User-initiated workflows use random WorkflowId intentionally |
| `WorkflowId` in `ScheduleOutbox` | Each scheduled run creates a new workflow instance |

## Comparison: forkId vs Position-Based

| Aspect            | forkId Approach                 | Position-Based           |
|-------------------|---------------------------------|--------------------------|
| RootState changes | Add `forkId: IDV7?` field       | None                     |
| Serialization     | Breaking change (nullable)      | No change                |
| Fork handling     | Derive forkId, pass to branches | Position already unique  |
| Nested forks      | Chain of derived IDs            | Positions naturally nest |
| Complexity        | Medium                          | Low                      |
