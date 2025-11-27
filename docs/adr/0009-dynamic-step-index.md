# [ADR-0009] Simple Integer Step Counter

## Status

Proposed

## Context

Lemline needs a way to uniquely identify each step in a workflow execution for:

1. **Message deduplication** - Prevent double-processing when brokers redeliver messages
2. **Database idempotency** - Prevent duplicate rows in outbox tables (waits, retries, parents)
3. **Deterministic identification** - Same execution path always produces same identifier

Currently, Lemline uses `NodePosition` (static JSON Pointer) to identify nodes in the workflow definition tree (e.g.,
`/do/taskA`). However, using `(workflowId, NodePosition)` for database primary keys causes **ID collisions** when the
same node is visited multiple times:

- **Wait task in a loop**: Each iteration creates a new wait, but all have the same `NodePosition` → collision
- **Retry task**: Each retry attempt needs its own database row, but shares the same `NodePosition` → collision
- **Parallel branch tasks**: Multiple concurrent executions of same branch → collision

Example collision scenario:

```yaml
for:
    in: [ 1, 2, 3 ]
    do:
        -   wait: PT1M  # Same NodePosition "/for/do/wait" on every iteration!
```

Without a unique step identifier, all three wait tasks would generate the same ID, causing database constraint violations.

The challenge is to create a step identifier that:

- Is deterministic (same execution path → same identifier)
- Distinguishes between multiple visits to the same node
- Is simple and easy to implement correctly
- Minimizes complexity (follows Lemline's core philosophy of pragmatism)

## Decision

We will add a simple **integer counter (`workflowStep: Int`)** to `WorkflowState` that increments each time we enter a task.

### How It Works

1. **Initialization**: `workflowStep` starts at 0 when workflow begins
2. **Task Entry**: Increment `workflowStep` each time we enter a task (from parent or child)
3. **Fork Branches**: Each branch initializes its own counter, which is restored when the branch completes
4. **Uniqueness**: `(workflowId, workflowStep)` creates a unique identifier for each execution step

### Examples

**Sequential tasks:**

```yaml
do:
    -   taskA:
            set: { x: 1 }
    -   taskB:
            set: { y: 2 }
```

Execution steps:

```
1. workflowStep=0  ← Enter taskA
2. workflowStep=1  ← Enter taskB
3. workflowStep=2  ← Complete workflow
```

**Foreach loop:**

```yaml
for:
    in: [ 1, 2, 3 ]
    do:
        -   task1:
                set: { x: . }
        -   task2:
                set: { y: . }
```

Execution steps:

```
Iteration 0:
  workflowStep=0  ← task1 (iteration 0)
  workflowStep=1  ← task2 (iteration 0)

Iteration 1:
  workflowStep=2  ← task1 (iteration 1)
  workflowStep=3  ← task2 (iteration 1)

Iteration 2:
  workflowStep=4  ← task1 (iteration 2)
  workflowStep=5  ← task2 (iteration 2)
```

**Fork (parallel branches):**

```yaml
fork:
    branches:
        -   name: branch1
            do:
                -   task1:
                        set: { x: 1 }
        -   name: branch2
            do:
                -   task2:
                        set: { y: 2 }
```

Execution steps (branches execute independently):

```
Parent: workflowStep=0  ← Enter fork

Branch 1: workflowStep=0  ← task1 (new counter for branch)
Branch 2: workflowStep=0  ← task2 (new counter for branch)

Parent: workflowStep=1  ← Resume after fork completes
```

**Try with retry:**

```yaml
try:
    -   failing:
            call: { http: ... }
catch:
    errors: ...
    retry:
        maxAttempts: 3
```

Execution steps:

```
Attempt 0:
  workflowStep=0  ← failing task (fails)

Retry (attempt 1):
  workflowStep=1  ← failing task retry (fails)

Retry (attempt 2):
  workflowStep=2  ← failing task retry (succeeds)
```

### Implementation

**1. Add workflowStep to RootState**

```kotlin
@Serializable
data class RootState(
    override val startedAt: Instant,
    val workflowId: WorkflowId,
    val workflowInput: JsonElement,
    val hasWaitingParent: Boolean = false,
    val workflowStep: Int = 0,  // NEW! Simple counter
) : BaseState()
```

**2. Add convenience property to WorkflowState**

```kotlin
@Serializable
sealed class WorkflowState {
    abstract val taskStates: TaskStates
    abstract val nodePosition: NodePosition

    val workflowId: WorkflowId get() = (taskStates[NodePosition.root] as RootState).workflowId
    val hasWaitingParent: Boolean get() = (taskStates[NodePosition.root] as RootState).hasWaitingParent
    val workflowStep: Int get() = (taskStates[NodePosition.root] as RootState).workflowStep  // NEW!
}
```

**3. Increment Logic**

The `workflowStep` is incremented in `StepByStepOrchestrator.resumeFromTask()` at the start of each task execution:

```kotlin
internal suspend fun resumeFromTask(
    taskStates: TaskStates,
    node: Node<*>,
    rawInput: JsonElement,
    flowDirective: FlowDirective? = null,
): WorkflowEvent {
    // Increment workflow step counter
    val rootState = taskStates[NodePosition.root] as RootState
    val updatedTaskStates = taskStates + (NodePosition.root to rootState.copy(
        workflowStep = rootState.workflowStep + 1
    ))

    // Continue with execution using updatedTaskStates...
}
```

**Key behaviors:**
- **Task entry**: Counter increments each time `resumeFromTask` is called (entering or re-entering any node)
- **Async operations**: `resumeFromCompletedTask` and `resumeFromFailedTask` do NOT increment (they resume existing tasks)
- **Fork branches**: Each branch starts with parent's counter value, continues incrementing independently
- **Branch completion**: Parent resumes with its last counter value, continues incrementing from there

**4. Remove visitCount from BaseState**

The old `visitCount` field is no longer needed:

```kotlin
// REMOVE this from BaseState:
abstract val visitCount: Int

// REMOVE increment logic from all processors:
// DoProcessor.stateEnterFromChild() - remove visitCount increment
// ForProcessor.stateEnterFromChild() - remove visitCount increment
// TryProcessor.stateEnterFromChild() - remove visitCount increment
// etc.
```

**5. Deterministic Message and Database IDs**

Use `(workflowId, workflowStep)` to generate unique, deterministic IDs for database tables:

```kotlin
// For message IDs (broker deduplication)
val messageId = IDV7.fromNamespace(
    namespace = workflowId,
    name = workflowStep.toString()
)

// For outbox table IDs (waits, retries, parents)
val waitId = IDV7.fromNamespace(
    namespace = workflowId,
    name = workflowStep.toString()
)
```

**Why this works:**

- **Unique per execution**: Counter increments on each task entry
    - Loop iteration 0, task 1: `workflowStep=0` → unique ID
    - Loop iteration 0, task 2: `workflowStep=1` → different ID
    - Loop iteration 1, task 1: `workflowStep=2` → different ID
- **Deterministic**: Same execution path → same sequence of workflowStep values → same UUIDs
- **No collisions**: Each wait/retry/parent gets unique database row

**Example: lemline_waits table with loop**

Given this workflow:

```yaml
for:
    in: [ 1, 2, 3 ]
    do:
        -   wait: PT1M
```

With workflowStep counter:

```sql
-- ✅ Each iteration gets unique row
CREATE TABLE lemline_waits
(
    id            UUID PRIMARY KEY, -- Derived from (workflowId, workflowStep)
    workflow_id   UUID NOT NULL,
    workflow_step INT NOT NULL,
    node_position TEXT NOT NULL,  -- Still stored for context/debugging
    ...
);

INSERT INTO lemline_waits VALUES
  (uuid_from_namespace(workflow_id, '0'), workflow_id, 0, '/for/do/wait', ...);  -- Iteration 0

INSERT INTO lemline_waits VALUES
  (uuid_from_namespace(workflow_id, '1'), workflow_id, 1, '/for/do/wait', ...);  -- Iteration 1

INSERT INTO lemline_waits VALUES
  (uuid_from_namespace(workflow_id, '2'), workflow_id, 2, '/for/do/wait', ...);  -- Iteration 2
```

## Consequences

### Positive

1. **Simplicity**: Just an integer - easy to understand, implement, and debug
    - No complex path building logic
    - No visit count tracking per node
    - Minimal risk of implementation bugs

2. **Unique identification without collisions**: `(workflowId, workflowStep)` creates unique IDs for each execution
    - Prevents database collisions
    - Enables deterministic reprocessing
    - Broker-level deduplication via message IDs
    - Database-level idempotency via primary keys

3. **Natural retry handling**: Each retry gets a new step number
    - First attempt: `workflowStep=0`
    - Retry: `workflowStep=1`
    - Each retry has unique identifier

4. **Aligns with Lemline architecture**:
    - Minimal complexity (pragmatic approach)
    - State carried in messages
    - Node states already track relevant execution context

5. **Fork isolation**: Each branch gets its own counter
    - No coordination needed between parallel branches
    - Natural isolation via separate WorkflowState instances
    - Parent counter restored after fork completes

### Negative

1. **Loss of structural information**: Step number doesn't encode position in workflow tree
    - `workflowStep=47` doesn't tell you where in the workflow you are
    - Mitigation: `NodePosition` is still stored in WorkflowState and database tables for context

2. **Debugging**: Need to look at NodePosition separately to understand location
    - Can't infer execution path from step number alone
    - Mitigation: All node states already contain relevant execution context (startedAt, etc.)

### Neutral

1. **No impact on existing NodePosition usage**: Static positions still used for node lookup in lemline-core
2. **Database schema**: Already stores both workflowStep and nodePosition for debugging

## Alternatives Considered

### 1. Dynamic JSON Pointer with Visit Counts (Previous Design)

Use hierarchical path with visit counts like `/do,0/taskA,0`:

**Rejected because:**

- Much more complex implementation
- Requires tracking visit counts per node in state
- More code to maintain and debug
- Path building logic adds overhead
- Over-engineered for the core requirement (unique IDs)
- Most debugging info already available in node states

### 2. Global Sequential Counter with Path Encoding

Combine both: increment counter AND track path.

**Rejected because:**

- Adds complexity without clear benefit
- Redundant information (path already in NodePosition)
- More state to serialize and carry in messages

### 3. Hash-Based Identifier

Hash the execution path: `hash(workflowId + nodePosition + timestamp)`

**Rejected because:**

- Timestamp makes it non-deterministic
- Can't guarantee uniqueness without collision handling
- Doesn't help with debugging

## References

- **Existing Architecture**:
    - [ADR-0002: Workflow Execution Model](0002-workflow-execution-model.md)
    - [ADR-0003: Messaging Architecture](0003-messaging-architecture.md)
    - [ADR-0004: Database Storage Strategy](0004-database-storage-strategy.md)

- **Related Concepts**:
    - Serverless Workflow DSL: https://serverlessworkflow.io/

- **Design Documents**:
    - `/docs/orchestrator-architecture.md` - Processor and tree navigation model
    - `/docs/runner-architecture.md` - Messaging and outbox patterns

- **UUID v7 (Deterministic)**:
    - RFC 4122, Section 4.3: https://tools.ietf.org/html/rfc4122#section-4.3
    - Used for generating deterministic IDs from workflowStep
