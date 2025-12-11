# Research: End-to-End Testing Framework

**Feature**: 001-testing-architecture
**Date**: 2025-12-11

## Table of Contents

1. [Quarkus Test Profile Composition](#1-quarkus-test-profile-composition)
2. [Event-Based Synchronization Patterns](#2-event-based-synchronization-patterns)
3. [Existing Infrastructure Analysis](#3-existing-infrastructure-analysis)
4. [Key Decisions](#4-key-decisions)

---

## 1. Quarkus Test Profile Composition

### 1.1 Creating Composable Test Profiles

A `QuarkusTestProfile` is the foundation for test configuration in Quarkus 3.x:

```kotlin
class PostgresProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            DATABASE_TYPE to DB_TYPE_POSTGRESQL,
            MESSAGING_TYPE to MSG_TYPE_IN_MEMORY,
        )
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(QuarkusTestProfile.TestResourceEntry(PostgresTestResource::class.java))
    }

    override fun tags(): Set<String> {
        return setOf(DB_TYPE_POSTGRESQL)
    }
}
```

**Key Methods:**
- `getConfigOverrides()`: Configuration properties merged into test config
- `testResources()`: `QuarkusTestResource` lifecycle managers to activate
- `tags()`: Optional tagging for filtering

### 1.2 Profile Composition via Inheritance

**Decision**: Use inheritance for composable profiles (Quarkus doesn't support multiple `@TestProfile` annotations).

```kotlin
// Base profile with common settings
abstract class BaseBrokerTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            ORCHESTRATOR_MODE to "all",
            "lemline.outbox.enabled" to "true",
            "lemline.outbox.wait.outbox.every" to "1s",
            "lemline.outbox.wait.outbox.initial-delay" to "1s"
        )
    }
}

// Kafka + PostgreSQL composition
class KafkaPostgresProfile : BaseBrokerTestProfile() {
    override fun getConfigOverrides(): Map<String, String> {
        return super.getConfigOverrides() + mapOf(
            DATABASE_TYPE to DB_TYPE_POSTGRESQL,
            MESSAGING_TYPE to MSG_TYPE_KAFKA,
            // Loopback configuration for end-to-end testing
            "mp.messaging.incoming.commands-in.topic" to "lemline-commands",
            "mp.messaging.outgoing.commands-out.topic" to "lemline-commands"
        )
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(KafkaTestResource::class.java),
            QuarkusTestProfile.TestResourceEntry(PostgresTestResource::class.java)
        )
    }
}
```

### 1.3 QuarkusTestResource Lifecycle Management

```kotlin
class PostgresTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var postgres: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer(DockerImageName.parse("postgres:14-alpine"))
            .withDatabaseName("lemline_test")
            .withUsername("test")
            .withPassword("test")
        postgres.start()

        val properties = mapOf(
            "lemline.database.type" to "postgresql",
            "lemline.database.postgresql.host" to postgres.host,
            "lemline.database.postgresql.port" to postgres.firstMappedPort.toString(),
            "lemline.database.postgresql.name" to postgres.databaseName,
            "lemline.database.postgresql.username" to postgres.username,
            "lemline.database.postgresql.password" to postgres.password
        )
        // Set as system properties for custom config sources
        properties.forEach { (k, v) -> System.setProperty(k, v) }
        return properties
    }

    override fun stop() {
        if (::postgres.isInitialized) postgres.stop()
    }
}
```

### 1.4 Best Practices

| Practice | Rationale |
|----------|-----------|
| No multiple `@TestProfile` annotations | Quarkus applies only one profile per test class |
| Use inheritance for composition | Avoids configuration duplication |
| Return properties from `start()` | Merged into Quarkus config automatically |
| Set system properties too | Custom config sources can access them |
| Use `lateinit` + `isInitialized` | Safe cleanup of resources that may not start |
| Group tests by profile | Minimizes Quarkus restarts |

---

## 2. Event-Based Synchronization Patterns

### 2.1 Pattern: Coroutine Polling with delay()

**Decision**: Use coroutine-based polling with `delay()` instead of `Thread.sleep()`.

```kotlin
private suspend fun waitForCompletion(
    getResult: () -> WorkflowTestResult?,
    timeoutSeconds: Long = 30
): WorkflowTestResult {
    val startTime = System.currentTimeMillis()
    val timeoutMillis = timeoutSeconds * 1000
    var iterations = 0

    while (getResult() == null && iterations < 30000) {
        iterations++

        if (System.currentTimeMillis() - startTime > timeoutMillis) {
            return WorkflowTestResult.Failure(
                error = "Workflow did not complete within $timeoutSeconds seconds",
                exception = TimeoutException("Workflow execution timeout")
            )
        }

        // Non-blocking delay with adaptive backoff
        delay(if (iterations < 10) 100 else 50)
    }

    return getResult() ?: WorkflowTestResult.Failure(error = "Timeout", exception = null)
}
```

### 2.2 Pattern: Callback-Based Result Collection

**Decision**: Use existing `on*Test` callbacks + closure-captured mutable variables.

```kotlin
var result: WorkflowTestResult? = null
val hasError = AtomicBoolean(false)

// Setup callbacks
commandHandler.onEventProducedTest = { msg, event ->
    if (msg.workflowId == mainWorkflowId && result == null) {
        when (event) {
            is WorkflowEvent.WorkflowCompleted -> {
                result = WorkflowTestResult.Success(event.output)
            }
            is WorkflowEvent.WorkflowFailed -> {
                result = WorkflowTestResult.Failure(error = "...", exception = null)
            }
            else -> { /* ignore */ }
        }
    }
}

commandHandler.onFailureTest = { _, error ->
    if (error != null && !hasError.getAndSet(true)) {
        result = WorkflowTestResult.Failure(error = error.message ?: "Unknown", exception = error as? Exception)
    }
}
```

### 2.3 Pattern: Thread-Safe Event Sequence Capture

**Decision**: Use `CopyOnWriteArrayList` for event sequence verification.

```kotlin
class CloudEventCapture {
    private val _events = CopyOnWriteArrayList<CloudEvent>()
    val events: List<CloudEvent> get() = _events.toList()

    fun capture(event: CloudEvent) {
        _events.add(event)
    }

    fun filterByType(type: String): List<CloudEvent> {
        return _events.filter { it.type == type }
    }

    fun clear() {
        _events.clear()
    }
}
```

### 2.4 Timeout Handling Comparison

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| `withTimeout()` | Coroutine-idiomatic, cancels nested ops | Throws CancellationException | Use for test framework internals |
| `System.currentTimeMillis()` | Simple, real-world timing | Not virtual-time compatible | Use for wall-clock timeouts |
| Iteration limits | Guards infinite loops | Requires tuning | Combine with time limits |

---

## 3. Existing Infrastructure Analysis

### 3.1 Current Test Executors

The codebase has two test executor implementations:

**BrokerWorkflowTestExecutor** (end-to-end with real broker):
- Uses real Kafka/RabbitMQ via Testcontainers
- Loopback configuration (same topic for in/out)
- Callbacks: `onCompleteTest`, `onFailureTest`, `onEventProducedTest`
- Polling-based completion detection
- Location: `lemline-runner/src/test/kotlin/com/lemline/runner/testcases/`

**RunnerWorkflowTestExecutor** (in-memory channels):
- Uses SmallRye in-memory connectors
- Manual message routing between channels
- Faster execution but less production-like
- Location: `lemline-runner/src/test/kotlin/com/lemline/runner/testcases/`

### 3.2 Existing Test Resources

| Resource | Container | Location |
|----------|-----------|----------|
| `KafkaTestResource` | `confluentinc/cp-kafka:7.3.0` | `lemline-runner/src/test/.../resources/` |
| `RabbitMQTestResource` | `rabbitmq:3.13-management-alpine` | `lemline-runner/src/test/.../resources/` |
| `PostgresTestResource` | `postgres:14-alpine` | `lemline-runner/src/test/.../resources/` |
| `MySQLTestResource` | `mysql:8.0` | `lemline-runner/src/test/.../resources/` |

### 3.3 Existing Test Profiles

| Profile | Database | Messaging | Location |
|---------|----------|-----------|----------|
| `PostgresProfile` | PostgreSQL | In-memory | `lemline-runner/src/test/.../profiles/` |
| `MySQLProfile` | MySQL | In-memory | `lemline-runner/src/test/.../profiles/` |
| `KafkaTestCaseProfile` | H2 (in-memory) | Kafka | `lemline-runner/src/test/.../testcases/kafka/` |

### 3.4 WorkflowTestCase from lemline-core

```kotlin
data class WorkflowTestCase(
    val name: String,
    val description: String = name,
    val yaml: String,
    val input: JsonElement = JsonObject(emptyMap()),
    val validate: (WorkflowTestResult) -> String? = { null },
    val tags: Set<String> = emptySet(),
    val dependencies: List<WorkflowDependency> = emptyList()
)

sealed class WorkflowTestResult {
    data class Success(val output: JsonElement) : WorkflowTestResult()
    data class Failure(val error: String, val exception: Exception? = null) : WorkflowTestResult()
}
```

### 3.5 Existing Test Callbacks

The `WorkflowCommandHandler` and `WorkflowEventHandler` provide these test hooks:

```kotlin
// In WorkflowCommandHandler
var onCompleteTest: (Message<String>, InstanceMessage<*>?) -> Unit = { _, _ -> }
var onFailureTest: (Message<String>, Throwable?) -> Unit = { _, _ -> }
var onEventProducedTest: (InstanceMessage<*>, WorkflowEvent) -> Unit = { _, _ -> }

// In WorkflowEventHandler
var onCompleteTest: (Message<String>, InstanceMessage<WorkflowEvent>?) -> Unit = { _, _ -> }
var onFailureTest: (Message<String>, Throwable?) -> Unit = { _, _ -> }
```

---

## 4. Key Decisions

### 4.1 Module Structure

**Decision**: Create dedicated `lemline-testing` module

**Rationale**:
- Separates test infrastructure from production code
- Allows external projects to depend on test utilities
- Follows pattern of `spring-boot-test`, `quarkus-test-*`
- Can be published as a library artifact

**Alternatives Rejected**:
- Keep in `lemline-runner/src/test`: Not reusable by external projects
- Create `lemline-runner-testing` submodule: Unnecessary coupling

### 4.2 Profile Architecture

**Decision**: Composable profiles via inheritance + multiple TestResources per profile

**Rationale**:
- Quarkus doesn't support multiple `@TestProfile` annotations
- Inheritance allows sharing common configuration
- Multiple TestResources per profile enables clean composition

**Implementation**:
```kotlin
// 4 concrete profiles for all combinations
class KafkaPostgresProfile : BaseBrokerTestProfile() { ... }
class KafkaMysSQLProfile : BaseBrokerTestProfile() { ... }
class RabbitMQPostgresProfile : BaseBrokerTestProfile() { ... }
class RabbitMQMySQLProfile : BaseBrokerTestProfile() { ... }
```

### 4.3 Event Synchronization

**Decision**: Use `LifecycleEventHookImpl` → CloudEvents → `CloudEventCapture` → `WorkflowStateHooks`

**REVISED ARCHITECTURE** (based on review feedback):

Instead of creating test-specific hooks, leverage the existing lifecycle event infrastructure:

```
StepByStepOrchestrator
        │
        │ calls
        ▼
LifecycleEventHook (interface in lemline-core)
        │
        │ implemented by
        ▼
LifecycleEventHookImpl (in lemline-runner)
        │
        │ emits CloudEvents to
        ▼
Messaging Channel
        │
        │ subscribed by
        ▼
CloudEventCapture ◄──── WorkflowStateHooks (wraps for convenience)
```

**Rationale**:
- Tests verify the real event emission path (not a test-only path)
- No test-specific hooks to maintain
- Tests see exactly what production users see
- CloudEventCapture handles both capture AND synchronization
- No `Thread.sleep()` or fixed delays (per FR-034)

**Implementation**:
```kotlin
class WorkflowStateHooksImpl(
    private val eventCapture: CloudEventCapture
) : WorkflowStateHooks {

    override suspend fun awaitCompletion(
        workflowId: WorkflowId,
        timeout: Duration
    ): WorkflowTestResult {
        return withTimeout(timeout) {
            // Wait for completion or faulted CloudEvent
            val event = eventCapture.awaitEvent(timeout) { ce ->
                ce.source.toString().contains(workflowId.value) &&
                (ce.type == LemlineEventTypes.WORKFLOW_COMPLETED ||
                 ce.type == LemlineEventTypes.WORKFLOW_FAULTED)
            }

            when (event?.type) {
                LemlineEventTypes.WORKFLOW_COMPLETED ->
                    WorkflowTestResult.Success(extractOutput(event))
                LemlineEventTypes.WORKFLOW_FAULTED ->
                    WorkflowTestResult.Failure(extractError(event))
                else -> error("Timeout waiting for workflow completion")
            }
        }
    }

    override suspend fun awaitTaskStarted(
        workflowId: WorkflowId,
        taskPosition: NodePosition,
        timeout: Duration
    ) {
        eventCapture.awaitEvent(timeout) { ce ->
            ce.type == LemlineEventTypes.TASK_STARTED &&
            ce.source.toString().contains(workflowId.value) &&
            extractTaskPosition(ce) == taskPosition
        } ?: error("Task did not start within $timeout")
    }
}
```

### 4.4 Activity Mocking

**Decision**: Extend existing `ActivityRunner` interface with test implementation

**Rationale**:
- Activity execution already uses `ActivityRunner` interface
- Test implementation returns configured responses
- No need for new abstraction

**Implementation**:
```kotlin
class TestActivityExecutor : ActivityRunner {
    private val responseQueue = ConcurrentLinkedQueue<ActivityResponse>()

    fun queueResponse(response: ActivityResponse) {
        responseQueue.add(response)
    }

    override suspend fun runHttp(config: HttpCallConfig): JsonElement {
        return responseQueue.poll()?.toJson()
            ?: error("No response configured for HTTP call")
    }
}
```

### 4.5 CloudEvent Capture

**Decision**: Subscribe to messaging channel to capture real CloudEvents from `LifecycleEventHookImpl`

**REVISED APPROACH** (based on review feedback):

Instead of using test-only callbacks, subscribe to the actual messaging channel where
`LifecycleEventHookImpl` emits CloudEvents:

**Rationale**:
- Captures the actual CloudEvents emitted in production
- Tests verify the real event path, not a test-only shortcut
- Both lifecycle events and custom emit events flow through the same channel
- Enables both verification AND synchronization via the same mechanism

**Implementation** (with test isolation via workflow ID scoping):
```kotlin
class CloudEventCaptureImpl(
    initialWorkflowId: WorkflowId
) : CloudEventCapture {
    // Scoped workflow IDs - only events from these workflows are captured
    private val _scopedWorkflowIds = ConcurrentHashMap.newKeySet<WorkflowId>()
        .also { it.add(initialWorkflowId) }

    private val _events = CopyOnWriteArrayList<CloudEvent>()
    private val _waiters = CopyOnWriteArrayList<Pair<(CloudEvent) -> Boolean, CompletableDeferred<CloudEvent>>>()

    override fun scopedWorkflowIds(): Set<WorkflowId> = _scopedWorkflowIds.toSet()

    override fun addToScope(workflowId: WorkflowId) {
        _scopedWorkflowIds.add(workflowId)
    }

    // Called when CloudEvent arrives from messaging channel
    // This is registered as a listener on the events channel
    fun onEventReceived(event: CloudEvent) {
        // CRITICAL: Only capture events from scoped workflows
        val workflowIdFromEvent = extractWorkflowId(event)
        if (workflowIdFromEvent == null || workflowIdFromEvent !in _scopedWorkflowIds) {
            return  // Ignore events from other tests
        }

        _events.add(event)

        // Notify any waiters matching this event
        _waiters.removeIf { (predicate, deferred) ->
            if (predicate(event)) {
                deferred.complete(event)
                true
            } else {
                false
            }
        }
    }

    private fun extractWorkflowId(event: CloudEvent): WorkflowId? {
        // Extract workflow ID from CloudEvent source URI
        // e.g., "/lemline/workflows/{workflowId}/tasks/..."
        val source = event.source.toString()
        val match = Regex("/lemline/workflows/([^/]+)").find(source)
        return match?.groupValues?.get(1)?.let { WorkflowId(it) }
    }

    override fun events(): List<CloudEvent> = _events.toList()

    override fun filterByType(type: String): List<CloudEvent> =
        _events.filter { it.type == type }

    override suspend fun awaitEvent(
        timeout: Duration,
        predicate: (CloudEvent) -> Boolean
    ): CloudEvent? {
        // Check if already captured
        _events.find(predicate)?.let { return it }

        // Wait for future event
        val deferred = CompletableDeferred<CloudEvent>()
        _waiters.add(predicate to deferred)

        return withTimeoutOrNull(timeout) {
            deferred.await()
        }
    }
}
```

**Key isolation mechanism**: The `onEventReceived` method filters events by checking if the
workflow ID (extracted from CloudEvent source URI) is in the scoped set. Events from other
tests' workflows are silently dropped, ensuring test isolation even with concurrent execution.

### 4.5.1 Centralized Event Dispatcher

To efficiently route CloudEvents to the correct test captures, we use a **centralized dispatcher**
that subscribes once to the lifecycle events channel and routes to registered captures:

```kotlin
/**
 * Singleton that subscribes to lifecycle events channel and dispatches
 * to registered CloudEventCapture instances based on workflow ID.
 */
@ApplicationScoped
class CloudEventDispatcher {
    // Registry: workflowId -> capture instances interested in that workflow
    private val registry = ConcurrentHashMap<WorkflowId, MutableSet<CloudEventCaptureImpl>>()

    /**
     * Register a capture to receive events for a workflow.
     * Called when CloudEventCapture.addToScope() is invoked.
     */
    fun register(workflowId: WorkflowId, capture: CloudEventCaptureImpl) {
        registry.computeIfAbsent(workflowId) { ConcurrentHashMap.newKeySet() }
            .add(capture)
    }

    /**
     * Unregister a capture (called on test cleanup).
     */
    fun unregister(capture: CloudEventCaptureImpl) {
        registry.values.forEach { it.remove(capture) }
        // Clean up empty entries
        registry.entries.removeIf { it.value.isEmpty() }
    }

    /**
     * Called by messaging subscriber when CloudEvent arrives.
     * Routes to all captures registered for this workflow.
     */
    fun dispatch(event: CloudEvent) {
        val workflowId = extractWorkflowId(event) ?: return

        // Route to all captures interested in this workflow
        registry[workflowId]?.forEach { capture ->
            capture.onEventReceived(event)
        }
    }

    private fun extractWorkflowId(event: CloudEvent): WorkflowId? {
        val source = event.source.toString()
        val match = Regex("/lemline/workflows/([^/]+)").find(source)
        return match?.groupValues?.get(1)?.let { WorkflowId(it) }
    }
}
```

**Flow**:
```
Messaging Channel (lifecycle events)
        │
        │ single subscription
        ▼
CloudEventDispatcher.dispatch(event)
        │
        │ extract workflowId
        │ lookup in registry
        ▼
┌───────┴───────┐
│               │
▼               ▼
Capture A     Capture B    (only captures registered for this workflowId)
(Test 1)      (Test 2)
```

**Benefits**:
- Single channel subscription (efficient)
- O(1) lookup by workflowId
- Multiple captures can observe same workflow (e.g., parent + child workflow tests)
- Clean separation of concerns

### 4.6 Test Isolation

**Decision**: Unique workflow IDs + names per test (no cleanup needed)

**Rationale**:
- Each test uses unique `WorkflowId.random()` + hash-based name
- No cross-test interference via shared state
- Avoids transaction management complexity
- Supports parallel test execution

**From existing code**:
```kotlin
// Generate unique workflow name based on yaml content
val uniqueName = "workflow-${yaml.hashCode()}"
val mainWorkflowId = WorkflowId.random()
```

---

## Summary

The research confirms the feasibility of the testing framework design:

1. **Profile Composition**: Use inheritance + multiple TestResources per profile
2. **Synchronization**: Use real CloudEvents from `LifecycleEventHookImpl` via `CloudEventCapture`
   - Tests verify the actual event emission path
   - `WorkflowStateHooks` wraps `CloudEventCapture` for convenient `await*` methods
   - No test-specific hooks to maintain
3. **Mocking**: Extend ActivityRunner interface for test responses
4. **Event Capture**: Subscribe to messaging channel where `LifecycleEventHookImpl` emits CloudEvents
   - Same mechanism for verification AND synchronization
   - Tests see exactly what production users see
5. **Isolation**: Unique IDs per test, no cleanup required

All patterns align with existing Lemline codebase patterns and Quarkus best practices.
