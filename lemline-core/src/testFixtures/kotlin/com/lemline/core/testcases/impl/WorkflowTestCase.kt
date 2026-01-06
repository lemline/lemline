// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases.impl

import com.lemline.core.activities.mock.MockConfiguration
import io.cloudevents.CloudEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Represents a workflow that the test depends on (e.g., a child workflow
 * that will be called via `run: workflow`).
 *
 * @property yaml The workflow YAML definition
 * @property namespace The workflow namespace
 * @property name The workflow name
 * @property version The workflow version
 */
data class WorkflowDependency(
    val yaml: String,
    val namespace: String = "test",
    val name: String,
    val version: String = "0.1.0"
)

/**
 * Represents a single workflow test case that can be executed
 * by different test executors (in-memory orchestrator, real infrastructure, etc.).
 *
 * @property name Short name for the test (used as test method name)
 * @property description Detailed description of what the test verifies
 * @property yaml The workflow YAML definition
 * @property input The input to pass to the workflow
 * @property validate Custom validation function for the output (returns error message or null if valid)
 * @property tags Tags for filtering tests (e.g., "slow", "http", "fork", "external")
 * @property dependencies Workflows that need to be registered before the main workflow runs
 * @property mockConfig Mock configuration for activity execution (HTTP, script, shell mocks)
 * @property cloudEvents CloudEvents to emit before workflow execution (for listen task tests)
 */
data class WorkflowTestCase(
    val name: String,
    val description: String = name,
    val yaml: String,
    val input: JsonElement = JsonObject(emptyMap()),
    val validate: (WorkflowTestResult) -> String? = { null },
    val tags: Set<String> = emptySet(),
    val dependencies: List<WorkflowDependency> = emptyList(),
    val mockConfig: MockConfiguration = MockConfiguration.empty(),
    val cloudEvents: List<CloudEvent> = emptyList(),
    val timeout: Duration = 5.seconds
) {
    companion object
}

/**
 * Result of executing a workflow test case.
 */
sealed class WorkflowTestResult {
    /**
     * Workflow completed successfully with the given output.
     */
    data class Success(val output: JsonElement) : WorkflowTestResult()

    /**
     * Workflow failed with an error.
     */
    data class Failure(val error: String, val exception: Exception? = null) : WorkflowTestResult()
}
