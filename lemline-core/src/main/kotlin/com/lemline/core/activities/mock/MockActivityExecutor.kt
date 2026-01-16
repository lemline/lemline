// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.mock

import com.lemline.common.logger.logger
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.states.WorkflowEvent.ActivityStarted
import com.lemline.core.states.WorkflowEvent.CallFunctionStarted
import com.lemline.core.states.WorkflowEvent.CallHttpStarted
import com.lemline.core.states.WorkflowEvent.RunScriptStarted
import com.lemline.core.states.WorkflowEvent.RunShellStarted
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Activity executor that returns mock responses from configuration.
 *
 * Use this executor for testing workflows without performing real I/O operations
 * (HTTP calls, script execution, shell commands).
 *
 * ## Usage in Tests
 *
 * ```kotlin
 * val mockExecutor = MockActivityExecutor(MockConfiguration(
 *     httpMocks = listOf(
 *         HttpMockRule(
 *             match = HttpMockMatcher(url = "*api.example.com*"),
 *             response = HttpMockResponse(body = buildJsonObject { put("id", 1) })
 *         )
 *     )
 * ))
 *
 * FullOrchestrator.start(workflow, activityExecutor = mockExecutor)
 * ```
 *
 * ## File-Based Configuration
 *
 * ```kotlin
 * val mockExecutor = MockActivityExecutor.fromFile("/path/to/mocks.yaml")
 * ```
 *
 * ## Thread Safety
 *
 * MockActivityExecutor is thread-safe. Mock configuration is immutable after construction.
 */
class MockActivityExecutor(
    val mockConfig: MockConfiguration
) : ActivityExecutor {

    private val logger = logger()

    override suspend fun execute(event: ActivityStarted): JsonElement = when (event) {
        is CallHttpStarted -> executeHttp(event)
        is CallFunctionStarted -> executeFunction(event)
        is RunScriptStarted -> executeScript(event)
        is RunShellStarted -> executeShell(event)
    }

    /**
     * Execute HTTP call using mock configuration.
     */
    private fun executeHttp(event: CallHttpStarted): JsonElement {
        val config = event.config
        val mock = mockConfig.findHttpMock(config.url, config.method)
            ?: throw MockNotFoundException("No HTTP mock found for ${config.method} ${config.url}")

        logger.debug { "Mock: HTTP mock matched for ${config.method} ${config.url}" }

        if (mock.response.error != null) {
            throw MockedActivityException(mock.response.error)
        }

        return mock.response.body ?: JsonObject(emptyMap())
    }

    /**
     * Execute function call using mock configuration.
     */
    private fun executeFunction(event: CallFunctionStarted): JsonElement {
        val config = event.config
        val mock = mockConfig.findFunctionMock(config.functionRef)
            ?: throw MockNotFoundException("No function mock found for: ${config.functionRef}")

        logger.debug { "Mock: Function mock matched for ${config.functionRef}" }

        if (mock.response.error != null) {
            throw MockedActivityException(mock.response.error)
        }

        return mock.response.output ?: JsonObject(emptyMap())
    }

    /**
     * Execute script using mock configuration.
     */
    private fun executeScript(event: RunScriptStarted): JsonElement {
        val config = event.config
        val mock = mockConfig.findScriptMock(config.language)
            ?: throw MockNotFoundException("No script mock found for language: ${config.language}")

        logger.debug { "Mock: Script mock matched for language=${config.language}" }

        if (mock.response.exitCode != 0) {
            throw MockedActivityException("Script failed with exit code: ${mock.response.exitCode}")
        }

        return mock.response.output ?: JsonObject(emptyMap())
    }

    /**
     * Execute shell command using mock configuration.
     */
    private fun executeShell(event: RunShellStarted): JsonElement {
        val config = event.config
        val mock = mockConfig.findShellMock(config.command)
            ?: throw MockNotFoundException("No shell mock found for command: ${config.command}")

        logger.debug { "Mock: Shell mock matched for command=${config.command}" }

        if (mock.response.exitCode != 0) {
            throw MockedActivityException("Shell command failed with exit code: ${mock.response.exitCode}")
        }

        return JsonPrimitive(mock.response.stdout)
    }

    companion object {
        /**
         * Create a MockActivityExecutor from a mock configuration file path.
         */
        fun fromFile(path: String): MockActivityExecutor {
            val config = MockConfigurationParser.fromFile(path)
            return MockActivityExecutor(config)
        }

        /**
         * Create a MockActivityExecutor from YAML content.
         */
        fun fromYaml(content: String): MockActivityExecutor {
            val config = MockConfigurationParser.fromYaml(content)
            return MockActivityExecutor(config)
        }

        /**
         * Create a MockActivityExecutor with empty configuration.
         * All activity calls will throw MockNotFoundException.
         */
        fun empty(): MockActivityExecutor {
            return MockActivityExecutor(MockConfiguration.empty())
        }
    }
}
