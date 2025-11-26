# Step Index for Idempotent Operations

**Status**: Planning
**Date**: 2025-11-24
**Author**: System Design

## Overview

This document describes how **stepIndex** (a deterministic step identifier) is used to achieve idempotency in Lemline's messaging and database operations.

**For stepIndex structure and generation details**, see [ADR-0009: Dynamic JSON Pointer for Step Indexing](adr/0009-dynamic-step-index.md).

### What is StepIndex?

StepIndex is a dynamic JSON Pointer that uniquely identifies each execution step within a workflow instance. Examples:
- `/do/0/taskA` - First task in do block
- `/try/2/first` - Retry attempt (try visit count = 2)
- `/fork/0/branch1/0/do/0/task1` - Task in parallel branch

### Why Idempotency Matters

**Problem**: Message brokers (Kafka, RabbitMQ) can deliver messages multiple times due to:
- Network failures
- Consumer crashes/restarts
- Broker rebalancing

**Without idempotency**:
- Same task executes twice → duplicate API calls, data corruption
- Same wait scheduled twice → multiple timers firing
- Same retry scheduled twice → cascading retries

**With stepIndex-based idempotency**:
- Same execution step → same stepIndex → same deterministic UUID
- Duplicate messages/operations are automatically detected and prevented

## Idempotency Strategy

Lemline uses **two-level deduplication** aligned with its core philosophy:

1. **Message-level** (Broker): Prevent duplicate messages in the queue
2. **Database-level** (Outbox tables): Prevent duplicate rows for business events

## Level 1: Broker-Based Message Deduplication

### Deterministic Message IDs

Each `InstanceMessage` gets a deterministic message ID derived from stepIndex:

```kotlin
// lemline-runner/src/main/kotlin/com/lemline/runner/messaging/InstanceMessage.kt

@Serializable
data class InstanceMessage<S : WorkflowState>(
    val workflowInfo: WorkflowInfo,
    val workflowState: S  // Contains stepIndex
) {
    /**
     * Generate deterministic message ID from stepIndex.
     * Same execution step → same messageId → broker can deduplicate.
     */
    val messageId: IDV7 by lazy {
        IDV7.fromNamespace(
            namespace = workflowState.workflowId,
            name = workflowState.stepIndex.toCompactString()
        )
    }
}
```

**How it works:**
1. Processor computes stepIndex for current workflow step
2. InstanceMessage generates deterministic messageId from stepIndex
3. MessageEmitter sends message with this messageId to broker
4. Broker uses messageId for deduplication (Kafka sequence numbers, RabbitMQ dedup plugin)

**Example:**
```
Step 1: Execute taskA → stepIndex = "/do/0/taskA"
        → messageId = IDV7.fromNamespace(workflowId, "/do/0/taskA")
        → Send message to broker

Retry:  Execute taskA again → SAME stepIndex = "/do/0/taskA"
        → SAME messageId (deterministic!)
        → Broker detects duplicate → discards message
        → No double-processing!
```

### 2. Deterministic IDV7 Generation

Since we need **deterministic** IDs (for both dedup and audit), we can't use `IDV7.random()`.

**Solution: UUID v5 (Name-based with SHA-1)**

```kotlin
// lemline-common/src/main/kotlin/com/lemline/common/types/IDV7.kt

/**
 * Generate deterministic IDV7 from a namespace UUID and name string.
 * Uses UUID v5 (name-based with SHA-1 hashing).
 *
 * This enables idempotent operations: same (namespace, name) always produces same UUID.
 *
 * @param namespace The namespace UUID (typically workflowId)
 * @param name The name string (typically stepIndex.toCompactString())
 * @return Deterministic UUID v5
 */
fun IDV7.Companion.fromNamespace(namespace: IDV7, name: String): IDV7 {
    // Convert namespace UUID to bytes
    val namespaceBytes = ByteBuffer.allocate(16).apply {
        putLong(namespace.uuid.mostSignificantBits)
        putLong(namespace.uuid.leastSignificantBits)
    }.array()

    // Combine namespace + name and hash with SHA-1
    val nameBytes = name.toByteArray(Charsets.UTF_8)
    val combined = namespaceBytes + nameBytes

    val hash = MessageDigest.getInstance("SHA-1").digest(combined)

    // Construct UUID v5 from hash
    val uuid = UUID.nameUUIDFromBytes(combined)
    return IDV7.from(uuid)
}

// Usage:
val stepId = IDV7.fromNamespace(
    namespace = workflowId,
    name = stepIndex.toCompactString()
)
```

**Why UUID v5?**
- Standard, well-tested algorithm
- Deterministic: same input always produces same output
- Globally unique (collision probability ~0)
- Compatible with existing IDV7 infrastructure

### 3. WorkflowState Integration

Add `stepIndex` to the `WorkflowState` sealed class hierarchy:

```kotlin
// lemline-core/src/main/kotlin/com/lemline/core/states/WorkflowState.kt
@Serializable
sealed class WorkflowState {
    abstract val taskStates: TaskStates
    abstract val nodePosition: NodePosition
    abstract val stepIndex: StepIndex  // NEW!

    val workflowId: IDV7 get() = (taskStates[NodePosition.root] as RootState).workflowId
    val hasWaitingParent: Boolean get() = (taskStates[NodePosition.root] as RootState).hasWaitingParent
}
```

All subclasses must include `stepIndex` field:
- `WorkflowCommand.ResumeFromTask`
- `WorkflowCommand.ResumeWithCompletedTask`
- `WorkflowCommand.ResumeWithFailedTask`
- `WorkflowEvent.TaskScheduled`
- `WorkflowEvent.WaitStarted`
- `WorkflowEvent.RetryScheduled`
- `WorkflowEvent.RunWorkflowStarted`
- `WorkflowEvent.ForkStarted`
- `WorkflowEvent.BranchCompleted`
- `WorkflowEvent.BranchFailed`
- `WorkflowEvent.WorkflowCompleted`
- `WorkflowEvent.WorkflowFailed`

### 4. StepIndex Computation in Processor

The key challenge: **How to compute stepIndex from NodePosition + execution state?**

**Approach**: Maintain a `StepIndexTracker` that tracks execution progress:

```kotlin
// lemline-core/src/main/kotlin/com/lemline/core/states/StepIndexTracker.kt

/**
 * Tracks step execution progress to generate deterministic stepIndex.
 *
 * Key behaviors:
 * - Increments sequential counter when moving to next task
 * - Adds branch segment when entering fork branch
 * - Adds iteration segment when entering foreach loop
 * - Preserves stepIndex during retries (doesn't increment)
 */
class StepIndexTracker {
    private var sequentialCounter = 0
    private val branchStack = mutableListOf<Int>()
    private val iterationCounters = mutableMapOf<String, Int>() // key = loop position

    /**
     * Generate stepIndex for task execution.
     *
     * @param position Current NodePosition
     * @param isRetry True if this is a retry attempt
     * @return StepIndex for this execution
     */
    fun onTaskStarted(position: NodePosition, isRetry: Boolean): StepIndex {
        if (isRetry) {
            return current() // Same stepIndex for retries!
        }

        // Parse position to detect branches/iterations
        val segments = buildSegments(position)
        sequentialCounter++
        return StepIndex(segments)
    }

    /**
     * Called when entering a fork branch.
     *
     * @param branchIndex The branch index (0, 1, 2...)
     */
    fun onBranchEntered(branchIndex: Int) {
        branchStack.add(branchIndex)
    }

    /**
     * Called when exiting a fork branch.
     */
    fun onBranchExited() {
        branchStack.removeLastOrNull()
    }

    /**
     * Called when starting a foreach iteration.
     *
     * @param loopPosition The NodePosition of the foreach loop
     * @param iteration The iteration number (0, 1, 2...)
     */
    fun onIterationStarted(loopPosition: String, iteration: Int) {
        iterationCounters[loopPosition] = iteration
    }

    /**
     * Get current stepIndex without incrementing.
     */
    fun current(): StepIndex {
        return StepIndex(buildSegmentsFromState())
    }

    private fun buildSegments(position: NodePosition): List<StepIndex.Segment> {
        // Build from sequentialCounter, branchStack, iterationCounters
        val segments = mutableListOf<StepIndex.Segment>()

        // Add sequential counter
        segments.add(StepIndex.Segment.Sequential(sequentialCounter))

        // Add branch path
        branchStack.forEach { branchIdx ->
            segments.add(StepIndex.Segment.Branch(branchIdx))
        }

        // Add iteration counters (extracted from position)
        // TODO: Parse position to find iteration tokens

        return segments
    }

    private fun buildSegmentsFromState(): List<StepIndex.Segment> {
        val segments = mutableListOf<StepIndex.Segment>()
        segments.add(StepIndex.Segment.Sequential(sequentialCounter))
        branchStack.forEach { segments.add(StepIndex.Segment.Branch(it)) }
        return segments
    }
}
```

**Integration with Processor:**

```kotlin
// lemline-core/src/main/kotlin/com/lemline/core/processor/Processor.kt

class Processor(
    private val workflow: Workflow,
    private val input: String? = null,
    private val workflowId: IDV7 = IDV7.random()
) {
    private val stepIndexTracker = StepIndexTracker()

    suspend fun run(): WorkflowState {
        // Detect if this is a retry attempt
        val currentTaskState = states[currentPosition]
        val isRetry = currentTaskState is RetryableTaskState &&
                      currentTaskState.attemptCount > 0

        // Generate stepIndex
        val stepIndex = stepIndexTracker.onTaskStarted(currentPosition, isRetry)

        // Execute task...
        val nextState = executeCurrentTask()

        return when (nextState) {
            is NextStepInfo.NextTask -> WorkflowState.TaskScheduled(
                taskStates = states,
                nodePosition = nextPosition,
                stepIndex = stepIndex
            )
            is NextStepInfo.Wait -> WorkflowState.WaitStarted(
                taskStates = states,
                nodePosition = currentPosition,
                stepIndex = stepIndex,
                delay = nextState.delay
            )
            // ... other cases
        }
    }
}
```

### 5. Messaging Integration with Broker Deduplication

**InstanceMessage** carries `WorkflowState` with `stepIndex`:

```kotlin
// lemline-runner/src/main/kotlin/com/lemline/runner/messaging/InstanceMessage.kt

@ExperimentalTime
@Serializable
data class InstanceMessage<S : WorkflowState>(
    @SerialName("i") override val workflowInfo: WorkflowInfo,
    @SerialName("s") val workflowState: S,  // Contains stepIndex
) : WithDefiniteWorkflowInfo, JsonSerializable {

    /**
     * Generate deterministic step ID from workflowId + stepIndex.
     * This ID is used as the broker message ID for deduplication.
     *
     * The broker (Kafka/RabbitMQ) uses this ID to detect and discard duplicates.
     * No database lookups required - deduplication happens at the broker level.
     */
    val stepId: IDV7 by lazy {
        IDV7.fromNamespace(
            namespace = workflowState.workflowId,
            name = workflowState.stepIndex.toCompactString()
        )
    }
}
```

**MessageEmitter** uses `stepId` as **broker message ID**:

```kotlin
// lemline-runner/src/main/kotlin/com/lemline/runner/messaging/MessageEmitter.kt

interface MessageEmitter {
    suspend fun <S : WorkflowState> send(message: InstanceMessage<S>)
}

// Implementation
class MessageEmitterImpl : MessageEmitter {
    override suspend fun <S : WorkflowState> send(message: InstanceMessage<S>) {
        val stepId = message.stepId  // Deterministic!
        val md = MetaData(messageId = stepId)

        retry(
            logger = logger,
            label = "Emit message",
            maxAttempts = 6,
            totalBudgetMs = 6_000,
            singleAttemptTimeoutMs = 1_000
        ) {
            emit(message.toJsonString(), md)
        }
    }

    private suspend fun emit(payload: String, metaData: MetaData) {
        // Send to broker with deterministic message ID in headers
        // Broker handles deduplication automatically
    }
}
```

### 6. Broker-Specific Deduplication Configuration

#### Kafka: Idempotent Producer

Kafka provides exactly-once semantics through idempotent producers:

```yaml
# application.yml
lemline:
  messaging:
    kafka:
      brokers: localhost:9092
      topic: lemline
      # Enable idempotent producer
      producer:
        enable.idempotence: true
        acks: all
        max.in.flight.requests.per.connection: 5
        retries: 2147483647  # Integer.MAX_VALUE
```

**How it works:**
- Kafka assigns each producer a unique `ProducerId`
- Each message gets a sequence number per partition
- Broker detects duplicates: same `(ProducerId, partition, sequence)` → ignored
- Works transparently - no application changes needed beyond config

**Quarkus Kafka connector configuration:**
```properties
# Automatically configured by lemline.messaging.kafka.producer.enable-idempotence
mp.messaging.outgoing.commands-out.enable.idempotence=true
mp.messaging.outgoing.commands-out.acks=all
mp.messaging.outgoing.commands-out.max.in.flight.requests.per.connection=5
```

**Benefits:**
- Exactly-once delivery within a transaction
- No database lookups needed
- Handles producer retries automatically
- Low overhead (sequencing in memory)

#### RabbitMQ: Message Deduplication Plugin

RabbitMQ provides deduplication via optional plugin:

```yaml
# application.yml
lemline:
  messaging:
    rabbitmq:
      host: localhost
      port: 5672
      # Deduplication configuration
      consumer:
        deduplication:
          enabled: true
          cache-size: 10000
          ttl-ms: 600000  # 10 minutes
```

**Plugin installation:**
```bash
# Enable deduplication plugin
rabbitmq-plugins enable rabbitmq_message_deduplication
```

**Exchange declaration with deduplication:**
```kotlin
// Declare exchange with deduplication
channel.exchangeDeclare(
    "lemline-commands",
    "x-message-deduplication",  // Special exchange type
    true,  // durable
    false, // auto-delete
    mapOf("x-cache-size" to 10000, "x-cache-ttl" to 600000)
)
```

**How it works:**
- Plugin maintains cache of message IDs per exchange
- Duplicate message IDs are discarded before routing
- Configurable cache size and TTL
- Works with message ID header (our `stepId`)

**Quarkus RabbitMQ connector configuration:**
```properties
# Automatically configured by lemline.messaging.rabbitmq
mp.messaging.outgoing.commands-out.exchange.type=x-message-deduplication
mp.messaging.outgoing.commands-out.exchange.x-cache-size=10000
mp.messaging.outgoing.commands-out.exchange.x-cache-ttl=600000
```

**Benefits:**
- Broker-level deduplication (no app logic)
- Configurable cache for memory management
- Works across consumer restarts (within TTL)

### 7. Message Flow with Deduplication

**Normal execution:**
```
1. Processor generates WorkflowState with stepIndex="1"
2. InstanceMessage created with stepId = IDV7.fromNamespace(workflowId, "1")
3. MessageEmitter sends with messageId = stepId
4. Broker receives message:
   - Kafka: Assigns sequence number, stores in partition
   - RabbitMQ: Checks dedup cache, adds messageId, routes
5. Consumer processes message
```

**Retry scenario (same stepIndex):**
```
1. Task fails, retry scheduled
2. Retry message created with SAME stepIndex="1"
3. stepId = IDV7.fromNamespace(workflowId, "1")  ← SAME!
4. MessageEmitter sends with messageId = stepId
5. Broker receives message:
   - Kafka: Same ProducerId + sequence → DEDUPLICATED
   - RabbitMQ: messageId in cache → DISCARDED
6. Consumer never sees duplicate
```

**Broker redelivery scenario:**
```
1. Consumer crashes while processing stepIndex="1"
2. Broker redelivers message (consumer acknowledgment timeout)
3. Consumer processes message:
   - stepIndex="1" already processed
   - Task state shows completion/failure
   - Consumer skips or handles based on state
4. NOTE: This is different from message deduplication!
   - Broker dedup: Same message sent multiple times by producer
   - Redelivery: Consumer didn't acknowledge, message re-sent
```

**Important distinction:**
- **Broker dedup**: Prevents same message from being written to queue multiple times
- **Consumer idempotency**: Handles redelivery after consumer crash (application-level)

For consumer idempotency, we rely on `WorkflowState` containing enough information to detect:
- Task already completed (state shows completion)
- Task in progress (can resume or skip based on task type)

**No database lookups required** - state is in the message!

### 8. Deterministic Database IDs for Business Events

While Lemline minimizes database usage, certain business events **must** be persisted:
- **Waits** (`lemline_waits`): Timer-based delays
- **Retries** (`lemline_retries`): Exponential backoff retry scheduling
- **Parents** (`lemline_parents`): Parent-child workflow relationships
- **Schedules** (`lemline_schedules`): Cron-based scheduling
- **Failures** (`lemline_failures`): Permanent workflow failures

**Problem**: Currently these tables use `IDV7.random()` for primary keys. If the same message is processed twice (broker redelivery after consumer crash), duplicate rows are created.

**Solution**: Use stepIndex to generate **deterministic row IDs**:

```kotlin
// Before (current - can create duplicates)
val waitId = IDV7.random()  // Different ID each time
waitRepository.insert(WaitOutboxModel(id = waitId, ...))

// After (proposed - idempotent)
val waitId = IDV7.fromNamespace(workflowId, stepIndex.toCompactString())
waitRepository.insert(WaitOutboxModel(id = waitId, ...))
// If processed twice → same ID → primary key conflict → no duplicate row!
```

#### Implementation for Each Outbox Table

**WaitOutbox** (`lemline_waits`):
```kotlin
// StepByStepRunner.kt - onWait()
val waitId = IDV7.fromNamespace(
    namespace = workflowId,
    name = stepIndex.toCompactString()
)

val waitModel = WaitOutboxModel(
    id = waitId,  // Deterministic!
    workflowId = workflowId,
    stepIndex = stepIndex.toCompactString(),  // Replaces workflow_position
    delay = delay,
    instanceMessage = message.toJsonString(),
    status = OutboxStatus.PENDING
)

// INSERT with ON CONFLICT handling
waitRepository.insertIfNotExists(waitModel)
```

**RetryOutbox** (`lemline_retries`):
```kotlin
// StepByStepRunner.kt - onRetry()
val retryId = IDV7.fromNamespace(
    namespace = workflowId,
    name = stepIndex.toCompactString() + ":${attemptCount}"  // Include attempt count
)

val retryModel = RetryOutboxModel(
    id = retryId,  // Deterministic!
    workflowId = workflowId,
    stepIndex = stepIndex.toCompactString(),
    attemptCount = attemptCount,
    delay = calculateBackoff(attemptCount),
    instanceMessage = message.toJsonString(),
    status = OutboxStatus.PENDING
)

retryRepository.insertIfNotExists(retryModel)
```

**Note on Retry IDs**: Since retries of the same task share the same stepIndex, we append attempt count to make each retry attempt unique.

**ParentOutbox** (`lemline_parents`):
```kotlin
// StepByStepRunner.kt - onRunWorkflow()
val parentId = IDV7.fromNamespace(
    namespace = workflowId,
    name = stepIndex.toCompactString()
)

val parentModel = ParentOutboxModel(
    id = parentId,  // Deterministic!
    workflowId = workflowId,
    stepIndex = stepIndex.toCompactString(),
    parentMessage = message.toJsonString(),
    childWorkflowId = childWorkflowId,
    status = OutboxStatus.PENDING
)

parentRepository.insertIfNotExists(parentModel)
```

#### Database Schema Updates

Replace `workflow_position` with `step_index` in all outbox tables:

```sql
-- Migration: V00X__replace_position_with_step_index.sql

-- Waits table
ALTER TABLE lemline_waits
DROP COLUMN workflow_position,
ADD COLUMN step_index VARCHAR(255) NOT NULL;

CREATE INDEX idx_waits_step_index ON lemline_waits(step_index);

-- Retries table
ALTER TABLE lemline_retries
DROP COLUMN workflow_position,
ADD COLUMN step_index VARCHAR(255) NOT NULL;

CREATE INDEX idx_retries_step_index ON lemline_retries(step_index);

-- Parents table
ALTER TABLE lemline_parents
DROP COLUMN workflow_position,
ADD COLUMN step_index VARCHAR(255) NOT NULL;

CREATE INDEX idx_parents_step_index ON lemline_parents(step_index);

-- Schedules table
ALTER TABLE lemline_schedules
DROP COLUMN workflow_position,
ADD COLUMN step_index VARCHAR(255) NOT NULL;

-- Failures table
ALTER TABLE lemline_failures
DROP COLUMN workflow_position,
ADD COLUMN step_index VARCHAR(255) NOT NULL;
```

**Why replace instead of add:**
- stepIndex contains workflow_position information (static position = strip visit counts)
- stepIndex provides MORE information (retry attempt, foreach iteration, etc.)
- Avoids redundancy (no need to store both)
- Better for debugging (shows exact execution instance)

#### Repository Updates

Add `insertIfNotExists` pattern to outbox repositories:

```kotlin
interface WaitOutboxRepository : OutboxRepository<WaitOutboxModel> {
    /**
     * Insert wait record if not exists (idempotent).
     * Returns true if inserted, false if already exists.
     */
    suspend fun insertIfNotExists(model: WaitOutboxModel): Boolean
}

// Implementation
override suspend fun insertIfNotExists(model: WaitOutboxModel): Boolean {
    val sql = """
        INSERT INTO lemline_waits (
            id, workflow_id, step_index, delay, instance_message, status, created_at
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7)
        ON CONFLICT (id) DO NOTHING
    """

    val result = client.preparedQuery(sql)
        .execute(Tuple.of(
            model.id.uuid,
            model.workflowId.uuid,
            model.stepIndex,
            model.delay,
            model.instanceMessage,
            model.status.name,
            model.createdAt
        ))
        .await()

    return result.rowCount() > 0
}
```

#### Benefits

1. **Idempotent database writes**: Same event processed twice → same ID → no duplicate rows
2. **Automatic deduplication**: Primary key constraint prevents duplicates at database level
3. **No additional queries**: INSERT with ON CONFLICT is single operation
4. **Debugging**: stepIndex column shows which workflow step created the row
5. **Consistency**: Aligns with deterministic message ID strategy

#### Example Scenario: Wait Task with Redelivery

```
Time 0: Process wait task (stepIndex="5")
  → Generate waitId = IDV7.fromNamespace(workflowId, "5")
  → INSERT INTO lemline_waits (id=waitId, ...)
  → Success! Row created
  → Send to events-out channel

Time 1: Consumer crashes before acknowledging message
  → Broker redelivers wait task message
  → Generate waitId = IDV7.fromNamespace(workflowId, "5")  ← SAME ID!
  → INSERT INTO lemline_waits (id=waitId, ...) ON CONFLICT DO NOTHING
  → Conflict! Row already exists → rowCount = 0
  → No duplicate created, processing continues safely
```

**No duplicate waits scheduled** - the timer will only fire once!

### 9. Audit Trail: CloudEvents (Future)

**Decision**: Audit trail will be handled via **CloudEvents** from the Serverless Workflow specification (not yet implemented in Lemline).

This design document focuses on:
- **Deterministic stepIndex** generation
- **Broker-based deduplication** using stepIndex

Audit/observability will be addressed separately through CloudEvents implementation, which aligns with the Serverless Workflow DSL standard.

## Testing Strategy

### Unit Tests (lemline-core)

```kotlin
// lemline-core/src/test/kotlin/com/lemline/core/states/StepIndexTest.kt

class StepIndexTest {

    @Test
    fun `sequential tasks get incrementing indices`() {
        val tracker = StepIndexTracker()

        val step0 = tracker.onTaskStarted(NodePosition.parse("/do/0"), isRetry = false)
        assertEquals("0", step0.toCompactString())

        val step1 = tracker.onTaskStarted(NodePosition.parse("/do/1"), isRetry = false)
        assertEquals("1", step1.toCompactString())

        val step2 = tracker.onTaskStarted(NodePosition.parse("/do/2"), isRetry = false)
        assertEquals("2", step2.toCompactString())
    }

    @Test
    fun `retry preserves stepIndex`() {
        val tracker = StepIndexTracker()

        val step1 = tracker.onTaskStarted(NodePosition.parse("/do/0"), isRetry = false)
        assertEquals("0", step1.toCompactString())

        // Retry attempt - should get SAME stepIndex
        val step1Retry = tracker.onTaskStarted(NodePosition.parse("/do/0"), isRetry = true)
        assertEquals("0", step1Retry.toCompactString())
        assertEquals(step1, step1Retry)
    }

    @Test
    fun `fork branches get distinct indices`() {
        val tracker = StepIndexTracker()

        // Fork task
        val forkStep = tracker.onTaskStarted(NodePosition.parse("/fork"), isRetry = false)
        assertEquals("0", forkStep.toCompactString())

        // Branch 0
        tracker.onBranchEntered(0)
        val branch0Task = tracker.onTaskStarted(NodePosition.parse("/branches/0/task"), isRetry = false)
        assertEquals("0.0", branch0Task.toCompactString())

        // Branch 1
        tracker.onBranchExited()
        tracker.onBranchEntered(1)
        val branch1Task = tracker.onTaskStarted(NodePosition.parse("/branches/1/task"), isRetry = false)
        assertEquals("0.1", branch1Task.toCompactString())
    }

    @Test
    fun `foreach iterations get distinct indices`() {
        val tracker = StepIndexTracker()

        // Iteration 0
        tracker.onIterationStarted("/foreach", 0)
        val iter0 = tracker.onTaskStarted(NodePosition.parse("/foreach/do/0"), isRetry = false)
        assertEquals("0[0]", iter0.toCompactString())

        // Iteration 1
        tracker.onIterationStarted("/foreach", 1)
        val iter1 = tracker.onTaskStarted(NodePosition.parse("/foreach/do/0"), isRetry = false)
        assertEquals("0[1]", iter1.toCompactString())

        // Iteration 2
        tracker.onIterationStarted("/foreach", 2)
        val iter2 = tracker.onTaskStarted(NodePosition.parse("/foreach/do/0"), isRetry = false)
        assertEquals("0[2]", iter2.toCompactString())
    }

    @Test
    fun `nested forks create hierarchical indices`() {
        val tracker = StepIndexTracker()

        // Outer fork
        tracker.onTaskStarted(NodePosition.parse("/fork1"), isRetry = false) // "0"
        tracker.onBranchEntered(0)

        // Inner fork in branch 0
        tracker.onTaskStarted(NodePosition.parse("/branches/0/fork2"), isRetry = false) // "0.0"
        tracker.onBranchEntered(1)

        val nestedTask = tracker.onTaskStarted(NodePosition.parse("/branches/0/fork2/branches/1/task"), isRetry = false)
        assertEquals("0.0.1", nestedTask.toCompactString())
    }

    @Test
    fun `deterministic IDV7 generation`() {
        val workflowId = IDV7.random()
        val stepIndex = StepIndex.parse("0.1[2].3")

        // Same inputs should produce same output
        val id1 = IDV7.fromNamespace(workflowId, stepIndex.toCompactString())
        val id2 = IDV7.fromNamespace(workflowId, stepIndex.toCompactString())

        assertEquals(id1, id2)

        // Different stepIndex should produce different output
        val differentStepIndex = StepIndex.parse("0.2[2].3")
        val id3 = IDV7.fromNamespace(workflowId, differentStepIndex.toCompactString())

        assertNotEquals(id1, id3)
    }

    @Test
    fun `stepIndex parsing roundtrip`() {
        val original = StepIndex(
            listOf(
                StepIndex.Segment.Sequential(0),
                StepIndex.Segment.Branch(1),
                StepIndex.Segment.Iteration(2),
                StepIndex.Segment.Sequential(3)
            )
        )

        val compactString = original.toCompactString()
        assertEquals("0.1[2].3", compactString)

        val parsed = StepIndex.parse(compactString)
        assertEquals(original, parsed)
    }
}
```

### Integration Tests (lemline-runner)

```kotlin
// lemline-runner/src/test/kotlin/com/lemline/runner/messaging/StepDeduplicationTest.kt

@QuarkusTest
@TestProfile(PostgresProfile::class)
class StepDeduplicationTest : FunSpec({

    @Inject
    lateinit var handler: InstanceMessageHandler

    @Inject
    lateinit var stepRepository: StepRepository

    test("duplicate messages are ignored") {
        // Create instance message with deterministic stepIndex
        val workflowId = IDV7.random()
        val stepIndex = StepIndex.parse("0")

        val message = InstanceMessage(
            workflowInfo = WorkflowInfo(
                workflowNamespace = WorkflowNamespace("test"),
                workflowName = WorkflowName("example"),
                workflowVersion = WorkflowVersion("1.0.0")
            ),
            workflowState = WorkflowState.TaskScheduled(
                taskStates = mapOf(
                    NodePosition.root to RootState(workflowId = workflowId)
                ),
                nodePosition = NodePosition.parse("/do/0"),
                stepIndex = stepIndex
            )
        )

        // First processing - should succeed
        handler.handle(message)

        val step1 = stepRepository.findByUUID(message.stepId)
        assertNotNull(step1)
        assertEquals(workflowId, step1.workflowId)
        assertEquals("0", step1.stepIndex)

        // Simulate broker redelivery (duplicate message)
        handler.handle(message)

        // Verify only one execution recorded
        val executions = stepRepository.findByWorkflowId(workflowId)
        assertEquals(1, executions.size)
    }

    test("retry attempts share stepIndex") {
        val workflowId = IDV7.random()
        val stepIndex = StepIndex.parse("1")

        // Initial attempt
        val message1 = createTaskMessage(workflowId, stepIndex, attemptCount = 0)
        handler.handle(message1)

        // Retry attempt - SAME stepIndex
        val message2 = createTaskMessage(workflowId, stepIndex, attemptCount = 1)
        handler.handle(message2)

        // Should be deduplicated (only first attempt recorded)
        val executions = stepRepository.findByWorkflowId(workflowId)
        assertEquals(1, executions.size)
        assertEquals("1", executions[0].stepIndex)
    }

    test("foreach iterations get distinct stepIndex") {
        val workflowId = IDV7.random()

        // Iteration 0
        val message0 = createTaskMessage(workflowId, StepIndex.parse("0[0]"), attemptCount = 0)
        handler.handle(message0)

        // Iteration 1
        val message1 = createTaskMessage(workflowId, StepIndex.parse("0[1]"), attemptCount = 0)
        handler.handle(message1)

        // Iteration 2
        val message2 = createTaskMessage(workflowId, StepIndex.parse("0[2]"), attemptCount = 0)
        handler.handle(message2)

        // Should have 3 distinct executions
        val executions = stepRepository.findByWorkflowId(workflowId)
        assertEquals(3, executions.size)
        assertEquals(setOf("0[0]", "0[1]", "0[2]"), executions.map { it.stepIndex }.toSet())
    }

    test("fork branches get distinct stepIndex") {
        val workflowId = IDV7.random()

        // Branch 0
        val branch0 = createTaskMessage(workflowId, StepIndex.parse("0.0"), attemptCount = 0)
        handler.handle(branch0)

        // Branch 1
        val branch1 = createTaskMessage(workflowId, StepIndex.parse("0.1"), attemptCount = 0)
        handler.handle(branch1)

        // Should have 2 distinct executions
        val executions = stepRepository.findByWorkflowId(workflowId)
        assertEquals(2, executions.size)
        assertEquals(setOf("0.0", "0.1"), executions.map { it.stepIndex }.toSet())
    }

    test("stepId is deterministic") {
        val workflowId = IDV7.random()
        val stepIndex = StepIndex.parse("0")

        val message1 = createTaskMessage(workflowId, stepIndex, attemptCount = 0)
        val message2 = createTaskMessage(workflowId, stepIndex, attemptCount = 0)

        // Same workflowId + stepIndex should produce same stepId
        assertEquals(message1.stepId, message2.stepId)
    }
})
```

### End-to-End Tests

```kotlin
// lemline-runner/src/test/kotlin/com/lemline/runner/workflows/WorkflowStepAuditTest.kt

@QuarkusTest
class WorkflowStepAuditTest : FunSpec({

    @Inject
    lateinit var workflowExecutor: WorkflowExecutor

    @Inject
    lateinit var stepRepository: StepRepository

    test("workflow execution creates audit trail") {
        val workflowDef = """
            document:
              dsl: 1.0.0
              namespace: test
              name: sequential-tasks
              version: 1.0.0
            do:
              - task1:
                  set:
                    message: "Step 1"
              - task2:
                  set:
                    message: "Step 2"
              - task3:
                  set:
                    message: "Step 3"
        """.trimIndent()

        // Execute workflow
        val result = workflowExecutor.execute(workflowDef, input = null)
        val workflowId = result.workflowId

        // Verify audit trail
        val steps = stepRepository.findByWorkflowId(workflowId)
        assertEquals(3, steps.size)

        // Verify step indices
        assertEquals("0", steps[0].stepIndex)
        assertEquals("1", steps[1].stepIndex)
        assertEquals("2", steps[2].stepIndex)

        // Verify all completed
        steps.forEach { step ->
            assertNotNull(step.completedAt)
            assertNull(step.failedAt)
        }
    }

    test("fork execution creates branch audit trail") {
        val workflowDef = """
            document:
              dsl: 1.0.0
              namespace: test
              name: parallel-fork
              version: 1.0.0
            do:
              - parallel:
                  fork:
                    branches:
                      - name: branch1
                        do:
                          - task1:
                              set:
                                message: "Branch 1"
                      - name: branch2
                        do:
                          - task2:
                              set:
                                message: "Branch 2"
        """.trimIndent()

        val result = workflowExecutor.execute(workflowDef, input = null)
        val workflowId = result.workflowId

        // Verify audit trail
        val steps = stepRepository.findByWorkflowId(workflowId)

        // Should have: fork start + 2 branches
        assertTrue(steps.size >= 2)

        val branchSteps = steps.filter { it.stepIndex.contains(".") }
        assertEquals(2, branchSteps.size)

        // Verify branch indices
        assertTrue(branchSteps.any { it.stepIndex.startsWith("0.0") })
        assertTrue(branchSteps.any { it.stepIndex.startsWith("0.1") })
    }
})
```

## Implementation Plan

### Phase 1: Core StepIndex Infrastructure (lemline-core)

1. **Create `StepIndex.kt`**
   - Implement data class with segments
   - Implement `toCompactString()` serialization
   - Implement `parse()` deserialization
   - Add unit tests

2. **Add deterministic IDV7 generation**
   - Extend `IDV7` with `fromNamespace()` method
   - Add unit tests for determinism

3. **Create `StepIndexTracker.kt`**
   - Implement tracking logic
   - Handle sequential, branch, iteration segments
   - Add unit tests

4. **Update `WorkflowState.kt`**
   - Add `stepIndex` abstract property
   - Update all subclasses (12 classes total)
   - Ensure serialization works

5. **Update `Processor.kt`**
   - Integrate `StepIndexTracker`
   - Detect retry attempts
   - Pass stepIndex to WorkflowState constructors

### Phase 2: Messaging Integration (lemline-runner)

6. **Update `InstanceMessage.kt`**
   - Add `stepId` computed property
   - Add unit tests

7. **Update `MessageEmitter.kt`**
   - Use stepId as message ID
   - Update tests

### Phase 3: Broker Configuration and Outbox Updates (lemline-runner)

8. **Configure broker deduplication**
   - Update `LemlineConfiguration.kt` with Kafka idempotent producer settings
   - Update `LemlineConfiguration.kt` with RabbitMQ dedup plugin settings
   - Add tests for broker configurations

9. **Create database migrations**
   - PostgreSQL: `V00X__add_step_index_to_outbox_tables.sql`
   - MySQL: `V00X__add_step_index_to_outbox_tables.sql`
   - H2: `V00X__add_step_index_to_outbox_tables.sql`
   - Add `step_index` column to all outbox tables

10. **Update outbox models**
    - Add `stepIndex: String` field to `WaitOutboxModel`
    - Add `stepIndex: String` field to `RetryOutboxModel`
    - Add `stepIndex: String` field to `ParentOutboxModel`
    - Add `stepIndex: String` field to `ScheduleOutboxModel`
    - Add `stepIndex: String` field to `FailureOutboxModel`

11. **Update outbox repositories**
    - Add `insertIfNotExists()` method to `WaitOutboxRepository`
    - Add `insertIfNotExists()` method to `RetryOutboxRepository`
    - Add `insertIfNotExists()` method to `ParentOutboxRepository`
    - Add `insertIfNotExists()` method to `ScheduleOutboxRepository`
    - Add `insertIfNotExists()` method to `FailureOutboxRepository`
    - Implement with `ON CONFLICT DO NOTHING` pattern

12. **Update `StepByStepRunner.kt`**
    - Use deterministic IDs: `IDV7.fromNamespace(workflowId, stepIndex)` for outbox records
    - Update `onWait()` to use deterministic wait ID
    - Update `onRetry()` to use deterministic retry ID
    - Update `onRunWorkflow()` to use deterministic parent ID
    - Add integration tests for deduplication

### Phase 4: Testing and Validation

13. **Comprehensive testing**
    - Unit tests for StepIndex, StepIndexTracker, deterministic IDV7
    - Integration tests for broker deduplication (Kafka/RabbitMQ)
    - Integration tests for outbox table deduplication
    - End-to-end workflow tests with retries and redelivery
    - Performance testing (overhead of deterministic ID generation)

14. **Documentation**
    - Update CLAUDE.md with stepIndex architecture
    - Update runner developer guide with broker dedup config
    - Add migration guide for existing deployments

### Phase 5: Optional Enhancements

15. **Observability**
    - Add metrics: outbox deduplication conflicts
    - Add logging: duplicate outbox insert attempts
    - Add dashboard for stepIndex tracking

16. **CLI commands**
    - `lemline instance history <workflowId>` - Query outbox tables by stepIndex
    - `lemline instance debug <workflowId> --step-index <index>` - Find all records for specific step

## Migration Considerations

### Backward Compatibility

**Existing workflows**: When upgrading Lemline with in-flight workflows:

1. **Default stepIndex**: For messages without stepIndex, assign `StepIndex.root`
2. **Gradual rollout**: No feature flag needed - deterministic IDs don't change behavior
3. **Database migration**: Flyway handles schema updates automatically (adds step_index columns)
4. **Existing outbox rows**: NULL step_index for old rows (pre-migration) is acceptable

### Performance Impact

**Deterministic ID generation**:
- UUID v5 generation: ~1-2 microseconds per ID (negligible)
- No additional database queries
- `ON CONFLICT DO NOTHING` is highly efficient in PostgreSQL/MySQL

**Broker deduplication**:
- **Kafka**: Idempotent producer has negligible overhead (<1% throughput impact)
- **RabbitMQ**: Dedup plugin cache lookup is O(1), minimal overhead

**Mitigation strategies**:
- Connection pooling for database operations
- Monitor metrics: p50/p95/p99 latency for outbox inserts
- Broker throughput monitoring

### Outbox Table Growth

**No change from current behavior**:
- Outbox tables already exist and grow with workflow executions
- Adding `step_index` column is informational (debugging/querying)
- Existing outbox cleanup processes remain unchanged

## Key Design Decisions

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Determinism** | UUID v5 (name-based) | Standard, deterministic, collision-resistant |
| **Retry handling** | Same stepIndex | Enables deduplication of retried tasks |
| **Foreach iterations** | Distinct stepIndex | Each iteration processes different data |
| **Branch tracking** | Hierarchical segments | Captures nested forks correctly |
| **Message deduplication** | Broker-native (Kafka/RabbitMQ) | No database overhead, aligns with Lemline philosophy |
| **Database deduplication** | Deterministic IDs for outbox tables only | Prevents duplicate rows for business events (waits, retries, parents) |
| **Message ID** | Use stepId as messageId | Enables broker-level deduplication |
| **Serialization format** | Compact string `"0.1[2].3"` | Human-readable, URL-safe, efficient |
| **Uniqueness scope** | Per workflow instance | Simpler than global uniqueness |
| **Audit trail** | CloudEvents (future) | Separate concern, follows SW spec standard |

## Success Criteria

1. **Functional**:
   - Broker deduplicates messages (no double-processing at message level)
   - Outbox tables prevent duplicate rows for business events
   - Deterministic stepIds enable consistent identification across retries

2. **Performance**:
   - Deterministic ID generation adds < 1 microsecond overhead
   - Broker deduplication has < 1% throughput impact
   - No additional database queries for deduplication

3. **Reliability**:
   - No duplicate waits/retries/parents in database
   - Handles broker redelivery correctly
   - Survives consumer crashes and restarts

4. **Operability**:
   - Easy to debug (step_index in outbox tables)
   - Easy to query (find all events for a workflow step)
   - No additional cleanup processes needed

5. **Alignment with Lemline Philosophy**:
   - Minimizes database usage (only for necessary business events)
   - Carries state in messages (stepIndex in WorkflowState)
   - Leverages broker capabilities (native deduplication)

## References

- **UUID v5 Specification**: RFC 4122, Section 4.3
- **Idempotency Patterns**: https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/
- **PostgreSQL ON CONFLICT**: https://www.postgresql.org/docs/current/sql-insert.html
- **Lemline Architecture**: `/docs/runner-architecture.md`
