// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.mock

import com.lemline.common.logger.logger
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.cloudevents.CloudEventFactory
import com.lemline.core.states.WorkflowEvent.ActivityStarted
import com.lemline.core.states.WorkflowEvent.CallHttpStarted
import com.lemline.core.states.WorkflowEvent.EmitStarted
import com.lemline.core.states.WorkflowEvent.RunScriptStarted
import com.lemline.core.states.WorkflowEvent.RunShellStarted
import io.cloudevents.CloudEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Activity executor that returns mock responses from configuration.
 *
 * Use this executor for testing workflows without performing real I/O operations
 * (HTTP calls, script execution, shell commands).
 *
 * Note: Function calls are NOT activities - they are control-flow tasks that
 * navigate to function nodes step-by-step through normal message flow.
 * Use [MockFunctionResolver] to provide mock function definitions for testing.
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
 * val functionResolver = MockFunctionResolver(mockConfig)
 *
 * FullOrchestrator.start(workflow, activityExecutor = mockExecutor, functionResolver = functionResolver)
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
 *
 * @param mockConfig The mock configuration defining responses for activities
 * @param emitCloudEvent Optional callback invoked when a CloudEvent is emitted. Used for testing emit behavior.
 */
class MockActivityExecutor(
    val mockConfig: MockConfiguration,
    private val emitCloudEvent: (suspend (CloudEvent) -> Unit)? = null,
) : ActivityExecutor {

    private val logger = logger()

    override suspend fun execute(event: ActivityStarted): JsonElement = when (event) {
        is CallHttpStarted -> executeHttp(event)
        is RunScriptStarted -> executeScript(event)
        is RunShellStarted -> executeShell(event)
        is EmitStarted -> executeEmit(event)
    }

    /**
     * Execute HTTP call using mock configuration.
     */
    private fun executeHttp(event: CallHttpStarted): JsonElement {
        val config = event.config
        val mock = mockConfig.findHttpMock(config.url, config.method, config.query)
            ?: throw MockNotFoundException("No HTTP mock found for ${config.method} ${config.url} query=${config.query}")

        logger.debug { "Mock: HTTP mock matched for ${config.method} ${config.url}" }

        if (mock.response.error != null) {
            throw MockedActivityException(mock.response.error)
        }

        return mock.response.body ?: JsonObject(emptyMap())
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

    /**
     * Execute emit using mock configuration.
     *
     * Builds and emits the CloudEvent via the callback if provided.
     * Mock configuration can override output or simulate errors.
     */
    private suspend fun executeEmit(event: EmitStarted): JsonElement {
        val config = event.config
        val mock = mockConfig.findEmitMock(config.type, config.source)

        // Check for mock error first
        if (mock?.response?.error != null) {
            throw MockedActivityException(mock.response.error)
        }

        // Build and emit the CloudEvent
        val cloudEvent = CloudEventFactory.build(config)
        logger.debug { "Mock: Emitting CloudEvent type=${config.type} source=${config.source}" }
        emitCloudEvent?.invoke(cloudEvent)

        // Return mock output if provided, otherwise pass through input
        return mock?.response?.output ?: event.input
    }

    companion object {
        /**
         * Create a MockActivityExecutor from a mock configuration file path.
         */
        fun fromFile(
            path: String,
            emitCloudEvent: (suspend (CloudEvent) -> Unit)? = null
        ): MockActivityExecutor {
            val config = MockConfigurationParser.fromFile(path)
            return MockActivityExecutor(config, emitCloudEvent)
        }

        /**
         * Create a MockActivityExecutor from YAML content.
         */
        fun fromYaml(
            content: String,
            emitCloudEvent: (suspend (CloudEvent) -> Unit)? = null
        ): MockActivityExecutor {
            val config = MockConfigurationParser.fromYaml(content)
            return MockActivityExecutor(config, emitCloudEvent)
        }

        /**
         * Create a MockActivityExecutor with empty configuration.
         * All activity calls will throw MockNotFoundException (except emit which passes through).
         */
        fun empty(emitCloudEvent: (suspend (CloudEvent) -> Unit)? = null): MockActivityExecutor {
            return MockActivityExecutor(MockConfiguration.empty(), emitCloudEvent)
        }
    }
}
