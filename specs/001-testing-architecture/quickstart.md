# Quickstart Guide: End-to-End Testing Framework

**Feature**: 001-testing-architecture
**Date**: 2025-12-11

## Overview

This guide shows how to write end-to-end tests for Lemline workflows using the native runner binary with real
infrastructure (Kafka/RabbitMQ + PostgreSQL/MySQL).

**Architecture**: Tests spawn the native-compiled runner as an external process, interact via CloudEvents through the
message broker.

---

## 1. Add Dependency

```kotlin
// build.gradle.kts
dependencies {
    testImplementation(project(":lemline-testing"))
}
```

---

## 2. Write Your First Test

### Basic Test

```kotlin
import com.lemline.testing.TestWorkflowExecutor
import com.lemline.testing.infrastructure.BrokerType
import com.lemline.testing.infrastructure.DatabaseType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MyFirstWorkflowTest : FunSpec({

    lateinit var executor: TestWorkflowExecutor

    beforeSpec {
        // Start infrastructure (Testcontainers) and native runner
        executor = TestWorkflowExecutor.create(
            broker = BrokerType.KAFKA,
            database = DatabaseType.POSTGRESQL
        )
        executor.start()
    }

    afterSpec {
        executor.stop()
    }

    test("workflow produces expected output") {
        // Define workflow via CLI
        executor.defineWorkflow(
            yaml = """
                document:
                  name: my-test-workflow
                  version: "1.0.0"
                  dsl: "1.0.0"
                do:
                  - set:
                      greeting: Hello, World!
            """.trimIndent()
        )

        // Start workflow via CLI and wait for completion
        val result = executor.runWorkflow(
            name = "my-test-workflow",
            version = "1.0.0",
            input = buildJsonObject { }
        )

        result.output shouldBe buildJsonObject { put("greeting", "Hello, World!") }
    }
})
```

---

## 3. Mock External Activities

Create a mock configuration file that the runner loads via `--mock-config`:

```kotlin
test("workflow with mocked HTTP call") {
    // Define mocks that runner will use
    val mocks = MockConfig {
        http {
            match { url contains "api.example.com" }
            respond {
                status = 200
                body = buildJsonObject {
                    put("id", 123)
                    put("name", "Test User")
                }
            }
        }
    }

    executor.defineWorkflow(
        yaml = """
            document:
              name: http-test
              version: "1.0.0"
              dsl: "1.0.0"
            do:
              - call: http
                with:
                  method: GET
                  endpoint:
                    uri: https://api.example.com/users/123
        """.trimIndent()
    )

    // Run with mocks - runner spawned with --mock-config=<path>
    val result = executor.runWorkflow(
        name = "http-test",
        version = "1.0.0",
        input = buildJsonObject { },
        mocks = mocks
    )

    result.output["id"]?.jsonPrimitive?.int shouldBe 123
}
```

### Mock Script Execution

```kotlin
MockConfig {
    script {
        match { language == "javascript" }
        respond { output = buildJsonObject { put("computed", 42) } }
    }
}
```

### Mock Shell Command

```kotlin
MockConfig {
    shell {
        match { command startsWith "echo" }
        respond { stdout = "mocked output"; exitCode = 0 }
    }
}
```

### Simulate Errors

```kotlin
MockConfig {
    http {
        match { url contains "failing.api" }
        respond { status = 500; error = "Service unavailable" }
    }
}
```

---

## 4. Verify Emitted CloudEvents

Use `CloudEventCapture` to read events from the broker:

```kotlin
test("workflow emits expected events") {
    val capture = executor.cloudEventCapture

    executor.defineWorkflow(
        yaml = """
            document:
              name: emit-test
              version: "1.0.0"
              dsl: "1.0.0"
            do:
              - emit:
                  event:
                    with:
                      type: com.myapp.order.created
                      source: /orders
                      data:
                        orderId: "123"
        """.trimIndent()
    )

    executor.runWorkflow(
        name = "emit-test",
        version = "1.0.0",
        input = buildJsonObject { }
    )

    // Query captured events from broker
    val lifecycleEvents = capture.filter { it.type.startsWith("com.lemline.") }
    lifecycleEvents.any { it.type == "com.lemline.workflow.completed" } shouldBe true

    val customEvents = capture.filter { it.type == "com.myapp.order.created" }
    customEvents.size shouldBe 1
    customEvents[0].source shouldBe "/orders"
}
```

---

## 5. Test Listen Tasks (Event-Driven Workflows)

Use `CloudEventDelivery` to emit events that trigger `listen` tasks:

```kotlin
test("workflow waits for and processes event") {
    val hooks = executor.stateHooks
    val delivery = executor.cloudEventDelivery

    executor.defineWorkflow(
        yaml = """
            document:
              name: listen-test
              version: "1.0.0"
              dsl: "1.0.0"
            do:
              - listen:
                  to:
                    one:
                      with:
                        type: com.myapp.payment.completed
        """.trimIndent()
    )

    // Start workflow (non-blocking) via CLI
    val workflowId = executor.startWorkflowAsync(
        name = "listen-test",
        version = "1.0.0",
        input = buildJsonObject { }
    )

    // Wait for listen task to start (via captured CloudEvent)
    // This is deterministic - no Thread.sleep() needed!
    hooks.awaitTaskStarted(
        workflowId = workflowId,
        taskName = "listen",
        timeout = 5.seconds
    )

    // Deliver event to broker - runner will receive it
    delivery.emit(
        type = "com.myapp.payment.completed",
        source = "/payments/456",
        data = buildJsonObject { put("amount", 99.99) }
    )

    // Wait for workflow completion (via captured CloudEvent)
    val result = hooks.awaitWorkflowCompleted(workflowId, timeout = 10.seconds)
    result.status shouldBe WorkflowStatus.COMPLETED
}
```

---

## 6. Test Parent-Child Workflows

```kotlin
test("parent workflow calls child workflow") {
    // Define child workflow first
    executor.defineWorkflow(
        yaml = """
            document:
              name: child-workflow
              version: "1.0.0"
              dsl: "1.0.0"
            do:
              - set:
                  childResult: "processed"
        """.trimIndent()
    )

    // Define parent workflow
    executor.defineWorkflow(
        yaml = """
            document:
              name: parent-workflow
              version: "1.0.0"
              dsl: "1.0.0"
            do:
              - run: workflow
                with:
                  name: child-workflow
                  version: "1.0.0"
        """.trimIndent()
    )

    val result = executor.runWorkflow(
        name = "parent-workflow",
        version = "1.0.0",
        input = buildJsonObject { }
    )

    result.status shouldBe WorkflowStatus.COMPLETED
}
```

---

## 7. Switching Infrastructure

Change broker/database by creating executor with different types:

```kotlin
class KafkaPostgresTest : FunSpec({
    val executor = TestWorkflowExecutor.create(
        broker = BrokerType.KAFKA,
        database = DatabaseType.POSTGRESQL
    )
    // ... tests
})

class RabbitMQMySQLTest : FunSpec({
    val executor = TestWorkflowExecutor.create(
        broker = BrokerType.RABBITMQ,
        database = DatabaseType.MYSQL
    )
    // ... same tests work with different infrastructure
})
```

Testcontainers automatically provision the required infrastructure.

---

## 8. Common Patterns

### Pattern: Timeout Configuration

```kotlin
val result = executor.runWorkflow(
    name = "slow-workflow",
    version = "1.0.0",
    input = input,
    timeout = 60.seconds
)
```

### Pattern: Custom Runner Binary Path

```kotlin
val executor = TestWorkflowExecutor.create(
    broker = BrokerType.KAFKA,
    database = DatabaseType.POSTGRESQL,
    runnerBinaryPath = "/custom/path/lemline-runner"
)
```

### Pattern: Error Handling Test

```kotlin
test("workflow handles error correctly") {
    val mocks = MockConfig {
        http {
            match { url contains "failing.api" }
            respond { status = 500; error = "Service unavailable" }
        }
    }

    executor.defineWorkflow(
        yaml = """
            document:
              name: error-handling-test
              version: "1.0.0"
              dsl: "1.0.0"
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
        """.trimIndent()
    )

    val result = executor.runWorkflow(
        name = "error-handling-test",
        version = "1.0.0",
        input = buildJsonObject { },
        mocks = mocks
    )

    result.output["recovered"]?.jsonPrimitive?.boolean shouldBe true
}
```

---

## Key Principles

1. **Native Binary** - Tests spawn the real native-compiled runner
2. **CLI-Driven** - Define workflows via `definition` CLI, start via `instance` CLI
3. **CloudEvent Interaction** - Read events via `CloudEventCapture`, emit via `CloudEventDelivery`
4. **No Thread.sleep()** - Use `WorkflowStateHooks.await*` methods for deterministic sync
5. **Isolated** - Each test uses unique workflow IDs
6. **Infrastructure Switching** - Change `BrokerType`/`DatabaseType` to test different combinations

---

## Next Steps

- See `data-model.md` for detailed entity definitions
- See `contracts/` for interface specifications
- See `spec.md` for full requirements
