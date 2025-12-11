# Quickstart Guide: End-to-End Testing Framework

**Feature**: 001-testing-architecture
**Date**: 2025-12-11

## Overview

This guide shows how to write end-to-end tests for Lemline workflows using real infrastructure (Kafka/RabbitMQ + PostgreSQL/MySQL).

---

## 1. Add Dependency

```kotlin
// build.gradle.kts
dependencies {
    testImplementation(project(":lemline-testing"))
}
```

---

## 2. Choose Your Test Profile

Select a profile based on your infrastructure:

| Profile | Broker | Database |
|---------|--------|----------|
| `KafkaPostgresProfile` | Kafka | PostgreSQL |
| `KafkaMySQLProfile` | Kafka | MySQL |
| `RabbitMQPostgresProfile` | RabbitMQ | PostgreSQL |
| `RabbitMQMySQLProfile` | RabbitMQ | MySQL |

---

## 3. Write Your First Test

### Basic Test

```kotlin
import com.lemline.testing.TestWorkflowExecutor
import com.lemline.core.testcases.WorkflowTestResult
import io.kotest.matchers.shouldBe
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(KafkaPostgresProfile::class)
class MyFirstWorkflowTest {

    @Inject
    lateinit var executor: TestWorkflowExecutor

    @Test
    fun `workflow produces expected output`() = runBlocking {
        val result = executor.execute(
            yaml = """
                do:
                  - set:
                      greeting: Hello, World!
            """.trimIndent(),
            input = buildJsonObject { }
        )

        result shouldBe WorkflowTestResult.Success(
            buildJsonObject { put("greeting", "Hello, World!") }
        )
    }
}
```

---

## 4. Mock External Activities

Use `TestConfiguration.withMocking` to mock HTTP, script, and shell calls:

```kotlin
@Test
fun `workflow with mocked HTTP call`() = runBlocking {
    val result = executor.execute(
        yaml = """
            do:
              - call: http
                with:
                  method: GET
                  endpoint:
                    uri: https://api.example.com/users/123
        """.trimIndent(),
        input = buildJsonObject { },
        config = TestConfiguration.withMocking {
            queueHttpResponse(HttpResponse.ok(
                buildJsonObject {
                    put("id", 123)
                    put("name", "Test User")
                }
            ))
        }
    )

    // Verify the HTTP call was made
    val invocations = config.activityExecutor!!.getHttpInvocations()
    invocations.size shouldBe 1
    invocations[0].url shouldBe "https://api.example.com/users/123"
    invocations[0].method shouldBe "GET"
}
```

### Mock Script Execution

```kotlin
TestConfiguration.withMocking {
    queueScriptResponse(ScriptResponse.ok(
        buildJsonObject { put("computed", 42) }
    ))
}
```

### Mock Shell Command

```kotlin
TestConfiguration.withMocking {
    queueShellResponse(ShellResponse.ok(stdout = "success"))
}
```

### Simulate Errors

```kotlin
TestConfiguration.withMocking {
    queueError(ActivityError(
        type = "network",
        message = "Connection refused"
    ))
}
```

---

## 5. Verify Emitted CloudEvents

Check lifecycle and custom events emitted during workflow execution:

```kotlin
@Test
fun `workflow emits expected events`() = runBlocking {
    val result = executor.execute(
        yaml = """
            do:
              - emit:
                  event:
                    with:
                      type: com.myapp.order.created
                      source: /orders
                      data:
                        orderId: "123"
        """.trimIndent(),
        input = buildJsonObject { }
    )

    val capture = executor.getEventCapture()

    // Check lifecycle events
    val lifecycleEvents = capture.lifecycleEvents()
    lifecycleEvents.any { it.type == LemlineEventTypes.WORKFLOW_COMPLETED } shouldBe true

    // Check custom events
    val customEvents = capture.customEvents()
    customEvents.size shouldBe 1
    customEvents[0].type shouldBe "com.myapp.order.created"
}
```

---

## 6. Test Listen Tasks (Event-Driven Workflows)

For workflows that wait for external events, use event-based synchronization.
This uses the real CloudEvents emitted by `LifecycleEventHookImpl`:

```kotlin
@Test
fun `workflow waits for and processes event`() = runBlocking {
    val hooks = executor.getStateHooks()
    val delivery = executor.getEventDelivery()

    // Start workflow (non-blocking)
    val workflowId = executor.startWorkflowAsync(
        yaml = """
            do:
              - listen:
                  to:
                    one:
                      with:
                        type: com.myapp.payment.completed
        """.trimIndent(),
        input = buildJsonObject { }
    )

    // Wait for listen task to start (via task.started CloudEvent)
    // This is deterministic - no Thread.sleep() needed!
    hooks.awaitTaskStarted(
        workflowId,
        NodePosition.parse("[0, \"listen\"]"),
        timeout = 5.seconds
    )

    // Now safely deliver the event (listen task is ready to receive)
    delivery.deliver(
        type = "com.myapp.payment.completed",
        source = "/payments/456",
        data = buildJsonObject { put("amount", 99.99) },
        targetWorkflowId = workflowId
    )

    // Wait for workflow completion (via workflow.completed CloudEvent)
    val result = hooks.awaitCompletion(workflowId, 10.seconds)
    result shouldBe instanceOf<WorkflowTestResult.Success>()
}
```

**How it works**: `WorkflowStateHooks.awaitTaskStarted()` internally waits for a
`com.lemline.task.started` CloudEvent from the real `LifecycleEventHookImpl`.
This ensures tests verify the actual event emission path.

---

## 7. Test Parent-Child Workflows

Test workflows that invoke child workflows:

```kotlin
@Test
fun `parent workflow calls child workflow`() = runBlocking {
    val result = executor.execute(
        yaml = """
            do:
              - run: workflow
                with:
                  namespace: test
                  name: child-workflow
                  version: "0.1.0"
                  input: \${ .parentData }
        """.trimIndent(),
        input = buildJsonObject { put("parentData", "from parent") },
        dependencies = listOf(
            WorkflowDependency(
                yaml = """
                    do:
                      - set:
                          childResult: "processed: \${ .input }"
                """.trimIndent(),
                namespace = "test",
                name = "child-workflow",
                version = "0.1.0"
            )
        )
    )

    result shouldBe instanceOf<WorkflowTestResult.Success>()
}
```

---

## 8. Advanced: Custom Event Synchronization

Wait for specific task events during execution:

```kotlin
@Test
fun `verify intermediate task output`() = runBlocking {
    val hooks = executor.getStateHooks()

    val workflowId = executor.startWorkflowAsync(
        yaml = """
            do:
              - set:
                  step1: value1
              - set:
                  step2: value2
        """.trimIndent(),
        input = buildJsonObject { }
    )

    // Wait for first task to complete
    val step1Output = hooks.awaitTaskCompleted(
        workflowId,
        NodePosition.parse("[0, \"set\"]"),
        timeout = 5.seconds
    )
    step1Output shouldBe buildJsonObject { put("step1", "value1") }

    // Continue to workflow completion
    val result = hooks.awaitCompletion(workflowId, 10.seconds)
    result shouldBe instanceOf<WorkflowTestResult.Success>()
}
```

---

## 9. Switching Infrastructure

Simply change the test profile to run against different infrastructure:

```kotlin
// Test with Kafka + PostgreSQL
@TestProfile(KafkaPostgresProfile::class)
class KafkaPostgresWorkflowTest { ... }

// Same tests with RabbitMQ + MySQL
@TestProfile(RabbitMQMySQLProfile::class)
class RabbitMQMySQLWorkflowTest { ... }
```

Testcontainers automatically provision the required infrastructure.

---

## 10. Common Patterns

### Pattern: Timeout Configuration

```kotlin
val result = executor.execute(
    yaml = workflowYaml,
    input = input,
    config = TestConfiguration(timeout = 60.seconds)
)
```

### Pattern: Verify No Unused Mocks

```kotlin
@Test
fun `all mocked responses are consumed`() = runBlocking {
    val config = TestConfiguration.withMocking {
        queueHttpResponse(HttpResponse.ok(data))
    }

    executor.execute(yaml, input, config)

    config.activityExecutor!!.hasUnusedResponses() shouldBe false
}
```

### Pattern: Error Handling Test

```kotlin
@Test
fun `workflow handles error correctly`() = runBlocking {
    val result = executor.execute(
        yaml = """
            do:
              - try:
                  - call: http
                    with:
                      method: GET
                      endpoint:
                        uri: https://failing.api
                catch:
                  - set:
                      recovered: true
        """.trimIndent(),
        input = buildJsonObject { },
        config = TestConfiguration.withMocking {
            queueError(ActivityError("http", "Service unavailable"))
        }
    )

    result shouldBe WorkflowTestResult.Success(
        buildJsonObject { put("recovered", true) }
    )
}
```

---

## Key Principles

1. **No Thread.sleep()** - Use event-based synchronization (`awaitCompletion`, `awaitListenStarted`, etc.)
2. **Deterministic** - Tests produce the same result every time
3. **Isolated** - Each test uses unique workflow IDs and names
4. **Profile-based** - Switch infrastructure by changing `@TestProfile`
5. **Mocking** - Control external dependencies for predictable behavior

---

## Next Steps

- See `data-model.md` for detailed entity definitions
- See `contracts/` for interface specifications
- See `spec.md` for full requirements
