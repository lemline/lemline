// SPDX-License-Identifier: BUSL-1.1
/**
 * TestActivityExecutor Contract
 *
 * This file defines the contract for activity mocking in tests.
 * Implementation will be in lemline-testing module.
 *
 * @feature 001-testing-architecture
 */
package com.lemline.testing

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Intercepts activity calls and returns configured responses for deterministic testing.
 *
 * Use this to mock HTTP calls, script execution, and shell commands
 * without depending on external services.
 *
 * ## Usage
 *
 * ```kotlin
 * val config = TestConfiguration.withMocking {
 *     // Queue responses in order they will be called
 *     queueHttpResponse(HttpResponse(
 *         statusCode = 200,
 *         body = buildJsonObject { put("success", true) }
 *     ))
 *
 *     queueHttpResponse(HttpResponse(
 *         statusCode = 404,
 *         body = buildJsonObject { put("error", "Not found") }
 *     ))
 * }
 *
 * val result = executor.execute(workflowYaml, input, config)
 *
 * // Verify calls were made as expected
 * val invocations = config.activityExecutor!!.getInvocations()
 * invocations.size shouldBe 2
 * (invocations[0] as HttpInvocation).url shouldBe "https://api.example.com/first"
 * ```
 *
 * ## Response Queuing
 *
 * Responses are consumed in FIFO order. If the queue is empty when an
 * activity is called, the test fails with a clear error message.
 *
 * ## Thread Safety
 *
 * TestActivityExecutor is thread-safe for concurrent workflow execution.
 */
interface TestActivityExecutor {

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP Mocking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Queue an HTTP response for the next `call: http` task.
     *
     * @param response The HTTP response to return
     */
    fun queueHttpResponse(response: HttpResponse)

    /**
     * Queue multiple HTTP responses for sequential calls.
     *
     * @param responses The HTTP responses in call order
     */
    fun queueHttpResponses(vararg responses: HttpResponse) {
        responses.forEach { queueHttpResponse(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Script Mocking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Queue a script response for the next `run: script` task.
     *
     * @param response The script execution result
     */
    fun queueScriptResponse(response: ScriptResponse)

    /**
     * Queue multiple script responses for sequential calls.
     */
    fun queueScriptResponses(vararg responses: ScriptResponse) {
        responses.forEach { queueScriptResponse(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shell Mocking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Queue a shell response for the next `run: shell` task.
     *
     * @param response The shell execution result
     */
    fun queueShellResponse(response: ShellResponse)

    /**
     * Queue multiple shell responses for sequential calls.
     */
    fun queueShellResponses(vararg responses: ShellResponse) {
        responses.forEach { queueShellResponse(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error Simulation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Queue an error for the next activity call of any type.
     *
     * Use this to test error handling and retry logic.
     *
     * @param error The error to simulate
     */
    fun queueError(error: ActivityError)

    // ─────────────────────────────────────────────────────────────────────────
    // Invocation Tracking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get all activity invocations recorded during test execution.
     *
     * Use this to verify:
     * - Activities were called with expected parameters
     * - Call order was correct
     * - Headers, bodies, etc. match expectations
     *
     * @return List of invocations in chronological order
     */
    fun getInvocations(): List<ActivityInvocation>

    /**
     * Get HTTP invocations only.
     */
    fun getHttpInvocations(): List<HttpInvocation> =
        getInvocations().filterIsInstance<HttpInvocation>()

    /**
     * Get script invocations only.
     */
    fun getScriptInvocations(): List<ScriptInvocation> =
        getInvocations().filterIsInstance<ScriptInvocation>()

    /**
     * Get shell invocations only.
     */
    fun getShellInvocations(): List<ShellInvocation> =
        getInvocations().filterIsInstance<ShellInvocation>()

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Clear all queued responses and recorded invocations.
     *
     * Called automatically between test executions.
     */
    fun reset()

    /**
     * Check if there are any unused queued responses.
     *
     * Useful for verifying all expected calls were made.
     *
     * @return true if response queues are empty
     */
    fun hasUnusedResponses(): Boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// Response Data Classes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Mocked HTTP response for `call: http` tasks.
 */
data class HttpResponse(
    /**
     * HTTP status code (e.g., 200, 404, 500).
     */
    val statusCode: Int = 200,

    /**
     * Response body as JSON.
     */
    val body: JsonElement = JsonObject(emptyMap()),

    /**
     * Response headers.
     */
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * Create a successful JSON response.
         */
        fun ok(body: JsonElement) = HttpResponse(statusCode = 200, body = body)

        /**
         * Create a 404 Not Found response.
         */
        fun notFound(message: String = "Not found") = HttpResponse(
            statusCode = 404,
            body = JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive(message)))
        )

        /**
         * Create a 500 Internal Server Error response.
         */
        fun serverError(message: String = "Internal server error") = HttpResponse(
            statusCode = 500,
            body = JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive(message)))
        )
    }
}

/**
 * Mocked script execution result for `run: script` tasks.
 */
data class ScriptResponse(
    /**
     * Script output as JSON (returned to workflow).
     */
    val output: JsonElement,

    /**
     * Exit code (0 = success).
     */
    val exitCode: Int = 0
) {
    companion object {
        /**
         * Create a successful script response.
         */
        fun ok(output: JsonElement) = ScriptResponse(output = output, exitCode = 0)

        /**
         * Create a failed script response.
         */
        fun failed(exitCode: Int = 1) = ScriptResponse(
            output = JsonObject(emptyMap()),
            exitCode = exitCode
        )
    }
}

/**
 * Mocked shell execution result for `run: shell` tasks.
 */
data class ShellResponse(
    /**
     * Standard output.
     */
    val stdout: String = "",

    /**
     * Standard error.
     */
    val stderr: String = "",

    /**
     * Exit code (0 = success).
     */
    val exitCode: Int = 0
) {
    companion object {
        /**
         * Create a successful shell response.
         */
        fun ok(stdout: String = "") = ShellResponse(stdout = stdout, exitCode = 0)

        /**
         * Create a failed shell response.
         */
        fun failed(stderr: String = "", exitCode: Int = 1) = ShellResponse(
            stderr = stderr,
            exitCode = exitCode
        )
    }
}

/**
 * Simulated activity error for testing error handling.
 */
data class ActivityError(
    /**
     * Error type (e.g., "network", "timeout", "validation").
     */
    val type: String,

    /**
     * Human-readable error message.
     */
    val message: String,

    /**
     * Additional error details.
     */
    val details: JsonElement? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// Invocation Tracking Data Classes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Base class for activity invocation records.
 */
sealed class ActivityInvocation {
    /**
     * When the activity was invoked.
     */
    abstract val timestamp: Instant
}

/**
 * Record of an HTTP call invocation.
 */
data class HttpInvocation(
    override val timestamp: Instant,

    /**
     * HTTP method (GET, POST, PUT, DELETE, etc.).
     */
    val method: String,

    /**
     * Request URL.
     */
    val url: String,

    /**
     * Request headers.
     */
    val headers: Map<String, String>,

    /**
     * Request body (if any).
     */
    val body: JsonElement?
) : ActivityInvocation()

/**
 * Record of a script execution invocation.
 */
data class ScriptInvocation(
    override val timestamp: Instant,

    /**
     * Script language (javascript, python, etc.).
     */
    val language: String,

    /**
     * Script code or source reference.
     */
    val code: String,

    /**
     * Arguments passed to the script.
     */
    val arguments: Map<String, JsonElement>
) : ActivityInvocation()

/**
 * Record of a shell command invocation.
 */
data class ShellInvocation(
    override val timestamp: Instant,

    /**
     * Shell command.
     */
    val command: String,

    /**
     * Command arguments.
     */
    val arguments: List<String>,

    /**
     * Environment variables.
     */
    val environment: Map<String, String>
) : ActivityInvocation()
