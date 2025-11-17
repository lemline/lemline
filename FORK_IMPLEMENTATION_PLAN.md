# Fork Task Implementation Plan

## Executive Summary

The Fork task enables concurrent execution of multiple subtasks (branches) within a workflow. It has two modes:
- **Cooperative mode** (`compete: false`, default): Waits for all branches to complete, returns array of results
- **Competitive mode** (`compete: true`): Returns as soon as first branch completes (racing)

**Architecture Strategy**: Fork follows the same pattern as child workflows using `WorkflowState.RunningFork` to enable:
- Step-by-step execution with pause/resume capability
- True parallelism in Complete mode using coroutines
- Distributed parallel execution in Async mode via runner scheduling

## Implementation Status (Updated 2025-11-16)

### ✅ Phase 1-4: Core Implementation (COMPLETED)

**Implemented in commit `2669a27` on branch `feature/fork-task-implementation`**

#### Completed Files:

1. **ForkException** (`WorkflowException.kt`)
   - Exception thrown on first fork entry
   - Contains branch configuration (compete mode, branch list)
   - Each branch has: index, name, nodePosition

2. **WorkflowState.RunningFork** (`WorkflowState.kt`)
   - State for async mode fork execution
   - Contains ForkConfig tracking branch progress
   - Includes completedBranches map

3. **ForkTaskState** (`ForkTaskState.kt`)
   - Runtime state for fork execution
   - Tracks branch states (PENDING/RUNNING/COMPLETED/FAULTED)
   - Stores branch outputs

4. **ForkProcessor** (`ForkProcessor.kt`)
   - Implements fork execution logic
   - Uses field reflection to access `compete` flag (workaround for DSL library)
   - Throws ForkException on first entry
   - Verifies completion on re-entry

5. **WorkflowOrchestrator Integration** (`WorkflowOrchestrator.kt`)
   - `processForkException()`: Handles fork execution in Complete mode
   - `executeForkBranches()`: Dispatcher for compete vs cooperative
   - `executeForkBranchesCompete()`: Race mode using `select{}`
   - `executeForkBranchesCooperative()`: Wait for all using `awaitAll()`
   - `executeSingleBranch()`: Execute individual branch
   - `extractBranchOutput()`: Extract output from branch state
   - `updateForkStateWithResults()`: Update state with completed branches
   - Uses `completeInterruptedTask()` for output transformation and export

#### Test Coverage:

**10 comprehensive tests** in `ForkTaskExecutionTest.kt`:
1. ✅ Parallel execution in cooperative mode
2. ✅ First-wins behavior in competitive mode
3. ✅ Input passing to all branches
4. ✅ Output order preservation
5. ✅ Output transformation with JQ expressions
6. ✅ Nested control flow support
7. ✅ If condition evaluation
8. ✅ Single branch execution
9. ✅ Context export functionality
10. ✅ Default cooperative mode

**Test Results**: All passing, full lemline-core test suite: BUILD SUCCESSFUL

#### Key Implementation Details:

- **Field Reflection**: Used `getDeclaredField("compete")` with `isAccessible = true` to access compete flag
- **Output Transformation**: Uses `completeInterruptedTask()` pattern (same as Wait/ChildWorkflow)
- **Context Export**: Works with `$context.variableName` syntax
- **JQ Expressions**: Simplified to use arithmetic (`+`) instead of `add` filter
- **True Parallelism**: Kotlin coroutines with `async`/`await`/`select` for concurrent execution
- **Refactored**: Extracted common branch execution logic to eliminate duplication (commit `c3a3c25`)

### ❌ Phase 5: Runner Integration for Async Mode (NOT STARTED)

This phase will enable fork tasks in distributed/async execution mode where branches can execute on different workers.

**Final Architecture Decision** (Based on Concurrency & Compatibility Analysis):
- ✅ **Multiple Rows** (one per branch) - Better concurrency, observability
- ✅ **Pessimistic Locking** (FOR UPDATE) - Simple, correct, good for expected load
- ✅ **Database-Agnostic SQL** - Works on PostgreSQL, MySQL, H2
- ✅ **Separate Migration Files** - Per-database schema optimizations
- 📋 **Optional DB-Specific Code** - Can add if profiling shows benefit

**Why Multiple Rows?**
- 3-4x faster throughput under concurrent load (20ms vs 60ms for 3 branches)
- Shorter lock hold time (5ms vs 20ms - only locks fork metadata, not branch data)
- Branch updates don't conflict (different rows)
- Better observability (can query individual branch states)
- More database-agnostic (no JSONB operations required)

---

## Architecture Analysis

### Key Implementation Philosophy

1. **Pure Functional State Management**: State is external to nodes, stored in `Map<NodePosition, TaskState>`
2. **Step-by-Step Execution**: Each processor implements `getNextStepInfo()` to determine next node and updated state
3. **Exception-Driven Control Flow**: Special cases (waits, retries, child workflows, forks) throw exceptions caught by orchestrator
4. **Immutable Nodes**: `Node<T>` objects are immutable definitions; runtime state in separate `TaskState` objects
5. **WorkflowState for Interruptions**: Tasks that need external coordination (Wait, ChildWorkflow, Fork) return WorkflowState

### Similar Patterns

- **ChildWorkflow Pattern**: Throws exception → orchestrator returns `WorkflowState.RunningChildWorkflow` → runner handles execution → resumes parent
- **WaitTask Pattern**: Throws exception → orchestrator returns `WorkflowState.Waiting` → runner schedules wake-up → resumes workflow
- **ForProcessor** (iteration): Tracks `collection` and `index`, provides scope variables
- **TryProcessor** (branching): Has multiple child paths (`try` and `catch`), handles error routing

### Actual Implementation Flow

#### Complete Mode (IMPLEMENTED)

```kotlin
// 1. ForkProcessor.getNextStepInfo() - First entry
if (state.branchStates.values.all { it == BranchState.PENDING }) {
    throw ForkException(transformedInput, config)
}

// 2. WorkflowOrchestrator.processForkException()
// - Builds nodesMap by traversing from root
// - Creates ForkTaskState if not exists
// - Calls executeForkBranches()

// 3. executeForkBranches() - Dispatcher
return if (compete) {
    executeForkBranchesCompete(...)
} else {
    executeForkBranchesCooperative(...)
}

// 4a. Compete mode - Race with select{}
val results = coroutineScope {
    branches.map { async { executeSingleBranch(...) } }
}
val (index, output) = select {
    results.forEach { deferred -> deferred.onAwait { it } }
}

// 4b. Cooperative mode - Wait for all
val outputs = coroutineScope {
    branches.map { async { executeSingleBranch(...) } }
}.awaitAll()

// 5. executeSingleBranch() - Execute branch
val result = resumeFromTask(
    taskStates = taskStates,
    node = branchNode,
    rawInput = branchInput,
    executionMode = executionMode
)
return extractBranchOutput(result, branch.name)

// 6. extractBranchOutput() - Extract output
when (result) {
    is ReadyForNextTask -> result.rawInput  // Branch stopped at fork
    is Completed -> result.output
    is Failed -> throw result.exception
}

// 7. completeInterruptedTask() - Apply transformations
// - Applies output transformation (output.as)
// - Applies context export (export.as)
// - Returns to parent workflow
```

#### Async Mode (NOT YET IMPLEMENTED)

Will follow the same pattern as ChildWorkflow async mode, but with branch coordination.

---

## Phase 5: Runner Integration (Async Mode) - DETAILED PLAN

### Overview

In async mode, fork execution must be distributed across workers with proper state management and branch coordination. This enables:
- Branches executing on different workers
- Full pause/resume capability for each branch
- Efficient resource utilization in distributed systems

### Architecture for Async Fork Execution

```
┌─────────────────────────────────────────────────────────────┐
│ Worker 1: Parent Workflow                                   │
│                                                              │
│  1. Fork task throws ForkException                          │
│  2. Orchestrator returns WorkflowState.RunningFork          │
│  3. StepByStepRunner catches state                          │
│  4. Emits DatabaseMessage with RunningFork state            │
│                                                              │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   v
┌─────────────────────────────────────────────────────────────┐
│ Database: lemline_forks table                               │
│                                                              │
│  - instance_id (UUID)                                       │
│  - fork_position (NodePosition)                             │
│  - compete (Boolean)                                        │
│  - branch_count (Int)                                       │
│  - completed_count (Int)                                    │
│  - task_states (JSONB) - parent workflow state              │
│  - branch_outputs (JSONB) - Map<Int, JsonElement>          │
│  - status (PENDING/RUNNING/COMPLETED/FAILED)                │
│  - created_at, updated_at                                   │
│                                                              │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   v
┌─────────────────────────────────────────────────────────────┐
│ DatabaseMessageHandler                                       │
│                                                              │
│  1. Receives RunningFork message                            │
│  2. Inserts row into lemline_forks                          │
│  3. Emits InstanceMessage for each branch                   │
│                                                              │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   v
        ┌──────────┴──────────┬──────────────┐
        │                     │              │
        v                     v              v
┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│ Worker 2      │    │ Worker 3      │    │ Worker 4      │
│ Branch 0      │    │ Branch 1      │    │ Branch 2      │
│               │    │               │    │               │
│ Executes      │    │ Executes      │    │ Executes      │
│ branch tasks  │    │ branch tasks  │    │ branch tasks  │
│               │    │               │    │               │
│ Returns to    │    │ Returns to    │    │ Returns to    │
│ fork position │    │ fork position │    │ fork position │
└───────┬───────┘    └───────┬───────┘    └───────┬───────┘
        │                    │                    │
        └──────────┬─────────┴────────────────────┘
                   │
                   v
┌─────────────────────────────────────────────────────────────┐
│ BranchCompletionHandler                                      │
│                                                              │
│  1. Detects ReadyForNextTask at fork position               │
│  2. Updates lemline_forks with branch output                │
│  3. Increments completed_count                              │
│  4. Checks if fork is complete                              │
│                                                              │
│  IF COMPLETE:                                               │
│    - Assembles final output (single or array)               │
│    - Emits InstanceMessage to resume parent at fork         │
│    - Deletes row from lemline_forks                         │
│                                                              │
│  IF NOT COMPLETE:                                           │
│    - Updates lemline_forks row                              │
│    - Waits for more branches                                │
│                                                              │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   v (when complete)
┌─────────────────────────────────────────────────────────────┐
│ Worker X: Parent Workflow Resumes                           │
│                                                              │
│  1. InstanceMessage at fork position                        │
│  2. Orchestrator completes fork task                        │
│  3. Continues parent workflow                               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Database Schema

**Decision**: **Multiple Rows (One Per Branch) + Pessimistic Locking**

After concurrency analysis, the multiple-rows approach provides:
- ✅ 3-4x better throughput under concurrent load
- ✅ Shorter lock hold time (5ms vs 20ms)
- ✅ Better observability (query individual branches)
- ✅ Simpler retry logic (branches don't retry, only completion check)

#### Table: `lemline_forks` (Fork Metadata)

Stores fork configuration and parent workflow state:

```sql
-- PostgreSQL version (see migration files for MySQL/H2 variants)
CREATE TABLE lemline_forks (
    -- Primary identification
    instance_id UUID NOT NULL,
    fork_position TEXT NOT NULL,  -- Serialized NodePosition

    -- Fork configuration
    compete BOOLEAN NOT NULL,
    branch_count INT NOT NULL,

    -- State storage (parent workflow state)
    task_states TEXT NOT NULL,  -- JSON serialized TaskStates

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    PRIMARY KEY (instance_id, fork_position)
);

-- Indexes
CREATE INDEX idx_forks_created ON lemline_forks(created_at);
```

#### Table: `lemline_fork_branches` (Branch Execution State)

One row per branch, tracks individual branch execution:

```sql
-- PostgreSQL version
CREATE TABLE lemline_fork_branches (
    -- Primary identification
    instance_id UUID NOT NULL,
    fork_position TEXT NOT NULL,
    branch_index INT NOT NULL,

    -- Branch metadata
    branch_name TEXT NOT NULL,
    branch_node_position TEXT NOT NULL,  -- Serialized NodePosition

    -- Execution state
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    output TEXT,  -- JSON, NULL until completed
    error TEXT,   -- Error details if FAILED

    -- Timestamps
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    PRIMARY KEY (instance_id, fork_position, branch_index),

    FOREIGN KEY (instance_id, fork_position)
        REFERENCES lemline_forks(instance_id, fork_position)
        ON DELETE CASCADE,

    CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CHECK (branch_index >= 0)
);

-- Indexes for performance
CREATE INDEX idx_fork_branches_status
    ON lemline_fork_branches(instance_id, fork_position, status);

-- Partial index for fast completed branch lookup (PostgreSQL)
CREATE INDEX idx_fork_branches_completed
    ON lemline_fork_branches(instance_id, fork_position)
    WHERE status = 'COMPLETED';
```

#### Data Models

```kotlin
data class ForkModel(
    val instanceId: UUID,
    val forkPosition: String,  // Serialized NodePosition
    val compete: Boolean,
    val branchCount: Int,
    val taskStates: String,  // JSON
    val createdAt: Instant
)

data class ForkBranchModel(
    val instanceId: UUID,
    val forkPosition: String,
    val branchIndex: Int,
    val branchName: String,
    val branchNodePosition: String,
    val status: BranchStatus,
    val output: String?,  // JSON, nullable
    val error: String?,   // Nullable
    val startedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

enum class BranchStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
```

### Implementation Details

#### 5.1 Database Models

**Location**: `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/fork/`

```kotlin
// ForkModel.kt
data class ForkModel(
    val instanceId: UUID,
    val forkPosition: String,  // Serialized NodePosition
    val compete: Boolean,
    val branchCount: Int,
    val completedCount: Int,
    val status: ForkStatus,
    val taskStates: String,  // JSON
    val branchOutputs: String,  // JSON
    val branchMetadata: String,  // JSON
    val createdAt: Instant,
    val updatedAt: Instant
)

enum class ForkStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
```

#### 5.2 Repository

**Location**: `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/fork/ForkRepository.kt`

```kotlin
interface ForkRepository {
    /**
     * Insert a new fork with all branches.
     * Uses transaction to ensure atomicity.
     */
    suspend fun insertForkWithBranches(
        fork: ForkModel,
        branches: List<ForkBranchModel>
    )

    /**
     * Find fork by instance ID and position.
     */
    suspend fun findByInstanceAndPosition(
        instanceId: UUID,
        forkPosition: NodePosition
    ): ForkModel?

    /**
     * Record branch completion using PESSIMISTIC LOCKING.
     *
     * This method:
     * 1. Updates the branch row (no lock needed - different row per branch)
     * 2. Locks the fork row using FOR UPDATE (prevents concurrent completion checks)
     * 3. Counts completed branches
     * 4. Returns whether fork is complete
     *
     * Guarantees:
     * - No lost updates (pessimistic lock)
     * - Atomic completion detection
     * - Short lock hold time (~5ms)
     */
    suspend fun recordBranchCompletion(
        instanceId: UUID,
        forkPosition: NodePosition,
        branchIndex: Int,
        branchOutput: JsonElement
    ): ForkCompletionResult

    /**
     * Get all branches for a fork.
     */
    suspend fun getBranches(
        instanceId: UUID,
        forkPosition: NodePosition
    ): List<ForkBranchModel>

    /**
     * Delete fork and all branches (CASCADE).
     */
    suspend fun delete(
        instanceId: UUID,
        forkPosition: NodePosition
    )

    /**
     * Clean up old forks (safety mechanism for orphaned forks).
     */
    suspend fun cleanupOldForks(olderThan: Instant): Int
}

/**
 * Result of branch completion check.
 */
data class ForkCompletionResult(
    val isComplete: Boolean,
    val completedCount: Int,
    val branchCount: Int,
    val compete: Boolean,
    val taskStates: TaskStates
)
```

#### 5.3 StepByStepRunner Integration

**Location**: `lemline-runner/src/main/kotlin/com/lemline/runner/StepByStepRunner.kt`

Add handler for `WorkflowState.RunningFork`:

```kotlin
// In StepByStepRunner.run()
when (val state = orchestrator.resume(...)) {
    is WorkflowState.RunningFork -> {
        logger.debug { "Fork started, scheduling branches" }
        onForkStarted(state)
        return state
    }
    // ... other cases
}

/**
 * Handle fork start - emit database message to persist fork state.
 */
private fun onForkStarted(state: WorkflowState.RunningFork) {
    // Create fork outbox model
    val forkOutbox = ForkOutboxModel(
        instanceId = instanceId,
        forkPosition = state.nodePosition,
        compete = state.forkConfig.compete,
        branches = state.forkConfig.branches,
        taskStates = state.taskStates,
        rawInput = state.rawInput
    )

    // Emit to database message handler
    databaseMessageEmitter.emit(
        DatabaseMessage.ForkStarted(forkOutbox)
    )
}
```

#### 5.4 DatabaseMessage Extension

**Location**: `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessage.kt`

```kotlin
sealed class DatabaseMessage {
    // ... existing messages

    /**
     * Fork started - need to persist state and schedule branches.
     */
    @Serializable
    data class ForkStarted(
        val instanceId: UUID,
        val forkPosition: NodePosition,
        val compete: Boolean,
        val branches: List<BranchExecution>,
        val taskStates: TaskStates,
        val rawInput: JsonElement
    ) : DatabaseMessage()
}
```

#### 5.5 DatabaseMessageHandler Integration

**Location**: `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessageHandler.kt`

```kotlin
// In message handler
when (message) {
    is DatabaseMessage.ForkStarted -> handleForkStarted(message)
    // ... other cases
}

private suspend fun handleForkStarted(message: DatabaseMessage.ForkStarted) {
    logger.debug {
        "Handling fork started: instance=${message.instanceId}, " +
        "position=${message.forkPosition}, branches=${message.branches.size}"
    }

    // 1. Save fork state to database
    val forkModel = ForkModel(
        instanceId = message.instanceId,
        forkPosition = message.forkPosition.serialize(),
        compete = message.compete,
        branchCount = message.branches.size,
        completedCount = 0,
        status = ForkStatus.RUNNING,
        taskStates = json.encodeToString(message.taskStates),
        branchOutputs = "{}",
        branchMetadata = json.encodeToString(
            message.branches.map { branch ->
                BranchMetadata(
                    index = branch.index,
                    name = branch.name,
                    nodePosition = branch.nodePosition,
                    status = BranchStatus.PENDING
                )
            }
        ),
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now()
    )

    forkRepository.insert(forkModel).await()

    // 2. Emit instance messages for each branch
    message.branches.forEach { branch ->
        val branchMessage = InstanceMessage(
            workflowKey = workflowKey,
            instanceId = message.instanceId,
            state = WorkflowState.ReadyForNextTask(
                taskStates = message.taskStates,
                nodePosition = branch.nodePosition,
                rawInput = message.rawInput,
                flowDirective = null
            ),
            metadata = mapOf(
                "forkPosition" to message.forkPosition.serialize(),
                "branchIndex" to branch.index.toString(),
                "branchName" to branch.name
            )
        )

        logger.debug { "Scheduling branch ${branch.name} (index ${branch.index})" }
        instanceMessageEmitter.emit(branchMessage)
    }
}
```

#### 5.6 InstanceMessageHandler - Branch Completion Detection

**Location**: `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/instances/InstanceMessageHandler.kt`

```kotlin
// In message handler, after executing step
when (val state = runner.run(...)) {
    is WorkflowState.ReadyForNextTask -> {
        // Check if this is a branch completion
        if (isBranchCompletion(state, message)) {
            handleBranchCompletion(state, message)
        } else {
            // Normal task continuation
            emitNextMessage(state)
        }
    }
    // ... other cases
}

/**
 * Detect if this is a branch returning to its fork parent.
 */
private fun isBranchCompletion(
    state: WorkflowState.ReadyForNextTask,
    message: InstanceMessage
): Boolean {
    // Check if message has fork metadata
    val forkPosition = message.metadata?.get("forkPosition") ?: return false

    // Check if current position matches fork position
    return state.nodePosition.serialize() == forkPosition
}

/**
 * Handle branch completion.
 */
private suspend fun handleBranchCompletion(
    state: WorkflowState.ReadyForNextTask,
    message: InstanceMessage
) {
    val forkPosition = NodePosition.deserialize(message.metadata!!["forkPosition"]!!)
    val branchIndex = message.metadata["branchIndex"]!!.toInt()
    val branchOutput = state.rawInput

    logger.debug {
        "Branch $branchIndex completed for fork at $forkPosition, " +
        "output: ${branchOutput.toString().take(100)}"
    }

    // Update fork state atomically
    val updatedFork = forkRepository.recordBranchCompletion(
        instanceId = message.instanceId,
        forkPosition = forkPosition,
        branchIndex = branchIndex,
        branchOutput = branchOutput
    ).await()

    // Check if fork is complete
    val isForkComplete = when {
        updatedFork.compete && updatedFork.completedCount > 0 -> true
        !updatedFork.compete && updatedFork.completedCount == updatedFork.branchCount -> true
        else -> false
    }

    if (isForkComplete) {
        logger.debug { "Fork complete at $forkPosition, resuming parent workflow" }
        resumeForkParent(updatedFork)
    } else {
        logger.debug {
            "Fork not complete: ${updatedFork.completedCount}/${updatedFork.branchCount} branches done"
        }
    }
}

/**
 * Resume parent workflow after fork completes.
 */
private suspend fun resumeForkParent(fork: ForkModel) {
    // Assemble output
    val branchOutputs = json.decodeFromString<Map<Int, JsonElement>>(fork.branchOutputs)
    val assembledOutput = if (fork.compete) {
        // Compete mode: return first completed branch output
        branchOutputs.values.first()
    } else {
        // Cooperative mode: return array in order
        JsonArray(
            (0 until fork.branchCount).map { index ->
                branchOutputs[index] ?: throw IllegalStateException("Branch $index not completed")
            }
        )
    }

    // Load task states
    val taskStates = json.decodeFromString<TaskStates>(fork.taskStates)

    // Create resume message
    val resumeMessage = InstanceMessage(
        workflowKey = workflowKey,
        instanceId = fork.instanceId,
        state = WorkflowState.ReadyForNextTask(
            taskStates = taskStates,
            nodePosition = NodePosition.deserialize(fork.forkPosition),
            rawInput = assembledOutput,
            flowDirective = null
        )
    )

    // Emit resume message
    instanceMessageEmitter.emit(resumeMessage)

    // Clean up fork state
    forkRepository.delete(fork.instanceId, NodePosition.deserialize(fork.forkPosition)).await()

    logger.debug { "Fork parent resumed, fork state deleted" }
}
```

#### 5.7 Repository Implementation

**Location**: `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/fork/ForkRepositoryImpl.kt`

```kotlin
@ApplicationScoped
class ForkRepositoryImpl(
    private val client: Pool,  // Works with PgPool, MySQLPool, etc.
    private val json: Json
) : ForkRepository {

    override suspend fun insertForkWithBranches(
        fork: ForkModel,
        branches: List<ForkBranchModel>
    ) {
        client.withTransaction { tx ->
            // 1. Insert fork metadata
            tx.preparedQuery(
                """
                INSERT INTO lemline_forks (
                    instance_id, fork_position, compete, branch_count,
                    task_states, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """
            ).execute(
                Tuple.of(
                    fork.instanceId,
                    fork.forkPosition,
                    fork.compete,
                    fork.branchCount,
                    fork.taskStates,
                    fork.createdAt.toJavaInstant()
                )
            )
            .awaitSuspending()

            // 2. Batch insert all branches
            val batchArgs = branches.map { branch ->
                Tuple.of(
                    branch.instanceId,
                    branch.forkPosition,
                    branch.branchIndex,
                    branch.branchName,
                    branch.branchNodePosition,
                    branch.status.name,
                    branch.createdAt.toJavaInstant(),
                    branch.updatedAt.toJavaInstant()
                )
            }

            tx.preparedQuery(
                """
                INSERT INTO lemline_fork_branches (
                    instance_id, fork_position, branch_index, branch_name,
                    branch_node_position, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """
            ).executeBatch(batchArgs).awaitSuspending()
        }
    }

    /**
     * Record branch completion with PESSIMISTIC LOCKING.
     *
     * Uses database-agnostic SQL:
     * - FOR UPDATE (no OF clause - works on MySQL and PostgreSQL)
     * - COUNT(CASE WHEN ...) instead of FILTER clause
     * - Explicit GROUP BY for all non-aggregated columns
     */
    override suspend fun recordBranchCompletion(
        instanceId: UUID,
        forkPosition: NodePosition,
        branchIndex: Int,
        branchOutput: JsonElement
    ): ForkCompletionResult {
        return client.withTransaction { tx ->
            // STEP 1: Update branch row (no lock needed - different row per branch)
            tx.preparedQuery(
                """
                UPDATE lemline_fork_branches
                SET
                    status = ?,
                    output = ?,
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE instance_id = ?
                  AND fork_position = ?
                  AND branch_index = ?
                """
            ).execute(
                Tuple.of(
                    "COMPLETED",
                    json.encodeToString(branchOutput),
                    instanceId,
                    forkPosition.serialize(),
                    branchIndex
                )
            ).awaitSuspending()

            // STEP 2: Lock fork row and count completed branches
            // ⬅️ Uses FOR UPDATE for pessimistic locking
            // ⬅️ Uses COUNT(CASE WHEN...) for database compatibility
            val rowSet = tx.preparedQuery(
                """
                SELECT
                    f.instance_id,
                    f.fork_position,
                    f.compete,
                    f.branch_count,
                    f.task_states,
                    f.created_at,
                    COUNT(CASE WHEN b.status = 'COMPLETED' THEN 1 END) as completed_count
                FROM lemline_forks f
                LEFT JOIN lemline_fork_branches b
                    ON f.instance_id = b.instance_id
                    AND f.fork_position = b.fork_position
                WHERE f.instance_id = ?
                  AND f.fork_position = ?
                GROUP BY
                    f.instance_id,
                    f.fork_position,
                    f.compete,
                    f.branch_count,
                    f.task_states,
                    f.created_at
                FOR UPDATE
                """
            ).execute(
                Tuple.of(
                    instanceId,
                    forkPosition.serialize()
                )
            ).awaitSuspending()

            val row = rowSet.first()

            val compete = row.getBoolean("compete")
            val branchCount = row.getInteger("branch_count")
            val completedCount = row.getLong("completed_count")
            val taskStatesJson = row.getString("task_states")

            val isComplete = if (compete) {
                completedCount > 0
            } else {
                completedCount == branchCount.toLong()
            }

            ForkCompletionResult(
                isComplete = isComplete,
                completedCount = completedCount.toInt(),
                branchCount = branchCount,
                compete = compete,
                taskStates = json.decodeFromString(taskStatesJson)
            )
        }
    }

    override suspend fun getBranches(
        instanceId: UUID,
        forkPosition: NodePosition
    ): List<ForkBranchModel> {
        val rowSet = client.preparedQuery(
            """
            SELECT * FROM lemline_fork_branches
            WHERE instance_id = ?
              AND fork_position = ?
            ORDER BY branch_index
            """
        ).execute(
            Tuple.of(instanceId, forkPosition.serialize())
        ).awaitSuspending()

        return rowSet.map { it.toForkBranchModel() }
    }

    override suspend fun delete(
        instanceId: UUID,
        forkPosition: NodePosition
    ) {
        client.preparedQuery(
            """
            DELETE FROM lemline_forks
            WHERE instance_id = ? AND fork_position = ?
            """
        ).execute(
            Tuple.of(instanceId, forkPosition.serialize())
        ).awaitSuspending()
    }

    override suspend fun cleanupOldForks(olderThan: Instant): Int {
        val rowSet = client.preparedQuery(
            """
            DELETE FROM lemline_forks
            WHERE created_at < ?
            """
        ).execute(
            Tuple.of(olderThan.toJavaInstant())
        ).awaitSuspending()

        return rowSet.rowCount()
    }

    private fun Row.toForkModel() = ForkModel(
        instanceId = getUUID("instance_id"),
        forkPosition = getString("fork_position"),
        compete = getBoolean("compete"),
        branchCount = getInteger("branch_count"),
        taskStates = getString("task_states"),
        createdAt = getLocalDateTime("created_at").toKotlinInstant()
    )

    private fun Row.toForkBranchModel() = ForkBranchModel(
        instanceId = getUUID("instance_id"),
        forkPosition = getString("fork_position"),
        branchIndex = getInteger("branch_index"),
        branchName = getString("branch_name"),
        branchNodePosition = getString("branch_node_position"),
        status = BranchStatus.valueOf(getString("status")),
        output = getString("output"),
        error = getString("error"),
        startedAt = getLocalDateTime("started_at")?.toKotlinInstant(),
        completedAt = getLocalDateTime("completed_at")?.toKotlinInstant(),
        createdAt = getLocalDateTime("created_at").toKotlinInstant(),
        updatedAt = getLocalDateTime("updated_at").toKotlinInstant()
    )
}
```

### Database-Specific Notes

The repository implementation above uses **database-agnostic SQL** that works on PostgreSQL, MySQL, and H2.

If performance optimization is needed for specific databases, you can add database-specific implementations:

```kotlin
@ApplicationScoped
class ForkRepositoryImpl(
    private val client: Pool,
    private val json: Json,
    @ConfigProperty(name = "quarkus.datasource.db-kind") private val dbKind: String
) : ForkRepository {

    override fun recordBranchCompletion(...): Uni<ForkCompletionResult> {
        // Use database-agnostic implementation by default
        // Can add DB-specific optimizations if metrics show it's needed
        return recordBranchCompletionGeneric(...)
    }

    // PostgreSQL-optimized version (if needed in future)
    private fun recordBranchCompletionPostgres(...): Uni<ForkCompletionResult> {
        // Could use FILTER clause and FOR UPDATE OF for slight performance gain
        // Only implement if profiling shows benefit
        TODO("Optional optimization")
    }

    private fun recordBranchCompletionGeneric(...): Uni<ForkCompletionResult> {
        // Implementation shown above
    }
}
```

**Decision**: Start with database-agnostic implementation. Add DB-specific optimizations only if profiling shows benefit.

#### 5.8 Database Migrations

Separate migration files for each supported database to handle schema differences.

##### PostgreSQL Migration

**Location**: `lemline-runner/src/main/resources/db/migration/postgresql/V006__create_lemline_forks.sql`

```sql
-- Fork metadata table
CREATE TABLE lemline_forks (
    instance_id UUID NOT NULL,
    fork_position TEXT NOT NULL,
    compete BOOLEAN NOT NULL,
    branch_count INT NOT NULL,
    task_states TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (instance_id, fork_position)
);

-- Branch execution table
CREATE TABLE lemline_fork_branches (
    instance_id UUID NOT NULL,
    fork_position TEXT NOT NULL,
    branch_index INT NOT NULL,
    branch_name TEXT NOT NULL,
    branch_node_position TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    output TEXT,
    error TEXT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (instance_id, fork_position, branch_index),

    CONSTRAINT fk_fork_branches_fork
        FOREIGN KEY (instance_id, fork_position)
        REFERENCES lemline_forks(instance_id, fork_position)
        ON DELETE CASCADE,

    CONSTRAINT chk_branch_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),

    CONSTRAINT chk_branch_index
        CHECK (branch_index >= 0)
);

-- Indexes
CREATE INDEX idx_fork_branches_status
    ON lemline_fork_branches(instance_id, fork_position, status);

CREATE INDEX idx_fork_branches_completed
    ON lemline_fork_branches(instance_id, fork_position)
    WHERE status = 'COMPLETED';  -- Partial index (PostgreSQL)

CREATE INDEX idx_forks_created
    ON lemline_forks(created_at);

-- Comments
COMMENT ON TABLE lemline_forks IS 'Fork metadata for async parallel execution';
COMMENT ON TABLE lemline_fork_branches IS 'Individual branch execution state';
COMMENT ON COLUMN lemline_forks.compete IS 'True if compete mode (first wins), false if cooperative (wait all)';
COMMENT ON COLUMN lemline_fork_branches.status IS 'Branch execution status: PENDING, RUNNING, COMPLETED, FAILED';
```

##### MySQL Migration

**Location**: `lemline-runner/src/main/resources/db/migration/mysql/V006__create_lemline_forks.sql`

```sql
-- Fork metadata table
CREATE TABLE lemline_forks (
    instance_id BINARY(16) NOT NULL,  -- UUID as binary
    fork_position VARCHAR(1000) NOT NULL,
    compete TINYINT(1) NOT NULL,
    branch_count INT NOT NULL,
    task_states MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (instance_id, fork_position(255))  -- Need length for VARCHAR in key
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Branch execution table
CREATE TABLE lemline_fork_branches (
    instance_id BINARY(16) NOT NULL,
    fork_position VARCHAR(1000) NOT NULL,
    branch_index INT NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    branch_node_position VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    output MEDIUMTEXT,
    error TEXT,
    started_at TIMESTAMP NULL DEFAULT NULL,
    completed_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (instance_id, fork_position(255), branch_index),

    CONSTRAINT fk_fork_branches_fork
        FOREIGN KEY (instance_id, fork_position(255))
        REFERENCES lemline_forks(instance_id, fork_position(255))
        ON DELETE CASCADE,

    CONSTRAINT chk_branch_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),

    CONSTRAINT chk_branch_index
        CHECK (branch_index >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Indexes (no partial indexes in MySQL < 8.0.13)
CREATE INDEX idx_fork_branches_status
    ON lemline_fork_branches(instance_id, fork_position(255), status);

CREATE INDEX idx_fork_branches_completed
    ON lemline_fork_branches(instance_id, fork_position(255), status);

CREATE INDEX idx_forks_created
    ON lemline_forks(created_at);
```

##### H2 Migration (for tests)

**Location**: `lemline-runner/src/main/resources/db/migration/h2/V006__create_lemline_forks.sql`

```sql
-- Fork metadata table
CREATE TABLE lemline_forks (
    instance_id UUID NOT NULL,
    fork_position VARCHAR(1000) NOT NULL,
    compete BOOLEAN NOT NULL,
    branch_count INT NOT NULL,
    task_states CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (instance_id, fork_position)
);

-- Branch execution table
CREATE TABLE lemline_fork_branches (
    instance_id UUID NOT NULL,
    fork_position VARCHAR(1000) NOT NULL,
    branch_index INT NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    branch_node_position VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    output CLOB,
    error CLOB,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (instance_id, fork_position, branch_index),

    CONSTRAINT fk_fork_branches_fork
        FOREIGN KEY (instance_id, fork_position)
        REFERENCES lemline_forks(instance_id, fork_position)
        ON DELETE CASCADE,

    CONSTRAINT chk_branch_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),

    CONSTRAINT chk_branch_index
        CHECK (branch_index >= 0)
);

-- Indexes
CREATE INDEX idx_fork_branches_status
    ON lemline_fork_branches(instance_id, fork_position, status);

CREATE INDEX idx_fork_branches_completed
    ON lemline_fork_branches(instance_id, fork_position, status);

CREATE INDEX idx_forks_created
    ON lemline_forks(created_at);
```

### Error Handling in Async Mode

#### Branch Failures

**Scenario**: A branch throws an exception during execution.

**Handling**:
```kotlin
// In InstanceMessageHandler, when branch execution fails
when (val state = runner.run(...)) {
    is WorkflowState.Failed -> {
        // Check if this is a branch failure
        if (isBranchExecution(message)) {
            handleBranchFailure(state, message)
        } else {
            // Normal workflow failure
            handleWorkflowFailure(state)
        }
    }
}

private suspend fun handleBranchFailure(
    state: WorkflowState.Failed,
    message: InstanceMessage
) {
    val forkPosition = NodePosition.deserialize(message.metadata!!["forkPosition"]!!)
    val branchIndex = message.metadata["branchIndex"]!!.toInt()

    logger.error { "Branch $branchIndex failed for fork at $forkPosition: ${state.error}" }

    // Load fork state
    val fork = forkRepository.findByInstanceAndPosition(
        message.instanceId,
        forkPosition
    ).await() ?: throw IllegalStateException("Fork not found")

    if (fork.compete) {
        // Compete mode: Other branches may still succeed
        // Just log the failure, don't fail the fork yet
        logger.debug { "Compete mode: ignoring branch $branchIndex failure, waiting for other branches" }
    } else {
        // Cooperative mode: Entire fork fails
        logger.error { "Cooperative mode: branch $branchIndex failed, failing entire fork" }

        // Delete fork state
        forkRepository.delete(message.instanceId, forkPosition).await()

        // Emit failure message to parent workflow
        instanceMessageEmitter.emit(
            InstanceMessage(
                workflowKey = workflowKey,
                instanceId = message.instanceId,
                state = state  // Forward the Failed state
            )
        )
    }
}
```

#### Fork Timeout

Use a scheduled cleanup job to detect and handle orphaned forks:

```kotlin
@ApplicationScoped
class ForkCleanupScheduler(
    private val forkRepository: ForkRepository,
    private val instanceMessageEmitter: InstanceMessageEmitter
) {

    @Scheduled(every = "5m")
    fun cleanupStaleForks() {
        val threshold = Clock.System.now() - 1.hours

        forkRepository.cleanupOldForks(threshold)
            .subscribe()
            .with(
                { count -> logger.info { "Cleaned up $count stale forks" } },
                { error -> logger.error(error) { "Failed to cleanup stale forks" } }
            )
    }
}
```

### Testing Strategy for Async Mode

#### Integration Tests

**Location**: `lemline-runner/src/test/kotlin/com/lemline/runner/tests/ForkAsyncTest.kt`

```kotlin
@QuarkusTest
@TestProfile(PostgresProfile::class)
class ForkAsyncTest : FunSpec({

    test("fork in async mode schedules branches independently") {
        val yaml = """
            do:
              - testFork:
                  fork:
                    branches:
                      - branch1:
                          set: { value: 1 }
                      - branch2:
                          set: { value: 2 }
        """.trimIndent()

        // Start workflow
        val instanceId = startWorkflowAsync(yaml)

        // Verify fork state saved
        val fork = forkRepository.findByInstance(instanceId).await()
        assertNotNull(fork)
        assertEquals(2, fork.branchCount)
        assertEquals(0, fork.completedCount)

        // Verify branch messages emitted
        val branchMessages = consumeMessages(2)
        assertEquals(2, branchMessages.size)

        // Simulate branch completions
        branchMessages.forEach { processMessage(it) }

        // Verify fork completed
        val completedFork = forkRepository.findByInstance(instanceId).await()
        assertNull(completedFork)  // Should be deleted

        // Verify parent resumed
        val output = getWorkflowOutput(instanceId)
        assertTrue(output is JsonArray)
        assertEquals(2, (output as JsonArray).size)
    }

    test("fork compete mode completes on first branch") {
        val yaml = """
            do:
              - raceFork:
                  fork:
                    compete: true
                    branches:
                      - fast:
                          set: { speed: "fast" }
                      - slow:
                          wait: PT10S
                          then:
                            set: { speed: "slow" }
        """.trimIndent()

        val instanceId = startWorkflowAsync(yaml)

        // Process first branch (fast)
        val fastMessage = consumeMessage()
        processMessage(fastMessage)

        // Verify fork completed after first branch
        val fork = forkRepository.findByInstance(instanceId).await()
        assertNull(fork)  // Should be deleted

        // Verify output is from fast branch
        val output = getWorkflowOutput(instanceId)
        assertEquals("fast", output.jsonObject["speed"]?.jsonPrimitive?.content)
    }

    test("fork cooperative mode waits for all branches") {
        val yaml = """
            do:
              - cooperativeFork:
                  fork:
                    compete: false
                    branches:
                      - branch1:
                          set: { order: 1 }
                      - branch2:
                          set: { order: 2 }
                      - branch3:
                          set: { order: 3 }
        """.trimIndent()

        val instanceId = startWorkflowAsync(yaml)

        // Complete branch 1
        processMessage(consumeMessage())
        var fork = forkRepository.findByInstance(instanceId).await()
        assertNotNull(fork)
        assertEquals(1, fork.completedCount)

        // Complete branch 2
        processMessage(consumeMessage())
        fork = forkRepository.findByInstance(instanceId).await()
        assertNotNull(fork)
        assertEquals(2, fork.completedCount)

        // Complete branch 3
        processMessage(consumeMessage())
        fork = forkRepository.findByInstance(instanceId).await()
        assertNull(fork)  // Now deleted

        // Verify all outputs assembled
        val output = getWorkflowOutput(instanceId) as JsonArray
        assertEquals(3, output.size)
        assertEquals(1, output[0].jsonObject["order"]?.jsonPrimitive?.int)
        assertEquals(2, output[1].jsonObject["order"]?.jsonPrimitive?.int)
        assertEquals(3, output[2].jsonObject["order"]?.jsonPrimitive?.int)
    }

    test("fork branch failure in cooperative mode fails entire fork") {
        val yaml = """
            do:
              - forkWithFailure:
                  try:
                    fork:
                      branches:
                        - good:
                            set: { status: "ok" }
                        - bad:
                            raise:
                              error:
                                type: TestError
                                title: "Branch failed"
                  catch:
                    do:
                      - handleError:
                          set: { handled: true }
        """.trimIndent()

        val instanceId = startWorkflowAsync(yaml)

        // Process good branch
        processMessage(consumeMessage())

        // Process bad branch (should fail)
        processMessage(consumeMessage())

        // Verify fork state deleted
        val fork = forkRepository.findByInstance(instanceId).await()
        assertNull(fork)

        // Verify error was caught
        val output = getWorkflowOutput(instanceId)
        assertEquals(true, output.jsonObject["handled"]?.jsonPrimitive?.boolean)
    }
})
```

---

## Implementation Checklist

### Phase 1-4: Core Implementation ✅ COMPLETED

- [x] ForkException in WorkflowException.kt
- [x] WorkflowState.RunningFork in WorkflowState.kt
- [x] ForkTaskState.kt
- [x] ForkProcessor.kt
- [x] Orchestrator integration (processForkException)
- [x] Parallel execution with coroutines (compete & cooperative)
- [x] Branch execution logic (executeForkBranches)
- [x] Output assembly and transformation
- [x] Context export support
- [x] 10 comprehensive tests
- [x] Refactoring to eliminate duplication

### Phase 5: Runner Integration (Async Mode) ❌ NOT STARTED

#### Database Layer
- [ ] Database migrations for PostgreSQL, MySQL, H2 (2 tables: `lemline_forks`, `lemline_fork_branches`)
- [ ] `ForkModel` and `ForkBranchModel` data classes
- [ ] `ForkRepository` interface with suspend functions
- [ ] `ForkRepositoryImpl` with pessimistic locking (FOR UPDATE)
- [ ] Database-agnostic SQL (no FILTER, no OF clause)

#### Messaging Layer
- [ ] `DatabaseMessage.ForkStarted` message type
- [ ] `StepByStepRunner.onForkStarted()` - emit fork to database channel
- [ ] `DatabaseMessageHandler.handleForkStarted()` - persist fork & schedule branches
- [ ] `InstanceMessageHandler.isBranchCompletion()` - detect branch completion
- [ ] `InstanceMessageHandler.handleBranchCompletion()` - update branch, check fork completion
- [ ] `InstanceMessageHandler.resumeForkParent()` - assemble output & resume parent

#### Error Handling
- [ ] Branch failure handling (compete vs cooperative modes)
- [ ] Fork cleanup scheduler (orphaned fork detection)
- [ ] Lock timeout handling
- [ ] Retry logic for database errors

#### Testing
- [ ] Integration tests for async fork execution (PostgreSQL)
- [ ] Integration tests for MySQL compatibility
- [ ] Compete mode tests (first wins)
- [ ] Cooperative mode tests (wait all)
- [ ] Branch failure tests
- [ ] Concurrency tests (multiple workers)

#### Monitoring & Documentation
- [ ] Lock wait time metrics
- [ ] Fork completion time metrics
- [ ] Branch count distribution metrics
- [ ] Documentation updates

---

## Success Criteria

### Core Implementation (Phase 1-4) ✅ ACHIEVED

- ✅ All unit tests pass (10/10)
- ✅ Full lemline-core test suite passes
- ✅ Both compete and cooperative modes work correctly
- ✅ True parallelism in Complete mode verified
- ✅ Output transformation and context export working
- ✅ Error handling through Try/Catch works
- ✅ Code refactored to eliminate duplication
- ✅ No regressions in existing tests

### Runner Integration (Phase 5) - TO BE ACHIEVED

- [ ] Fork state persists correctly to database
- [ ] Branches execute independently on different workers
- [ ] Branch completion detected and tracked correctly
- [ ] Fork completes and parent resumes correctly
- [ ] Compete mode completes on first branch
- [ ] Cooperative mode waits for all branches
- [ ] Branch failures handled appropriately
- [ ] Stale fork cleanup works
- [ ] All async integration tests pass
- [ ] No performance degradation

---

## Performance Considerations

### Complete Mode (Current Implementation)

**Advantages**:
- ✅ True parallelism with Kotlin coroutines
- ✅ Minimal overhead (no database roundtrips)
- ✅ Fast execution for CPU-bound tasks
- ✅ Compete mode returns immediately on first completion

**Limitations**:
- ⚠️ All branches execute in same process (memory bound)
- ⚠️ No fault tolerance (process crash loses all branches)
- ⚠️ Limited to single-machine resources

### Async Mode (Future Implementation)

**Advantages**:
- ✅ Distributed execution across workers
- ✅ Fault tolerant (branches can retry independently)
- ✅ Scalable (add more workers for more parallelism)
- ✅ Each branch can pause/resume (waits, child workflows)

**Trade-offs**:
- ⚠️ Database coordination overhead
- ⚠️ Latency from message passing
- ⚠️ More complex error handling

### Recommendations

**Use Complete Mode When**:
- Low branch count (< 10)
- Fast-executing branches (< 1 second each)
- CPU-bound operations
- Single deployment environment

**Use Async Mode When**:
- High branch count (> 10)
- Long-running branches (> 1 second)
- I/O-bound operations (HTTP calls, waits)
- Distributed deployment
- Need fault tolerance

---

## Documentation References

### Specification
- Serverless Workflow DSL: https://github.com/serverlessworkflow/specification/blob/main/dsl.md
- Fork Task Reference: https://github.com/serverlessworkflow/specification/blob/main/dsl-reference.md#fork

### Implemented Files (Phase 1-4)

**Core**:
- `WorkflowException.kt` - ForkException
- `WorkflowState.kt` - RunningFork state
- `ForkTaskState.kt` - Runtime state
- `ForkProcessor.kt` - Processor logic
- `WorkflowOrchestrator.kt` - Orchestration integration

**Tests**:
- `ForkTaskExecutionTest.kt` - Base test suite (10 tests)
- `ContinuousForkTaskExecutionTest.kt` - Continuous mode wrapper

### Files to Create (Phase 5)

**Database**:
- `db/migration/postgresql/V006__create_lemline_forks.sql`
- `db/migration/mysql/V006__create_lemline_forks.sql`
- `db/migration/h2/V006__create_lemline_forks.sql`

**Models**:
- `repositories/fork/ForkModel.kt`
- `repositories/fork/ForkStatus.kt`

**Repository**:
- `repositories/fork/ForkRepository.kt`
- `repositories/fork/ForkRepositoryImpl.kt`

**Handlers**:
- Updates to `StepByStepRunner.kt`
- Updates to `DatabaseMessageHandler.kt`
- Updates to `InstanceMessageHandler.kt`
- Updates to `DatabaseMessage.kt`

**Tests**:
- `tests/ForkAsyncTest.kt`

---

---

## Final Decisions Summary

### Architecture

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Schema** | Multiple Rows (2 tables) | 3-4x better throughput, better observability, more DB-agnostic |
| **Locking** | Pessimistic (FOR UPDATE) | Simple, correct, good for expected load |
| **SQL** | Database-Agnostic | Works on PostgreSQL, MySQL, H2 |
| **Migrations** | Separate per DB | Handle schema differences (UUID vs BINARY, etc.) |
| **API Style** | Suspend Functions | Native Kotlin coroutines, not Uni<T> |
| **DB-Specific Code** | Optional, avoid | Start generic, optimize if profiling shows benefit |

### Key Implementation Details

**Database Schema**:
- `lemline_forks`: Fork metadata (1 row per fork)
- `lemline_fork_branches`: Branch execution state (1 row per branch)
- Foreign key CASCADE for cleanup
- Indexes on `(instance_id, fork_position, status)`

**Concurrency Control**:
- Branch update: No lock (different rows)
- Completion check: `FOR UPDATE` on fork row
- Lock hold time: ~5ms (4x faster than single-row approach)
- No retry logic needed (pessimistic lock guarantees correctness)

**SQL Compatibility**:
- ✅ Use `FOR UPDATE` (no `OF` clause)
- ✅ Use `COUNT(CASE WHEN ...)` (not `FILTER`)
- ✅ Use `CURRENT_TIMESTAMP` (not `NOW()`)
- ✅ Explicit `GROUP BY` all non-aggregated columns
- ❌ Avoid PostgreSQL-specific features in repository code

**Performance**:
- 3 branches completing concurrently:
  - Single row: 60ms total (serialized)
  - Multiple rows: 20ms total (3x faster)
- Lock contention: Minimal (only on fork metadata row, not branch data)

### Migration Path

If future profiling shows performance issues:
1. Add DB-specific code path detection (`dbKind`)
2. Implement PostgreSQL-optimized version (`FILTER`, `FOR UPDATE OF`)
3. A/B test both implementations
4. Roll out based on metrics

### Related Documents

- **FORK_IMPLEMENTATION_COMPARISON.md**: Single row vs multiple rows analysis
- **FORK_CONCURRENCY_ANALYSIS.md**: Pessimistic vs optimistic locking analysis
- **FORK_RECOMMENDATION_REVISION.md**: Final recommendation with rationale

---

**Document Version**: 4.0
**Created**: 2025-01-16
**Last Updated**: 2025-11-16
**Status**: Phase 1-4 Complete, Phase 5 Ready to Implement
**Commits**: `2669a27` (core implementation), `c3a3c25` (refactoring)
**Branch**: `feature/fork-task-implementation`
**Final Decisions**: Multiple Rows + Pessimistic Locking + Database-Agnostic SQL + Suspend Functions
