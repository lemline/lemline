# WorkflowStep Implementation Plan

Implementation plan for ADR-0009: Dynamic JSON Pointer for Step Indexing

## Overview

Implement WorkflowStep to create unique identifiers for workflow execution steps by extending NodePosition with visit
counts. This enables unique database primary keys for outbox tables (waits, retries, parents, forks) and prevents ID
collisions when the same node is visited multiple times (loops, retries, parallel branches).

**Problem Being Solved:** Using `(workflowId, NodePosition)` for database primary keys causes collisions when the same
node is executed multiple times. WorkflowStep includes visit counts to make each execution unique.

**Format Decision:** WorkflowStep uses comma-separated name,count pairs (e.g., `/for,5/do,3/task,2`) instead of
alternating segments. This format is more explicit, easier to parse, and clearer to read.

## Implementation Phases

### Phase 0: Simplify NodePosition (lemline-core)

**Goal:** Refactor NodePosition to be simpler and more consistent, creating a clean foundation for WorkflowStep.

#### Current Issues

**NodePosition complexity:**

- Stores path as `List<String>` internally
- Lazily creates `PositionPointer` (value class with string) for string representation
- Two classes doing similar things (NodePosition and PositionPointer)
- Unnecessary conversions between List and String

**Current structure:**

```kotlin
// NodePosition: Stores List<String>
data class NodePosition(private val path: List<String> = listOf()) {
    val positionPointer by lazy { PositionPointer("/${path.joinToString("/")}") }
}

// PositionPointer: Stores String
@JvmInline
value class PositionPointer(private val path: String) {
    fun toPosition() = NodePosition(path.split("/").filter { it.isNotEmpty() })
}
```

This creates unnecessary back-and-forth conversion.

#### 0.1 Simplify NodePosition

**File:** `lemline-core/src/main/kotlin/com/lemline/core/nodes/NodePosition.kt`

**Refactored design:**

```kotlin
/**
 * Represents a static position in the workflow definition tree.
 *
 * Uses JSON Pointer notation (RFC 6901) to identify nodes.
 * Examples: "/do/taskA", "/for/do/processItem", "/try/failing"
 *
 * This is the STATIC position in the definition tree, without execution context.
 * For dynamic execution tracking with visit counts, see WorkflowStep.
 */
@Serializable(with = NodePositionSerializer::class)
data class NodePosition(private val path: String) {

    init {
        require(path.isEmpty() || path.startsWith("/")) {
            "NodePosition must start with '/' or be empty for root"
        }
    }

    /**
     * Get the node name (last segment of path).
     * Example: "/do/taskA" → "taskA"
     */
    val nodeName: String
        get() = if (path.isEmpty()) "" else path.substringAfterLast('/')

    /**
     * Get parent position by removing last segment.
     * Example: "/do/taskA" → "/do"
     */
    val parent: NodePosition?
        get() = when {
            path.isEmpty() -> null
            !path.contains('/') -> root
            path.lastIndexOf('/') == 0 -> root
            else -> NodePosition(path.substringBeforeLast('/'))
        }

    /**
     * Add a name component to the path.
     * Example: "/do" + "taskA" → "/do/taskA"
     */
    fun addName(name: String): NodePosition {
        require(!name.contains("/")) { "Task name $name must not contain '/'" }
        require(name.toIntOrNull() == null) { "Task name $name must not be an integer" }
        Token.entries.map { it.token }.let {
            require(!it.contains(name)) { "Task name $name must not be one of ${it.joinToString()}" }
        }
        return NodePosition(if (path.isEmpty()) "/$name" else "$path/$name")
    }

    /**
     * Add a token to the path.
     * Example: "/do" + Token.TRY → "/do/try"
     */
    fun addToken(token: Token): NodePosition =
        NodePosition(if (path.isEmpty()) "/${token.token}" else "$path/${token.token}")

    /**
     * Add an index to the path.
     * Example: "/do" + 0 → "/do/0"
     */
    fun addIndex(index: Int): NodePosition =
        NodePosition(if (path.isEmpty()) "/$index" else "$path/$index")

    /**
     * Get the segments of the path.
     * Example: "/do/taskA" → ["do", "taskA"]
     */
    fun segments(): List<String> =
        if (path.isEmpty()) emptyList()
        else path.substring(1).split('/')

    /**
     * Check if this is a container node (has potential children).
     */
    fun isContainer(): Boolean {
        val name = nodeName
        return Token.entries.any { it.token == name }
    }

    override fun toString(): String = if (path.isEmpty()) "/" else path

    companion object {
        val root = NodePosition("")

        fun parse(path: String): NodePosition {
            val normalized = path.trim()
            return when {
                normalized.isEmpty() || normalized == "/" -> root
                else -> NodePosition(normalized)
            }
        }

        fun fromJsonString(jsonString: String): NodePosition =
            LemlineJson.decodeFromString(jsonString)
    }
}

internal object NodePositionSerializer : KSerializer<NodePosition> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NodePosition", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: NodePosition) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): NodePosition {
        return NodePosition.parse(decoder.decodeString())
    }
}
```

**Key improvements:**

- ✅ Stores path as `String` internally (simpler, matches WorkflowStep design)
- ✅ No more PositionPointer indirection
- ✅ Direct string operations (faster, clearer)
- ✅ Added `nodeName` property (useful for WorkflowStep builder)
- ✅ Added `isContainer()` method (useful for WorkflowStep builder)
- ✅ Added `segments()` method (useful for traversal)
- ✅ Clearer documentation distinguishing static vs dynamic positions

#### 0.2 Remove PositionPointer

**File:** `lemline-core/src/main/kotlin/com/lemline/core/nodes/PositionPointer.kt`

**Action:** Delete this file - functionality merged into NodePosition.

**Migration:**

```kotlin
// Before (with PositionPointer)
val pointer = position.positionPointer
val str = pointer.toString()
val pos = PositionPointer("/do/taskA").toPosition()

// After (simplified)
val str = position.toString()
val pos = NodePosition.parse("/do/taskA")
```

#### 0.3 Update All References

**Files to update:**

- Search for all uses of `PositionPointer` and replace with `NodePosition`
- Update `.positionPointer` property accesses to just use the NodePosition directly
- Update `PositionPointer(...)` constructor calls to `NodePosition.parse(...)`

**Common patterns:**

```kotlin
// Before
val pointer = position.positionPointer.toString()

// After
val pointer = position.toString()
```

#### 0.4 Update Tests

**Files to update:**

- `NodePositionTest.kt` - Update tests for new string-based implementation
- Remove `PositionPointerTest.kt` if it exists
- Verify all existing tests still pass

**New tests to add:**

```kotlin
@Test
fun `nodeName returns last segment`() {
    assertEquals("taskA", NodePosition.parse("/do/taskA").nodeName)
    assertEquals("do", NodePosition.parse("/do").nodeName)
    assertEquals("", NodePosition.root.nodeName)
}

@Test
fun `segments returns path components`() {
    assertEquals(listOf("do", "taskA"), NodePosition.parse("/do/taskA").segments())
    assertEquals(emptyList(), NodePosition.root.segments())
}

@Test
fun `isContainer identifies container nodes`() {
    assertTrue(NodePosition.parse("/do").isContainer())
    assertTrue(NodePosition.parse("/try").isContainer())
    assertFalse(NodePosition.parse("/taskA").isContainer())
}

@Test
fun `string operations work correctly`() {
    val pos = NodePosition.parse("/do")
    assertEquals("/do/taskA", pos.addName("taskA").toString())
    assertEquals("/do/0", pos.addIndex(0).toString())
    assertEquals("/do/try", pos.addToken(Token.TRY).toString())
}
```

#### 0.5 Verify Serialization

Ensure backwards compatibility with existing serialized NodePositions:

```kotlin
@Test
fun `serialization is backwards compatible`() {
    // Old format (with List<String> internally) should deserialize to new format
    val json = """"/do/taskA""""
    val position = Json.decodeFromString<NodePosition>(json)
    assertEquals("/do/taskA", position.toString())
}

@Test
fun `round trip serialization works`() {
    val original = NodePosition.parse("/do/taskA")
    val json = Json.encodeToString(original)
    val deserialized = Json.decodeFromString<NodePosition>(json)
    assertEquals(original, deserialized)
}
```

---

### Phase 1: Core Data Structures (lemline-common)

#### 1.1 Create WorkflowStep Class

**File:** `lemline-common/src/main/kotlin/com/lemline/common/WorkflowStep.kt`

```kotlin
package com.lemline.common

import kotlinx.serialization.Serializable

/**
 * Dynamic workflow step identifier that includes visit counts.
 *
 * Extends static NodePosition with visit counts to uniquely identify each
 * execution instance, even when the same node is visited multiple times
 * (loops, retries, parallel branches).
 *
 * Format: /{nodeName},{visitCount}/{nodeName},{visitCount}/...
 * Example: /do,0/taskA,0 or /for,2/do,1/processItem,0
 *
 * The comma-separated format makes it explicit which count belongs to which node,
 * simplifies parsing, and improves readability.
 */
@Serializable(with = WorkflowStepSerializer::class)
data class WorkflowStep(private val path: String) {

    init {
        require(path.startsWith("/")) {
            "WorkflowStep path must start with '/', got: '$path'"
        }
        // Validate format: each segment should be name,count
        val segments = path.substring(1).split("/")
        require(segments.isNotEmpty()) {
            "WorkflowStep must have at least one segment"
        }
        segments.forEach { segment ->
            val parts = segment.split(",")
            require(parts.size == 2) {
                "Each segment must be in format 'name,count', got: '$segment'"
            }
            val (name, count) = parts
            require(name.isNotEmpty()) { "Name must not be empty" }
            require(count.all { it.isDigit() }) { "Visit count must be numeric" }
        }
    }

    /**
     * Convert to static NodePosition by removing visit counts.
     *
     * Example: "/do,0/taskA,0" → "/do/taskA"
     */
    fun toNodePosition(): NodePosition {
        val segments = path.substring(1).split("/")
        val nameSegments = segments.map { it.substringBefore(',') }
        return NodePosition.parse("/" + nameSegments.joinToString("/"))
    }

    /**
     * Get compact string representation for ID generation.
     */
    fun toJsonString(): String = path

    override fun toString(): String = path

    companion object {
        /**
         * Deserialize from JSON string.
         */
        fun fromJsonString(jsonString: String): WorkflowStep =
            LemlineJson.decodeFromString(jsonString)
    }
}
```

**Tests:** `lemline-common/src/test/kotlin/com/lemline/common/WorkflowStepTest.kt`

- Test parsing valid paths
- Test toStaticPosition() conversion
- Test invalid paths (no leading slash, etc.)
- Test round-trip: NodePosition → WorkflowStep → NodePosition

---

### Phase 2: Add Visit Counts to TaskState (lemline-core)

a#### 2.1 Update TaskState Base Class

**File:** `lemline-core/src/main/kotlin/com/lemline/core/state/TaskState.kt`

Add `visitCount` property to base TaskState:

```kotlin
@Serializable
sealed class TaskState {
    abstract val visitCount: Int
    // ... existing properties
}
```

#### 2.2 Update All TaskState Implementations

Update each TaskState implementation to include visitCount:

**Files to update:**

- `DoState` - Sequential do blocks
- `ForState` - Foreach loops
- `TryState` - Try/catch/retry blocks
- `ForkState` - Parallel branches
- `SwitchState` - Switch cases
- All task-specific states (SetState, CallState, WaitState, etc.)

**Example:**

```kotlin
@Serializable
data class DoState(
    override val visitCount: Int = 0,
    val index: Int = 0,
    // ... other fields
) : TaskState()
```

**Migration Note:** Existing serialized states without visitCount will need default value (0).

#### 2.3 Update State Creation Logic

**File:** `lemline-core/src/main/kotlin/com/lemline/core/processor/Processor.kt`

Update navigation logic to manage visitCount:

```kotlin
// When entering FROM parent (going down)
private fun enterFromParent(node: Node<*>): TaskState {
    // Create new state with visitCount = 0
    return node.createState(visitCount = 0)
}

// When entering FROM child (going up)
private fun enterFromChild(parentPosition: NodePosition): TaskState {
    val currentState = taskStates[parentPosition]

    // Delete child state (cleanup)
    val childPosition = currentPosition
    taskStates.remove(childPosition)

    // Increment parent's visitCount
    val newVisitCount = (currentState?.visitCount ?: 0) + 1
    return currentState.copy(visitCount = newVisitCount)
}
```

**Tests:** Update existing processor tests to verify visitCount increments correctly

- Test sequential tasks (do block navigation)
- Test foreach loops (multiple iterations)
- Test try/retry (retry increments visitCount)
- Test nested structures

---

### Phase 3: Build WorkflowStep from State (lemline-core)

#### 3.1 Create WorkflowStep Builder

**File:** `lemline-core/src/main/kotlin/com/lemline/core/state/WorkflowStepBuilder.kt`

```kotlin
package com.lemline.core.state

import com.lemline.common.NodePosition
import com.lemline.common.WorkflowStep

/**
 * Builds a dynamic WorkflowStep by walking up the node tree from current position,
 * collecting node names and visit counts from the state map.
 */
object WorkflowStepBuilder {

    fun buildWorkflowStep(
        currentPosition: NodePosition,
        taskStates: Map<NodePosition, TaskState>
    ): WorkflowStep {
        val segments = mutableListOf<String>()

        // Walk up from current position to root
        var pos: NodePosition? = currentPosition
        while (pos != null && pos != NodePosition.root) {
            // Look up state using STATIC position key
            val state = taskStates[pos]
            val visitCount = state?.visitCount ?: 0

            // Add name,count pair as single segment
            segments.add(0, "${pos.nodeName},$visitCount")

            pos = pos.parent
        }

        return WorkflowStep("/" + segments.joinToString("/"))
    }
}
```

**Tests:** `WorkflowStepBuilderTest.kt`

- Test building from simple position: `/do/taskA` → `/do,0/taskA,0`
- Test building from nested position: `/do/taskA/try/failing` → `/do,0/taskA,0/try,0/failing,0`
- Test building with non-zero visit counts
- Test with foreach iterations
- Test with retry attempts

---

#### 3.2 Add Computed Property to WorkflowState

**File:** `lemline-core/src/main/kotlin/com/lemline/core/state/WorkflowState.kt`

```kotlin
@Serializable
sealed class WorkflowState {
    abstract val taskStates: Map<NodePosition, TaskState>
    abstract val nodePosition: NodePosition

    val workflowId: IDV7
        get() = (taskStates[NodePosition.root] as RootState).workflowId

    /**
     * Dynamic workflow step including visit counts.
     * Computed on-demand from current position and task states.
     * Used for generating unique database IDs.
     */
    val workflowStep: WorkflowStep by lazy {
        WorkflowStepBuilder.buildWorkflowStep(nodePosition, taskStates)
    }
}
```

**Tests:** Update WorkflowState tests

- Test workflowStep is computed correctly
- Test lazy initialization (computed once)
- Test workflowStep changes as workflow progresses

---

### Phase 4: Update ID Generation (lemline-runner)

#### 4.1 Update IDV7 Namespace Generation

**File:** `lemline-common/src/main/kotlin/com/lemline/common/IDV7.kt`

Ensure IDV7.fromNamespace() method exists (or create it):

```kotlin
companion object {
    /**
     * Generate deterministic UUID v5 from namespace and name.
     * Same namespace + name always produces same UUID.
     */
    fun fromNamespace(namespace: IDV7, name: String): IDV7 {
        // Use UUID v5 (SHA-1 based) for deterministic generation
        val bytes = (namespace.toString() + name).toByteArray()
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes)
        // Convert to UUID v5 format
        // ... implementation
    }
}
```

#### 4.2 Update Outbox Model Creation

Update all outbox model creation to use workflowStep for ID generation:

**Files to update:**

- `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/wait/WaitOutboxModel.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/retry/RetryOutboxModel.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/parent/ParentOutboxModel.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/fork/ForkOutboxModel.kt`

**Example for WaitOutboxModel:**

```kotlin
data class WaitOutboxModel(
    val id: IDV7,
    val workflowId: IDV7,
    val workflowNamespace: String,
    val workflowName: String,
    val workflowVersion: String,
    val workflowPosition: String,  // Static position for queries
    val workflowState: String,
    // ... other fields
) {
    companion object {
        fun create(
            workflowState: WorkflowState,
            delay: Duration,
            // ... other params
        ): WaitOutboxModel {
            // Generate ID using WorkflowStep (includes visit counts)
            val id = IDV7.fromNamespace(
                namespace = workflowState.workflowId,
                name = workflowState.workflowStep.toCompactString()  // ✅ Uses visit counts!
            )

            return WaitOutboxModel(
                id = id,
                workflowId = workflowState.workflowId,
                workflowPosition = workflowState.nodePosition.toCompactString(),  // Static
                workflowState = workflowState.serialize(),
                // ...
            )
        }
    }
}
```

**Key Changes:**

- ID generation uses `workflowState.workflowStep` (with visit counts)
- `workflowPosition` column stores static position (for queries)
- Each iteration/retry gets unique ID

#### 4.3 Update StepByStepRunner

**File:** `lemline-runner/src/main/kotlin/com/lemline/runner/StepByStepRunner.kt`

Update exception handlers to use new model creation:

```kotlin
catch(e: WaitStartedException) {
    val waitModel = WaitOutboxModel.create(
        workflowState = processor.getWorkflowState(),
        delay = e.delay,
        // ...
    )
    sendToEventsOut(waitModel)
}

catch(e: TaskRetriedException) {
    val retryModel = RetryOutboxModel.create(
        workflowState = processor.getWorkflowState(),
        error = e.error,
        // ...
    )
    sendToEventsOut(retryModel)
}

// Similar for RunWorkflowStartedException (ParentOutboxModel)
```

---

### Phase 5: Database Schema (No Changes Needed)

**Current schema is already compatible:**

```sql
CREATE TABLE lemline_waits
(
    id                UUID PRIMARY KEY, -- ✅ Will now be based on workflowStep
    workflow_position TEXT NOT NULL,    -- ✅ Stores static position (for queries)
    workflow_state    TEXT NOT NULL,    -- ✅ Contains visit counts in taskStates
    -- ...
);
```

**Decision from discussion:**

- Keep `workflow_position` for query convenience (static position)
- Keep `workflow_namespace`, `workflow_name`, `workflow_version` (denormalized for performance)
- ID is now generated from `(workflowId, workflowStep)` with visit counts
- No schema migration needed!

**Verification queries to add:**

```sql
-- Query all waits for a specific task (across iterations)
SELECT *
FROM lemline_waits
WHERE workflow_id = ?
  AND workflow_position = '/for/do/wait';

-- Each iteration has unique ID (no collisions)
-- This now works because ID includes visit counts
```

---

### Phase 6: Testing

#### 6.1 Unit Tests (lemline-core)

**Test WorkflowStep Building:**

- `WorkflowStepTest.kt` - Basic WorkflowStep operations
- `WorkflowStepBuilderTest.kt` - Building from state
- Update existing Processor tests to verify visitCount behavior

**Test Cases:**

```kotlin
@Test
fun `sequential tasks increment visitCount correctly`() = runTest {
        val yaml = """
        do:
          - taskA: { set: { x: 1 } }
          - taskB: { set: { y: 2 } }
    """.trimIndent()

        val processor = getWorkflowProcessor(yaml)

        // Step 1: Enter do
        processor.run()
        assertEquals("/do,0", processor.state.workflowStep.toJsonString())

        // Step 2: Enter taskA
        processor.run()
        assertEquals("/do,0/taskA,0", processor.state.workflowStep.toJsonString())

        // Step 3: Complete taskA, go up to do
        processor.run()
        assertEquals("/do,1", processor.state.workflowStep.toJsonString())

        // Step 4: Enter taskB
        processor.run()
        assertEquals("/do,1/taskB,0", processor.state.workflowStep.toJsonString())
    }

@Test
fun `foreach loop creates unique workflowStep per iteration`() = runTest {
    val yaml = """
        for:
          in: [1, 2, 3]
          do:
            - task: { set: { x: . } }
    """.trimIndent()

    val processor = getWorkflowProcessor(yaml)
    val steps = mutableListOf<String>()

    while (processor.state is RunningState) {
        processor.run()
        steps.add(processor.state.workflowStep.toCompactString())
    }

    // Verify each iteration has unique workflowStep
    assertTrue(steps.contains("/for,0/do,0/task,0"))  // Iteration 0
    assertTrue(steps.contains("/for,1/do,0/task,0"))  // Iteration 1
    assertTrue(steps.contains("/for,2/do,0/task,0"))  // Iteration 2
}

@Test
fun `retry increments visitCount for try and task`() = runTest {
    val yaml = """
        try:
          - failing: { call: { http: "https://fail.com" } }
        catch:
          errors: { type: "*" }
          retry:
            maxAttempts: 3
    """.trimIndent()

    // Track workflowSteps across retries
    // First attempt: /try,0/failing,0
    // Retry 1: /try,2/failing,1 (try visitCount = 2, task visitCount = 1)
    // Verify both increment
}
```

#### 6.2 Integration Tests (lemline-runner)

**Test Outbox ID Generation:**

```kotlin
@QuarkusTest
@TestProfile(PostgresProfile::class)
class WorkflowStepIntegrationTest : FunSpec({

    @Inject
    lateinit var waitRepository: WaitRepository

    @Inject
    lateinit var runner: StepByStepRunner

    test("wait tasks in loop create unique database rows") {
        val yaml = """
            for:
              in: [1, 2, 3]
              do:
                - wait: PT1M
        """.trimIndent()

        // Run workflow
        runner.executeWorkflow(yaml)

        // Verify 3 separate wait rows created (no collision)
        val waits = waitRepository.findByWorkflowId(workflowId)
        assertEquals(3, waits.size)

        // Verify each has unique ID
        val ids = waits.map { it.id }.toSet()
        assertEquals(3, ids.size, "All IDs should be unique")

        // Verify all have same workflow_position (static)
        waits.forEach { wait ->
            assertEquals("/for/do/wait", wait.workflowPosition)
        }

        // Verify IDs are deterministic (same execution → same ID)
        val firstId = waits.first().id
        val regeneratedId = IDV7.fromNamespace(
            namespace = workflowId,
            name = "/for,0/do,0/wait,0"
        )
        assertEquals(firstId, regeneratedId)
    }

    test("retry tasks create unique database rows per attempt") {
        val yaml = """
            try:
              - failing: { call: { http: "https://fail.com" } }
            catch:
              retry:
                maxAttempts: 3
        """.trimIndent()

        // Run workflow (will fail and retry)
        runner.executeWorkflow(yaml)

        // Verify multiple retry rows created
        val retries = retryRepository.findByWorkflowId(workflowId)
        assertTrue(retries.size >= 2, "Should have multiple retry attempts")

        // Verify unique IDs
        val ids = retries.map { it.id }.toSet()
        assertEquals(retries.size, ids.size, "All retry IDs should be unique")
    }
})
```

#### 6.3 Database Tests

**Test No Collisions:**

```kotlin
test("no primary key violations in concurrent execution") {
    // Create multiple workflows with same definition
    val workflows = (1..10).map {
        startWorkflow(yaml) // Same YAML with loop
    }

    // Let all execute concurrently
    workflows.forEach { it.waitForCompletion() }

    // Verify no database errors occurred
    // If visit counts weren't included, we'd get PK violations
}
```

---

### Phase 7: Documentation Updates

#### 7.1 Update CLAUDE.md

Add WorkflowStep to architecture section:

```markdown
### Core Architecture Patterns

#### Workflow Step Identification

- **NodePosition**: Static position in workflow definition tree (e.g., `/do/taskA`)
- **WorkflowStep**: Dynamic execution identifier with visit counts (e.g., `/do,0/taskA,0`)
    - Uses comma-separated name,count pairs for clarity
    - Used for generating unique database IDs
    - Prevents collisions when same node visited multiple times (loops, retries)
    - Computed from `(nodePosition, taskStates)` on-demand
```

#### 7.2 Update Runner Documentation

Document ID generation pattern:

```markdown
### Database ID Generation

Outbox tables (waits, retries, parents, forks) use deterministic UUIDs:

```kotlin
val id = IDV7.fromNamespace(
    namespace = workflowId,
    name = workflowStep.toCompactString()  // Includes visit counts!
)
```

This ensures:

- Unique IDs for each execution (loop iterations, retry attempts)
- Deterministic IDs (same execution path → same ID)
- Idempotent database operations (redelivered messages use same ID)

```

#### 7.3 Add Examples to Developer Guide

Create examples showing workflowStep evolution:
- Sequential tasks
- Foreach loops
- Try/retry
- Parallel branches

---

## Implementation Checklist

### Phase 0: Simplify NodePosition
- [ ] Refactor NodePosition to store path as String (not List<String>)
- [ ] Add `nodeName` property to NodePosition
- [ ] Add `isContainer()` method to NodePosition
- [ ] Add `segments()` method to NodePosition
- [ ] Update parent property logic for string-based path
- [ ] Update addName/addToken/addIndex methods for string operations
- [ ] Delete PositionPointer.kt file
- [ ] Search and replace all PositionPointer references with NodePosition
- [ ] Update all `.positionPointer` property accesses
- [ ] Update NodePositionTest.kt for new implementation
- [ ] Add tests for new methods (nodeName, segments, isContainer)
- [ ] Verify serialization backwards compatibility
- [ ] Run full lemline-core test suite
- [ ] Ensure no regressions in existing functionality

### Phase 1: Core Data Structures
- [ ] Create `WorkflowStep` class in lemline-common
- [ ] Add tests for WorkflowStep parsing and conversion
- [ ] Verify serialization works correctly

### Phase 2: TaskState Visit Counts
- [ ] Add `visitCount` property to TaskState base class
- [ ] Update all TaskState implementations (Do, For, Try, etc.)
- [ ] Update state creation logic in Processor
- [ ] Add/update tests for visitCount increment logic
- [ ] Test backwards compatibility with existing serialized states

### Phase 3: WorkflowStep Builder
- [ ] Create `WorkflowStepBuilder` in lemline-core
- [ ] Add tests for building workflowStep from various positions
- [ ] Add `workflowStep` computed property to WorkflowState
- [ ] Test workflowStep computation in processor tests

### Phase 4: ID Generation
- [ ] Verify/create `IDV7.fromNamespace()` method
- [ ] Update `WaitOutboxModel.create()` to use workflowStep
- [ ] Update `RetryOutboxModel.create()` to use workflowStep
- [ ] Update `ParentOutboxModel.create()` to use workflowStep
- [ ] Update `ForkOutboxModel.create()` to use workflowStep
- [ ] Update `StepByStepRunner` exception handlers
- [ ] Add unit tests for model creation with correct IDs

### Phase 5: Integration Testing
- [ ] Add integration test for wait tasks in loop (no collisions)
- [ ] Add integration test for retry tasks (unique per attempt)
- [ ] Add integration test for fork tasks
- [ ] Add integration test for parent/child workflows
- [ ] Test deterministic ID generation (same path → same ID)
- [ ] Test concurrent execution (no PK violations)

### Phase 6: Documentation
- [ ] Update CLAUDE.md with WorkflowStep architecture
- [ ] Update runner developer guide with ID generation patterns
- [ ] Add examples to developer documentation
- [ ] Update API docs for new classes

### Phase 7: Final Verification
- [ ] Run full test suite: `./gradlew test`
- [ ] Test with PostgreSQL: `./gradlew test -Dtest.profile=postgres`
- [ ] Test with MySQL: `./gradlew test -Dtest.profile=mysql`
- [ ] Test with H2: `./gradlew test -Dtest.profile=h2`
- [ ] Manual testing with sample workflows
- [ ] Performance testing (ensure no regression)

---

## Rollout Strategy

### Development
1. **Phase 0 PR (Optional but Recommended):** Simplify NodePosition first
   - Create separate PR for NodePosition refactoring
   - Easier to review and test in isolation
   - Reduces risk for main WorkflowStep implementation
2. Implement Phase 1-3 in feature branch (core data structures)
3. Run lemline-core tests, verify no regressions
4. Implement Phase 4 (ID generation changes)
5. Run lemline-runner tests, verify no regressions
6. Add integration tests (Phase 5)

### Testing
1. Test all supported databases (PostgreSQL, MySQL, H2)
2. Test backwards compatibility:
   - Existing serialized WorkflowState without visitCount
   - Existing database rows
3. Load testing: Verify no performance regression
4. Chaos testing: Concurrent workflows with loops/retries

### Deployment
1. **No database migration needed** (schema already compatible)
2. Deploy new version
3. Existing workflows continue with visitCount=0 initially
4. New workflows use visit counts immediately
5. Monitor for any database constraint violations

### Rollback
- If issues found: Rollback deployment
- No data migration needed (backwards compatible)
- Old code can read new WorkflowState (visitCount ignored if missing)

---

## Risk Assessment

### Low Risk
- ✅ No database schema changes needed
- ✅ Backwards compatible with existing serialized states
- ✅ Pure addition (no breaking changes to existing code)
- ✅ Comprehensive test coverage planned

### Medium Risk
- ⚠️ ID generation changes - must verify determinism
- ⚠️ Processor navigation logic - must verify visitCount increments correctly
- ⚠️ Performance impact of lazy workflowStep computation

### Mitigation
- Extensive testing with all database backends
- Integration tests for collision scenarios
- Performance benchmarks before/after
- Staged rollout with monitoring

---

## Success Criteria

1. ✅ No database primary key violations in loop/retry scenarios
2. ✅ Deterministic ID generation (same execution → same ID)
3. ✅ All tests pass on PostgreSQL, MySQL, H2
4. ✅ No performance regression in outbox processing
5. ✅ Documentation complete and accurate
6. ✅ Backwards compatible with existing workflows

---

## Timeline Estimate

- **Phase 0** (Simplify NodePosition): 1-2 days
- **Phase 1-2** (Core structures, visitCount): 2-3 days
- **Phase 3** (WorkflowStep builder): 1-2 days
- **Phase 4** (ID generation): 2-3 days
- **Phase 5** (Integration testing): 2-3 days
- **Phase 6** (Documentation): 1 day
- **Phase 7** (Final verification): 1-2 days

**Total: 10-16 days** (2-3 weeks)

**Note:** Phase 0 can be done as a separate PR to reduce risk and make review easier.

---

## Notes

- Focus on correctness over performance (compute workflowStep is cheap)
- Extensive testing is critical (database collisions would be catastrophic)
- Keep static `workflow_position` column for query convenience
- Denormalized schema (namespace/name/version) is intentional for performance
