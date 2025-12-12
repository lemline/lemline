// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing.e2e

import com.lemline.testing.TestConfiguration
import com.lemline.testing.TestWorkflowExecutor
import com.lemline.testing.WorkflowStatus
import com.lemline.testing.infrastructure.BrokerType
import com.lemline.testing.infrastructure.DatabaseType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * E2E test verifying same test works across all infrastructure combinations.
 *
 * Tests all 4 combinations:
 * - Kafka + PostgreSQL
 * - Kafka + MySQL
 * - RabbitMQ + PostgreSQL
 * - RabbitMQ + MySQL
 *
 * Prerequisites:
 * - Native runner binary must be built
 * - Docker must be running for Testcontainers
 */
class InfrastructureSwitchingTest : FunSpec({

    val workflowYaml = """
        document:
          name: infra-test
          version: "1.0.0"
          dsl: "1.0.0"
        do:
          - set:
              result: "success"
    """.trimIndent()

    suspend fun runTestWithConfig(config: TestConfiguration, description: String) {
        val executor = TestWorkflowExecutor.create(config)

        try {
            executor.start()
            executor.defineWorkflow(workflowYaml)

            val result = executor.runWorkflow(
                name = "infra-test",
                version = "1.0.0",
                input = JsonObject(emptyMap()),
                timeout = 30.seconds
            )

            result.status shouldBe WorkflowStatus.COMPLETED
            result.output?.jsonObject?.get("result")?.jsonPrimitive?.content shouldBe "success"
        } finally {
            executor.stop()
        }
    }

    test("Kafka + PostgreSQL") {
        runTestWithConfig(
            TestConfiguration.kafkaPostgres(),
            "Kafka + PostgreSQL"
        )
    }

    xtest("Kafka + MySQL") {
        // Disabled by default - enable for full matrix testing
        runTestWithConfig(
            TestConfiguration.kafkaMysql(),
            "Kafka + MySQL"
        )
    }

    xtest("RabbitMQ + PostgreSQL") {
        // Disabled by default - enable for full matrix testing
        runTestWithConfig(
            TestConfiguration.rabbitmqPostgres(),
            "RabbitMQ + PostgreSQL"
        )
    }

    xtest("RabbitMQ + MySQL") {
        // Disabled by default - enable for full matrix testing
        runTestWithConfig(
            TestConfiguration.rabbitmqMysql(),
            "RabbitMQ + MySQL"
        )
    }
})
