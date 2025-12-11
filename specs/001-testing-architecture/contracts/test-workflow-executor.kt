// SPDX-License-Identifier: BUSL-1.1
/**
 * TestWorkflowExecutor Contract
 *
 * This file defines the contract for the TestWorkflowExecutor interface.
 * Implementation will be in lemline-testing module.
 *
 * @feature 001-testing-architecture
 */
package com.lemline.testing

import com.lemline.core.testcases.WorkflowTestCase
import com.lemline.core.testcases.WorkflowTestResult
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/**
 * Orchestrates end-to-end workflow test execution with real infrastructure.
 *
 * This executor manages the full lifecycle of a workflow test:
 * 1. Register workflow definition(s) in the database
 * 2. Set up event-based synchronization hooks
 * 3. Send initial command to start workflow
 * 4. Wait for workflow completion using deterministic callbacks
 * 5. Return result with captured output or error details
 *
 * ## Usage
 *
 * ```kotlin
 * @QuarkusTest
 * @TestProfile(KafkaPostgresProfile::class)
 * class MyWorkflowTest {
 *     @Inject
 *     lateinit var executor: TestWorkflowExecutor
 *
 *     @Test
 *     fun `my workflow produces expected output`() = runBlocking {
 *         val result = executor.execute(
 *             yaml = """
 *                 do:
 *                   - set:
 *                       answer: 42
 *             """.trimIndent(),
 *             input = buildJsonObject { }
 *         )
 *
 *         result shouldBe WorkflowTestResult.Success(
 *             buildJsonObject { put("answer", 42) }
 *         )
 *     }
 * }
 * ```
 *
 * ## Thread Safety
 *
 * TestWorkflowExecutor is thread-safe and supports concurrent test execution.
 * Each workflow instance is isolated via unique workflow IDs and names.
 *
 * ## Configuration
 *
 * Use [TestConfiguration] to customize:
 * - Timeout duration
 * - Activity mocking via [TestActivityExecutor]
 * - CloudEvent capture and delivery
 * - Platform and tag filtering
 */
interface TestWorkflowExecutor {

    /**
     * Execute a [WorkflowTestCase] from lemline-core testFixtures.
     *
     * This method provides compatibility with existing test cases,
     * enabling reuse of lemline-core tests against real infrastructure.
     *
     * @param testCase The workflow test case to execute
     * @param config Test configuration (optional, uses defaults if not provided)
     * @return [WorkflowTestResult.Success] with output, or [WorkflowTestResult.Failure] with error
     * @throws IllegalStateException if infrastructure is not properly configured
     */
    suspend fun execute(
        testCase: WorkflowTestCase,
        config: TestConfiguration = TestConfiguration.default()
    ): WorkflowTestResult

    /**
     * Execute a workflow from raw YAML definition.
     *
     * This is the primary method for writing new end-to-end tests.
     * The workflow YAML should NOT include the document header (namespace, name, version)
     * as these are generated automatically to ensure test isolation.
     *
     * @param yaml Workflow definition YAML (without document header)
     * @param input Workflow input as JsonElement
     * @param config Test configuration
     * @return [WorkflowTestResult.Success] with output, or [WorkflowTestResult.Failure] with error
     *
     * @sample
     * ```kotlin
     * val result = executor.execute(
     *     yaml = """
     *         do:
     *           - call: http
     *             with:
     *               method: GET
     *               endpoint:
     *                 uri: https://api.example.com/data
     *     """.trimIndent(),
     *     input = buildJsonObject { put("userId", "123") },
     *     config = TestConfiguration.withMocking {
     *         queueHttpResponse(HttpResponse(
     *             statusCode = 200,
     *             body = buildJsonObject { put("name", "Test User") }
     *         ))
     *     }
     * )
     * ```
     */
    suspend fun execute(
        yaml: String,
        input: JsonElement,
        config: TestConfiguration = TestConfiguration.default()
    ): WorkflowTestResult

    /**
     * Execute a workflow with dependencies (child workflows).
     *
     * Use this when testing parent-child workflow relationships via `run: workflow`.
     *
     * @param yaml Main workflow YAML
     * @param input Workflow input
     * @param dependencies List of child workflow definitions
     * @param config Test configuration
     * @return [WorkflowTestResult]
     */
    suspend fun execute(
        yaml: String,
        input: JsonElement,
        dependencies: List<WorkflowDependency>,
        config: TestConfiguration = TestConfiguration.default()
    ): WorkflowTestResult

    /**
     * Get the [CloudEventCapture] for the last execution.
     *
     * Call this after [execute] to verify emitted CloudEvents.
     *
     * @return CloudEventCapture with all events from the last execution
     * @throws IllegalStateException if no execution has occurred
     */
    fun getEventCapture(): CloudEventCapture

    /**
     * Get the [CloudEventDelivery] for programmatic event delivery.
     *
     * Use this to trigger listen tasks during test execution.
     *
     * @return CloudEventDelivery instance
     */
    fun getEventDelivery(): CloudEventDelivery

    /**
     * Get the [WorkflowStateHooks] for event-based synchronization.
     *
     * Use this to wait for specific workflow/task state transitions.
     *
     * @return WorkflowStateHooks instance
     */
    fun getStateHooks(): WorkflowStateHooks
}

/**
 * Dependency workflow for parent-child relationship testing.
 *
 * When testing workflows that use `run: workflow` to call child workflows,
 * the child workflow definitions must be registered before execution.
 */
data class WorkflowDependency(
    /**
     * Child workflow YAML definition (without document header).
     */
    val yaml: String,

    /**
     * Workflow namespace. Must match the namespace used in `run: workflow`.
     */
    val namespace: String = "test",

    /**
     * Workflow name. Must match the name used in `run: workflow`.
     */
    val name: String,

    /**
     * Workflow version. Must match the version used in `run: workflow`.
     */
    val version: String = "0.1.0"
)
