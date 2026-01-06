package com.lemline.core.testcases.impl

import com.lemline.core.activities.mock.MockConfiguration
import io.cloudevents.CloudEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Interface for executing workflow test cases.
 *
 * Implementations can use different execution strategies:
 * - In-memory orchestrator (FullOrchestrator) for fast unit tests
 * - Real messaging infrastructure (Kafka/RabbitMQ + Postgres) for E2E tests
 */
interface WorkflowTestExecutor {

    /**
     * Execute a workflow and return the result.
     *
     * @param yaml The workflow YAML definition
     * @param input The input to pass to the workflow
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @param dependencies Workflows that need to be registered before execution
     * @param mockConfig Mock configuration for activity execution
     * @param cloudEvents CloudEvents to emit before workflow execution (for listen task tests)
     * @return The result of the workflow execution
     */
    suspend fun execute(
        yaml: String,
        input: JsonElement = JsonObject(emptyMap()),
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0",
        dependencies: List<WorkflowDependency> = emptyList(),
        mockConfig: MockConfiguration = MockConfiguration.Companion.empty(),
        cloudEvents: List<CloudEvent> = emptyList()
    ): WorkflowTestResult
}
