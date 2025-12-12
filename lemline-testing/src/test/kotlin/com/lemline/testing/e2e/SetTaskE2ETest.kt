// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing.e2e

import com.lemline.testing.TestWorkflowExecutor
import com.lemline.testing.WorkflowStatus
import com.lemline.testing.infrastructure.BrokerType
import com.lemline.testing.infrastructure.DatabaseType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

/**
 * E2E test for basic Set task execution.
 *
 * This test verifies that:
 * 1. Testcontainers infrastructure starts correctly (Kafka + PostgreSQL)
 * 2. Native runner binary can be spawned
 * 3. Workflow can be defined via CLI
 * 4. Workflow executes and completes
 * 5. Output is captured correctly via CloudEvents
 *
 * Prerequisites:
 * - Native runner binary must be built: ./gradlew :lemline-runner:build
 * - Docker must be running for Testcontainers
 */
class SetTaskE2ETest : FunSpec({

    lateinit var executor: TestWorkflowExecutor

    beforeSpec {
        executor = TestWorkflowExecutor.create(
            broker = BrokerType.KAFKA,
            database = DatabaseType.POSTGRESQL
        )
        executor.start()
    }

    afterSpec {
        executor.stop()
    }

    test("simple set task produces expected output") {
        // Define a minimal workflow with a set task
        executor.defineWorkflow(
            yaml = """
                document:
                  name: set-test
                  version: "1.0.0"
                  dsl: "1.0.0"
                do:
                  - set:
                      greeting: "Hello, World!"
                      answer: 42
            """.trimIndent()
        )

        // Run workflow and wait for completion
        val result = executor.runWorkflow(
            name = "set-test",
            version = "1.0.0",
            input = JsonObject(emptyMap()),
            timeout = 30.seconds
        )

        // Verify result
        result.status shouldBe WorkflowStatus.COMPLETED
        result.output?.jsonObject?.get("greeting")?.jsonPrimitive?.content shouldBe "Hello, World!"
        result.output?.jsonObject?.get("answer")?.jsonPrimitive?.content shouldBe "42"
    }

    test("set task with input transformation") {
        executor.defineWorkflow(
            yaml = """
                document:
                  name: set-input-test
                  version: "1.0.0"
                  dsl: "1.0.0"
                do:
                  - set:
                      message: "${'$'}{ .name } says hello"
            """.trimIndent()
        )

        val result = executor.runWorkflow(
            name = "set-input-test",
            version = "1.0.0",
            input = buildJsonObject { put("name", "Alice") },
            timeout = 30.seconds
        )

        result.status shouldBe WorkflowStatus.COMPLETED
        result.output?.jsonObject?.get("message")?.jsonPrimitive?.content shouldBe "Alice says hello"
    }

    test("multiple set tasks in sequence") {
        executor.defineWorkflow(
            yaml = """
                document:
                  name: multi-set-test
                  version: "1.0.0"
                  dsl: "1.0.0"
                do:
                  - set:
                      step1: "first"
                  - set:
                      step2: "second"
                  - set:
                      final: "${'$'}{ .step1 } and ${'$'}{ .step2 }"
            """.trimIndent()
        )

        val result = executor.runWorkflow(
            name = "multi-set-test",
            version = "1.0.0",
            input = JsonObject(emptyMap()),
            timeout = 30.seconds
        )

        result.status shouldBe WorkflowStatus.COMPLETED
        result.output?.jsonObject?.get("final")?.jsonPrimitive?.content shouldBe "first and second"
    }
})
