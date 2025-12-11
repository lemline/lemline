# Data Model: End-to-End Testing Framework

**Feature**: 001-testing-architecture
**Date**: 2025-12-11

## Entity Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           lemline-testing module                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────┐      ┌──────────────────────┐                     │
│  │ TestWorkflowExecutor│──────│  WorkflowStateHooks  │                     │
│  │ (orchestrates test) │      │ (await convenience)  │                     │
│  └─────────┬───────────┘      └──────────┬───────────┘                     │
│            │                             │ wraps                            │
│            │ uses                        ▼                                  │
│            ▼                  ┌──────────────────────┐                     │
│  ┌─────────────────────┐      │  CloudEventCapture   │◄──── scoped by      │
│  │ TestActivityExecutor│      │ (capture & verify)   │      workflowId     │
│  │ (activity mocking)  │      └──────────────────────┘                     │
│  └─────────────────────┘                 ▲                                  │
│                                          │ routes events                    │
│            │                  ┌──────────┴───────────┐                     │
│            │                  │ CloudEventDispatcher │ (singleton)         │
│  ┌─────────▼───────────┐      │ (routes by workflowId│                     │
│  │ TestConfiguration   │      └──────────────────────┘                     │
│  │ (test settings)     │                 ▲                                  │
│  └─────────────────────┘                 │ subscribes (single)              │
│                               ┌──────────┴───────────┐                     │
│  ┌─────────────────────┐      │   Messaging Channel  │                     │
│  │  CloudEventDelivery │      │ (lifecycle events)   │                     │
│  │ (trigger listen)    │      └──────────────────────┘                     │
│  └─────────────────────┘                 ▲                                  │
│                                          │ emits                            │
│                               ┌──────────┴───────────┐                     │
│                               │LifecycleEventHookImpl│                     │
│                               │ (lemline-runner)     │                     │
│                               └──────────────────────┘                     │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────┐       │
│  │                    Composable Test Profiles                      │       │
│  │  ┌───────────────┐  ┌───────────────┐  ┌───────────────────┐    │       │
│  │  │ KafkaProfile  │  │PostgresProfile│  │ KafkaPostgresProf │    │       │
│  │  └───────────────┘  └───────────────┘  └───────────────────┘    │       │
│  │  ┌───────────────┐  ┌───────────────┐  ┌───────────────────┐    │       │
│  │  │RabbitMQProfile│  │ MySQLProfile  │  │ ...other combos   │    │       │
│  │  └───────────────┘  └───────────────┘  └───────────────────┘    │       │
│  └─────────────────────────────────────────────────────────────────┘       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Test Isolation Flow**: `CloudEventDispatcher` (singleton) subscribes once to the lifecycle
events channel. When events arrive, it extracts the workflowId and routes only to
`CloudEventCapture` instances registered for that workflowId. This ensures concurrent tests
don't interfere with each other.

---

## Core Entities

### 1. TestWorkflowExecutor

**Purpose**: Orchestrates end-to-end test execution with real infrastructure.

```kotlin
interface TestWorkflowExecutor {
    /**
     * Execute a workflow test case and return the result.
     *
     * @param testCase The workflow test case to execute
     * @param config Test configuration (timeouts, mocks)
     * @return WorkflowTestResult (Success or Failure)
     */
    suspend fun execute(
        testCase: WorkflowTestCase,
        config: TestConfiguration = TestConfiguration.default()
    ): WorkflowTestResult

    /**
     * Execute a workflow from YAML with custom input.
     *
     * @param yaml Workflow definition YAML
     * @param input Workflow input as JsonElement
     * @param config Test configuration
     * @return WorkflowTestResult
     */
    suspend fun execute(
        yaml: String,
        input: JsonElement,
        config: TestConfiguration = TestConfiguration.default()
    ): WorkflowTestResult
}
```

**Relationships**:
- Uses `WorkflowStateHooks` for event-based synchronization
- Uses `TestActivityExecutor` for activity mocking
- Uses `CloudEventCapture` for event verification
- Configured via `TestConfiguration`

---

### 2. TestActivityExecutor

**Purpose**: Intercepts activity calls and returns configured responses for deterministic testing.

```kotlin
interface TestActivityExecutor {
    /**
     * Queue a response for the next HTTP call.
     */
    fun queueHttpResponse(response: HttpResponse)

    /**
     * Queue a response for the next script execution.
     */
    fun queueScriptResponse(response: ScriptResponse)

    /**
     * Queue a response for the next shell execution.
     */
    fun queueShellResponse(response: ShellResponse)

    /**
     * Queue an error for the next activity call (any type).
     */
    fun queueError(error: ActivityError)

    /**
     * Get all activity invocations for verification.
     */
    fun getInvocations(): List<ActivityInvocation>

    /**
     * Clear all queued responses and recorded invocations.
     */
    fun reset()
}

// Response types
data class HttpResponse(
    val statusCode: Int = 200,
    val body: JsonElement = JsonObject(emptyMap()),
    val headers: Map<String, String> = emptyMap()
)

data class ScriptResponse(
    val output: JsonElement,
    val exitCode: Int = 0
)

data class ShellResponse(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0
)

data class ActivityError(
    val type: String,
    val message: String,
    val details: JsonElement? = null
)

// Invocation tracking
sealed class ActivityInvocation {
    abstract val timestamp: Instant

    data class HttpInvocation(
        override val timestamp: Instant,
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: JsonElement?
    ) : ActivityInvocation()

    data class ScriptInvocation(
        override val timestamp: Instant,
        val language: String,
        val code: String,
        val arguments: Map<String, JsonElement>
    ) : ActivityInvocation()

    data class ShellInvocation(
        override val timestamp: Instant,
        val command: String,
        val arguments: List<String>,
        val environment: Map<String, String>
    ) : ActivityInvocation()
}
```

---

### 3. CloudEventCapture

**Purpose**: Captures and queries CloudEvents emitted during workflow execution.

**Test Isolation**: Each CloudEventCapture instance is **scoped to specific workflow IDs**.
Events from other tests' workflows are automatically filtered out, enabling concurrent test execution.

```kotlin
interface CloudEventCapture {
    /**
     * Get all captured events in order of emission.
     * Only returns events from scoped workflow IDs.
     */
    fun events(): List<CloudEvent>

    /**
     * Filter events by CloudEvent type (e.g., "com.lemline.workflow.completed").
     */
    fun filterByType(type: String): List<CloudEvent>

    /**
     * Filter events by source URI.
     */
    fun filterBySource(source: URI): List<CloudEvent>

    /**
     * Find events matching a predicate.
     */
    fun find(predicate: (CloudEvent) -> Boolean): List<CloudEvent>

    /**
     * Get lifecycle events only (workflow/task created/started/completed/faulted).
     */
    fun lifecycleEvents(): List<CloudEvent>

    /**
     * Get custom events only (emitted via emit task).
     */
    fun customEvents(): List<CloudEvent>

    /**
     * Wait for an event matching the predicate (with timeout).
     */
    suspend fun awaitEvent(
        timeout: Duration,
        predicate: (CloudEvent) -> Boolean
    ): CloudEvent?

    /**
     * Get workflow IDs this capture is scoped to.
     */
    fun scopedWorkflowIds(): Set<WorkflowId>

    /**
     * Add a workflow ID to scope (for child workflows).
     */
    fun addToScope(workflowId: WorkflowId)

    /**
     * Clear all captured events.
     */
    fun clear()
}
```

**CloudEvent Structure** (from io.cloudevents.CloudEvent):
```kotlin
// Standard CloudEvents attributes
interface CloudEvent {
    val id: String           // Unique event ID
    val source: URI          // Event source (e.g., "/lemline/workflows/{id}")
    val type: String         // Event type (e.g., "com.lemline.task.completed")
    val time: OffsetDateTime // Event timestamp
    val data: ByteArray?     // Event payload
    // ... other standard attributes
}
```

---

### 4. CloudEventDelivery

**Purpose**: Programmatically delivers CloudEvents to trigger listen tasks.

```kotlin
interface CloudEventDelivery {
    /**
     * Deliver a CloudEvent to trigger a waiting listen task.
     *
     * @param event The CloudEvent to deliver
     * @param targetWorkflowId Optional: target specific workflow instance
     */
    suspend fun deliver(event: CloudEvent, targetWorkflowId: WorkflowId? = null)

    /**
     * Deliver a CloudEvent built from parameters.
     */
    suspend fun deliver(
        type: String,
        source: String,
        data: JsonElement,
        targetWorkflowId: WorkflowId? = null
    )

    /**
     * Deliver multiple events at once (for "all" listen strategy).
     */
    suspend fun deliverAll(events: List<CloudEvent>, targetWorkflowId: WorkflowId? = null)
}
```

---

### 5. WorkflowStateHooks

**Purpose**: Event-based synchronization for deterministic test assertions.

**DESIGN DECISION**: WorkflowStateHooks wraps CloudEventCapture instead of using separate callbacks.

This leverages the existing lifecycle event infrastructure:
1. `LifecycleEventHook` (interface in lemline-core) - called by orchestrator
2. `LifecycleEventHookImpl` (in lemline-runner) - builds and emits CloudEvents
3. `CloudEventCapture` (in lemline-testing) - subscribes and captures events
4. `WorkflowStateHooks` - provides convenient await methods over CloudEventCapture

**Benefits**:
- Tests verify the real event emission path
- No test-specific hooks to maintain
- Tests see exactly what production users see

```kotlin
interface WorkflowStateHooks {
    /**
     * Wait for workflow completion (success or failure).
     * Internally waits for workflow.completed or workflow.faulted CloudEvent.
     */
    suspend fun awaitCompletion(workflowId: WorkflowId, timeout: Duration): WorkflowTestResult

    /**
     * Wait for a specific task to complete.
     * Internally waits for task.completed CloudEvent.
     */
    suspend fun awaitTaskCompleted(
        workflowId: WorkflowId,
        taskPosition: NodePosition,
        timeout: Duration
    ): JsonElement

    /**
     * Wait for a task to start.
     * Internally waits for task.started CloudEvent.
     * Useful for synchronizing event delivery to listen tasks.
     */
    suspend fun awaitTaskStarted(
        workflowId: WorkflowId,
        taskPosition: NodePosition,
        timeout: Duration
    )

    /**
     * Wait for any of multiple CloudEvent types to occur.
     */
    suspend fun awaitAnyEvent(
        workflowId: WorkflowId,
        eventTypes: Set<String>,
        timeout: Duration
    ): CloudEvent

    /**
     * Get the underlying CloudEventCapture for direct event access.
     */
    fun getEventCapture(): CloudEventCapture

    /**
     * Clear all tracked state.
     */
    fun reset()
}
```

---

### 6. TestConfiguration

**Purpose**: Configures test execution parameters.

```kotlin
data class TestConfiguration(
    /**
     * Maximum time to wait for workflow completion.
     */
    val timeout: Duration = 30.seconds,

    /**
     * Activity executor for mocking external calls.
     * If null, uses real activity execution.
     */
    val activityExecutor: TestActivityExecutor? = null,

    /**
     * CloudEvent capture for event verification.
     * Created automatically if not provided.
     */
    val eventCapture: CloudEventCapture? = null,

    /**
     * CloudEvent delivery for triggering listen tasks.
     * Created automatically if not provided.
     */
    val eventDelivery: CloudEventDelivery? = null,

    /**
     * Tags to exclude from test execution.
     */
    val excludeTags: Set<String> = emptySet(),

    /**
     * Platform filter (e.g., "unix-only", "windows-only").
     * Tests with non-matching platform tags are skipped.
     */
    val platform: String = detectPlatform()
) {
    companion object {
        fun default() = TestConfiguration()

        fun withMocking(block: TestActivityExecutor.() -> Unit): TestConfiguration {
            val executor = DefaultTestActivityExecutor()
            executor.block()
            return TestConfiguration(activityExecutor = executor)
        }
    }
}
```

---

## Test Profile Entities

### 7. Composable Profiles

**Base Profile**:
```kotlin
abstract class BaseBrokerTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            ORCHESTRATOR_MODE to "all",
            "lemline.outbox.enabled" to "true",
            "lemline.outbox.wait.outbox.every" to "1s",
            "lemline.outbox.wait.outbox.initial-delay" to "1s",
            "lemline.outbox.retry.outbox.every" to "1s",
            "lemline.outbox.retry.outbox.initial-delay" to "1s"
        )
    }
}
```

**Concrete Profiles** (4 combinations):

| Profile | Database | Broker | TestResources |
|---------|----------|--------|---------------|
| `KafkaPostgresProfile` | PostgreSQL | Kafka | KafkaTestResource, PostgresTestResource |
| `KafkaMySQLProfile` | MySQL | Kafka | KafkaTestResource, MySQLTestResource |
| `RabbitMQPostgresProfile` | PostgreSQL | RabbitMQ | RabbitMQTestResource, PostgresTestResource |
| `RabbitMQMySQLProfile` | MySQL | RabbitMQ | RabbitMQTestResource, MySQLTestResource |

---

## Existing Entities (from lemline-core)

### WorkflowTestCase

```kotlin
// From lemline-core testFixtures
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

### WorkflowDependency

```kotlin
// From lemline-core testFixtures
data class WorkflowDependency(
    val yaml: String,
    val namespace: String = "test",
    val name: String,
    val version: String = "0.1.0"
)
```

---

## Entity Relationships

```
WorkflowTestCase ─────────────────┐
        │                         │
        │ executed by             │ depends on
        ▼                         ▼
TestWorkflowExecutor ───────> WorkflowDependency
        │
        ├── uses ──> TestActivityExecutor
        │                   │
        │                   └── tracks ──> ActivityInvocation
        │
        ├── uses ──> WorkflowStateHooks
        │                   │
        │                   └── wraps ──> CloudEventCapture
        │                                       │
        │                                       ├── stores ──> CloudEvent
        │                                       │
        │                                       └── subscribes to ──> MessagingChannel
        │                                                                   │
        │                                                     emits from ──┘
        │                                               LifecycleEventHookImpl
        │
        └── uses ──> CloudEventDelivery
                            │
                            └── delivers ──> CloudEvent ──> MessagingChannel

TestConfiguration
        │
        └── configures all above entities
```

**Key Design**: WorkflowStateHooks provides convenient `await*` methods that internally
wait for specific CloudEvents captured by CloudEventCapture. This means tests verify
the real event emission path through LifecycleEventHookImpl.

---

## State Transitions

### Workflow Test Execution States

```
┌──────────┐     ┌───────────┐     ┌─────────────┐     ┌───────────┐
│ PENDING  │ ──> │ EXECUTING │ ──> │  WAITING    │ ──> │ COMPLETED │
└──────────┘     └───────────┘     │ (for event) │     └───────────┘
                      │            └─────────────┘           │
                      │                   │                  │
                      ▼                   ▼                  ▼
                ┌─────────┐         ┌─────────┐        ┌─────────┐
                │ TIMEOUT │         │ TIMEOUT │        │ SUCCESS │
                └─────────┘         └─────────┘        └─────────┘
                      │                   │                  │
                      ▼                   ▼                  │
                ┌─────────────────────────────────────┐     │
                │              FAILED                 │ <───┘
                └─────────────────────────────────────┘
```

### Listen Task Synchronization Flow

```
1. Test starts workflow ──> workflow executes ──> listen task created
                                                        │
2. Test calls CloudEventDelivery.deliver() <────────────┘
        │
        ▼
3. Event delivered to listen task ──> workflow resumes ──> continues execution
```
