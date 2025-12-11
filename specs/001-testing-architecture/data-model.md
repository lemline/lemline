# Data Model: End-to-End Testing Framework

**Feature**: 001-testing-architecture
**Date**: 2025-12-11

## Entity Overview

**Architecture**: Native binary orchestration - tests spawn runner as external process, interact via
CLI and CloudEvents through the message broker.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           lemline-testing module                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────┐      ┌──────────────────────┐                     │
│  │ TestWorkflowExecutor│──────│  WorkflowStateHooks  │                     │
│  │ (spawns runner,     │      │ (await convenience)  │                     │
│  │  manages lifecycle) │      └──────────┬───────────┘                     │
│  └─────────┬───────────┘                 │ wraps                            │
│            │                             ▼                                  │
│            │ spawns         ┌──────────────────────┐                       │
│            │ process        │  CloudEventCapture   │◄──── subscribes to    │
│            │                │ (capture & verify)   │      broker           │
│            │                └──────────────────────┘                       │
│  ┌─────────▼───────────┐                 ▲                                 │
│  │   RunnerProcess     │                 │ routes events                   │
│  │ (native binary mgmt)│      ┌──────────┴───────────┐                     │
│  └─────────────────────┘      │ CloudEventDispatcher │ (singleton)         │
│                               │ (routes by workflowId│                     │
│  ┌─────────────────────┐      └──────────────────────┘                     │
│  │ TestConfiguration   │                 ▲                                 │
│  │ (generates configs) │                 │ subscribes (single)             │
│  └─────────────────────┘      ┌──────────┴───────────┐                     │
│                               │   Messaging Channel  │                     │
│  ┌─────────────────────┐      │ (Kafka/RabbitMQ)     │                     │
│  │  CloudEventDelivery │      └──────────────────────┘                     │
│  │ (trigger listen)    │────────────────►│                                 │
│  └─────────────────────┘      emits      │                                 │
│                                          │                                 │
│  ┌─────────────────────┐                 │                                 │
│  │    Testcontainers   │                 │                                 │
│  │ (Kafka, PostgreSQL) │                 │                                 │
│  └─────────────────────┘                 │                                 │
└──────────────────────────────────────────┼─────────────────────────────────┘
                                           │
┌──────────────────────────────────────────┼─────────────────────────────────┐
│                           lemline-runner (native binary)                    │
├──────────────────────────────────────────┼─────────────────────────────────┤
│                                          │                                 │
│                               ┌──────────▼───────────┐                     │
│                               │LifecycleEventHookImpl│                     │
│                               │ (emits CloudEvents)  │                     │
│                               └──────────────────────┘                     │
│                                          ▲                                 │
│  ┌─────────────────────┐                 │                                 │
│  │ TestActivityExecutor│─────────────────┘                                 │
│  │ (--test-mode flag)  │  returns mock responses                           │
│  │ (--mock-config file)│                                                   │
│  └─────────────────────┘                                                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Test Isolation Flow**: `CloudEventDispatcher` (singleton) subscribes once to the message broker.
When events arrive, it extracts the workflowId and routes only to `CloudEventCapture` instances
registered for that workflowId. This ensures concurrent tests don't interfere with each other.

---

## Core Entities

### 1. TestWorkflowExecutor (in lemline-testing)

**Purpose**: Orchestrates end-to-end test execution by spawning native runner binary and managing
infrastructure lifecycle.

```kotlin
interface TestWorkflowExecutor {
    /** Access to CloudEventCapture for reading events from broker. */
    val cloudEventCapture: CloudEventCapture

    /** Access to CloudEventDelivery for emitting events to broker. */
    val cloudEventDelivery: CloudEventDelivery

    /** Access to WorkflowStateHooks for deterministic await utilities. */
    val stateHooks: WorkflowStateHooks

    /** Start infrastructure (Testcontainers) and spawn native runner. */
    suspend fun start()

    /** Stop runner process and infrastructure containers. */
    suspend fun stop()

    /** Define a workflow via CLI command. */
    suspend fun defineWorkflow(yaml: String)

    /** Run a workflow and wait for completion. */
    suspend fun runWorkflow(
        name: String,
        version: String,
        input: JsonElement,
        mocks: MockConfig? = null,
        timeout: Duration = 30.seconds
    ): WorkflowResult

    /** Start a workflow without waiting for completion. */
    suspend fun startWorkflowAsync(
        name: String,
        version: String,
        input: JsonElement,
        mocks: MockConfig? = null
    ): String

    companion object {
        fun create(
            broker: BrokerType,
            database: DatabaseType,
            runnerBinaryPath: String? = null
        ): TestWorkflowExecutor
    }
}
```

**Relationships**:
- Spawns `RunnerProcess` (native binary with `--test-mode`)
- Manages `Testcontainers` (broker + database)
- Provides `WorkflowStateHooks` for event-based synchronization
- Provides `CloudEventCapture` for event verification
- Provides `CloudEventDelivery` for triggering listen tasks

---

### 2. TestActivityExecutor (in lemline-runner)

**Purpose**: Built into runner, activated via `--test-mode` CLI flag. Returns mock responses
loaded from `--mock-config` file.

```kotlin
/**
 * Implements ActivityExecutor interface. Returns mock responses from configuration.
 * Activated when runner starts with `--test-mode` flag.
 */
class TestActivityExecutor(
    private val mockConfig: MockConfiguration
) : ActivityExecutor {

    override suspend fun execute(event: ActivityStarted): JsonElement = when (event) {
        is EmitStarted -> executeEmit(event)
        is CallHttpStarted -> executeHttp(event.config)
        is RunScriptStarted -> executeScript(event.config)
        is RunShellStarted -> executeShell(event.config)
    }
}

/**
 * Mock configuration loaded from --mock-config file (YAML/JSON).
 */
data class MockConfiguration(
    val httpMocks: List<HttpMockRule> = emptyList(),
    val scriptMocks: List<ScriptMockRule> = emptyList(),
    val shellMocks: List<ShellMockRule> = emptyList()
) {
    companion object {
        fun fromYaml(path: String): MockConfiguration
    }
}

// Mock rule types
data class HttpMockRule(
    val match: HttpMockMatcher,  // url pattern, method
    val response: HttpMockResponse  // status, body, error
)

data class ScriptMockRule(
    val match: ScriptMockMatcher,  // language
    val response: ScriptMockResponse  // output, exitCode
)

data class ShellMockRule(
    val match: ShellMockMatcher,  // command pattern
    val response: ShellMockResponse  // stdout, stderr, exitCode
)
```

**Mock Configuration Format** (YAML):
```yaml
http:
  - match:
      url: "*api.example.com*"
      method: GET
    response:
      status: 200
      body: { "id": 123 }

shell:
  - match:
      command: "echo*"
    response:
      stdout: "mocked"
      exitCode: 0
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

## Infrastructure Configuration

### 7. BrokerType and DatabaseType

**Purpose**: Configuration enums for selecting test infrastructure. No Quarkus test profiles -
infrastructure is selected via `TestWorkflowExecutor.create()` parameters.

```kotlin
enum class BrokerType {
    KAFKA,
    RABBITMQ
}

enum class DatabaseType {
    POSTGRESQL,
    MYSQL
}
```

**Usage**:
```kotlin
val executor = TestWorkflowExecutor.create(
    broker = BrokerType.KAFKA,
    database = DatabaseType.POSTGRESQL
)
```

**4 Infrastructure Combinations**:

| Broker | Database | Testcontainers |
|--------|----------|----------------|
| KAFKA | POSTGRESQL | KafkaContainer, PostgreSQLContainer |
| KAFKA | MYSQL | KafkaContainer, MySQLContainer |
| RABBITMQ | POSTGRESQL | RabbitMQContainer, PostgreSQLContainer |
| RABBITMQ | MYSQL | RabbitMQContainer, MySQLContainer |

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
