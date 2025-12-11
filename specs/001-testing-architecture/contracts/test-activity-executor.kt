// SPDX-License-Identifier: BUSL-1.1
/**
 * TestActivityExecutor Contract
 *
 * This file defines the contract for activity mocking in tests.
 * Implementation will be in lemline-runner module, activated via --test-mode CLI flag.
 *
 * Architecture: TestActivityExecutor implements the existing ActivityExecutor interface.
 * Mock responses are loaded from a YAML/JSON config file specified via --mock-config flag.
 *
 * @feature 001-testing-architecture
 */
package com.lemline.runner.activities

import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.processors.RunScriptConfig
import com.lemline.core.processors.RunShellConfig
import com.lemline.core.states.WorkflowEvent.ActivityStarted
import com.lemline.core.states.WorkflowEvent.CallHttpStarted
import com.lemline.core.states.WorkflowEvent.RunScriptStarted
import com.lemline.core.states.WorkflowEvent.RunShellStarted
import com.lemline.core.states.WorkflowEvent.EmitStarted
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Activity executor that returns mock responses from configuration.
 *
 * Activated when runner starts with `--test-mode` flag.
 * Mock responses are loaded from the file specified by `--mock-config=<path>`.
 *
 * ## CLI Usage
 *
 * ```bash
 * ./lemline-runner listen --test-mode --mock-config=/path/to/mocks.yaml
 * ```
 *
 * ## Mock Configuration Format
 *
 * ```yaml
 * # mocks.yaml
 * http:
 *   - match:
 *       url: "*api.example.com*"
 *       method: GET
 *     response:
 *       status: 200
 *       body:
 *         id: 123
 *         name: "Test User"
 *
 *   - match:
 *       url: "*failing.api*"
 *     response:
 *       status: 500
 *       error: "Service unavailable"
 *
 * script:
 *   - match:
 *       language: javascript
 *     response:
 *       output:
 *         computed: 42
 *       exitCode: 0
 *
 * shell:
 *   - match:
 *       command: "echo*"
 *     response:
 *       stdout: "mocked output"
 *       exitCode: 0
 * ```
 *
 * ## Matching Rules
 *
 * - URL patterns support glob wildcards: `*`, `?`
 * - Command patterns support prefix matching with `*`
 * - First matching rule wins
 * - If no rule matches, an error is thrown (fail-fast)
 *
 * ## Thread Safety
 *
 * TestActivityExecutor is thread-safe. Mock configuration is immutable after loading.
 */
class TestActivityExecutor(
    private val mockConfig: MockConfiguration
) : ActivityExecutor {

    override suspend fun execute(event: ActivityStarted): JsonElement = when (event) {
        is EmitStarted -> executeEmit(event)
        is CallHttpStarted -> executeHttp(event.config)
        is RunScriptStarted -> executeScript(event.config)
        is RunShellStarted -> executeShell(event.config)
    }

    private fun executeEmit(event: EmitStarted): JsonElement {
        // Emit tasks are fire-and-forget in test mode too
        // The CloudEvent is still emitted to the broker for capture
        return event.input
    }

    private fun executeHttp(config: CallHttpConfig): JsonElement {
        val mock = mockConfig.findHttpMock(config.url, config.method)
            ?: throw MockNotFoundException("No HTTP mock found for ${config.method} ${config.url}")

        if (mock.response.error != null) {
            throw MockedActivityException(mock.response.error)
        }

        return mock.response.body ?: JsonObject(emptyMap())
    }

    private fun executeScript(config: RunScriptConfig): JsonElement {
        val mock = mockConfig.findScriptMock(config.language)
            ?: throw MockNotFoundException("No script mock found for language: ${config.language}")

        if (mock.response.exitCode != 0) {
            throw MockedActivityException("Script failed with exit code: ${mock.response.exitCode}")
        }

        return mock.response.output ?: JsonObject(emptyMap())
    }

    private fun executeShell(config: RunShellConfig): JsonElement {
        val mock = mockConfig.findShellMock(config.command)
            ?: throw MockNotFoundException("No shell mock found for command: ${config.command}")

        if (mock.response.exitCode != 0) {
            throw MockedActivityException("Shell command failed with exit code: ${mock.response.exitCode}")
        }

        return kotlinx.serialization.json.JsonPrimitive(mock.response.stdout)
    }
}

/**
 * Mock configuration loaded from --mock-config file.
 */
data class MockConfiguration(
    val httpMocks: List<HttpMockRule> = emptyList(),
    val scriptMocks: List<ScriptMockRule> = emptyList(),
    val shellMocks: List<ShellMockRule> = emptyList()
) {
    fun findHttpMock(url: String, method: String): HttpMockRule? =
        httpMocks.firstOrNull { it.matches(url, method) }

    fun findScriptMock(language: String): ScriptMockRule? =
        scriptMocks.firstOrNull { it.matches(language) }

    fun findShellMock(command: String): ShellMockRule? =
        shellMocks.firstOrNull { it.matches(command) }

    companion object {
        /**
         * Load mock configuration from YAML file.
         */
        fun fromYaml(path: String): MockConfiguration {
            // Implementation uses kaml library
            TODO("Implementation in lemline-runner")
        }

        /**
         * Empty configuration (no mocks - all activities will fail).
         */
        fun empty() = MockConfiguration()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HTTP Mock Rules
// ─────────────────────────────────────────────────────────────────────────────

data class HttpMockRule(
    val match: HttpMockMatcher,
    val response: HttpMockResponse
) {
    fun matches(url: String, method: String): Boolean {
        if (match.method != null && !match.method.equals(method, ignoreCase = true)) {
            return false
        }
        if (match.url != null && !globMatches(match.url, url)) {
            return false
        }
        return true
    }
}

data class HttpMockMatcher(
    val url: String? = null,
    val method: String? = null
)

data class HttpMockResponse(
    val status: Int = 200,
    val body: JsonElement? = null,
    val headers: Map<String, String> = emptyMap(),
    val error: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// Script Mock Rules
// ─────────────────────────────────────────────────────────────────────────────

data class ScriptMockRule(
    val match: ScriptMockMatcher,
    val response: ScriptMockResponse
) {
    fun matches(language: String): Boolean {
        if (match.language != null && !match.language.equals(language, ignoreCase = true)) {
            return false
        }
        return true
    }
}

data class ScriptMockMatcher(
    val language: String? = null
)

data class ScriptMockResponse(
    val output: JsonElement? = null,
    val exitCode: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// Shell Mock Rules
// ─────────────────────────────────────────────────────────────────────────────

data class ShellMockRule(
    val match: ShellMockMatcher,
    val response: ShellMockResponse
) {
    fun matches(command: String): Boolean {
        if (match.command != null && !globMatches(match.command, command)) {
            return false
        }
        return true
    }
}

data class ShellMockMatcher(
    val command: String? = null
)

data class ShellMockResponse(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// Utility Functions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Simple glob pattern matching.
 * Supports `*` (matches any characters) and `?` (matches single character).
 */
private fun globMatches(pattern: String, input: String): Boolean {
    val regex = pattern
        .replace(".", "\\.")
        .replace("*", ".*")
        .replace("?", ".")
    return Regex(regex, RegexOption.IGNORE_CASE).matches(input)
}

// ─────────────────────────────────────────────────────────────────────────────
// Exceptions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Thrown when no mock rule matches the activity request.
 */
class MockNotFoundException(message: String) : RuntimeException(message)

/**
 * Thrown when mock configuration specifies an error response.
 */
class MockedActivityException(message: String) : RuntimeException(message)
