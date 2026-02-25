// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.mock

import io.serverlessworkflow.api.types.Task
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mock configuration for activity execution in test mode.
 *
 * This class defines the structure of mock rules for activity execution.
 * Mock configuration can be specified via YAML or JSON:
 *
 * ```yaml
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
 */
@Serializable
data class MockConfiguration(
    @SerialName("emit")
    val emitMocks: List<EmitMockRule> = emptyList(),
    @SerialName("listen")
    val listenMocks: List<ListenMockRule> = emptyList(),
    @SerialName("http")
    val httpMocks: List<HttpMockRule> = emptyList(),
    @SerialName("function")
    val functionMocks: List<FunctionMockRule> = emptyList(),
    @SerialName("script")
    val scriptMocks: List<ScriptMockRule> = emptyList(),
    @SerialName("shell")
    val shellMocks: List<ShellMockRule> = emptyList(),
    /**
     * Function definitions for remote functions (URL/catalog).
     *
     * Map of function reference to its Task definition.
     * Used by MockFunctionResolver to provide definitions for execution.
     *
     * Unlike [functionMocks] which return a canned output, these definitions
     * are actually executed as mini-workflows.
     */
    @SerialName("functionDefinitions")
    @kotlinx.serialization.Transient
    val functionDefinitions: Map<String, Task> = emptyMap()
) {
    /**
     * Find emit mock matching type and source.
     * First matching rule wins.
     */
    fun findEmitMock(type: String, source: String): EmitMockRule? =
        emitMocks.firstOrNull { it.matches(type, source) }

    /**
     * Find listen mock matching event type.
     * First matching rule wins.
     */
    fun findListenMock(eventType: String): ListenMockRule? =
        listenMocks.firstOrNull { it.matches(eventType) }

    /**
     * Find HTTP mock matching URL and method.
     * First matching rule wins.
     */
    fun findHttpMock(url: String, method: String, query: Map<String, String> = emptyMap()): HttpMockRule? =
        httpMocks.firstOrNull { it.matches(url, method, query) }

    /**
     * Find function mock matching function reference.
     * First matching rule wins.
     */
    fun findFunctionMock(functionRef: String): FunctionMockRule? =
        functionMocks.firstOrNull { it.matches(functionRef) }

    /**
     * Get function definition for a remote function reference.
     * Returns the Task to execute, or null if not defined.
     */
    fun getFunctionDefinition(functionRef: String): Task? =
        functionDefinitions[functionRef]

    /**
     * Find script mock matching language.
     * First matching rule wins.
     */
    fun findScriptMock(language: String): ScriptMockRule? =
        scriptMocks.firstOrNull { it.matches(language) }

    /**
     * Find shell mock matching command.
     * First matching rule wins.
     */
    fun findShellMock(command: String): ShellMockRule? =
        shellMocks.firstOrNull { it.matches(command) }

    companion object {
        /**
         * Empty configuration (no mocks - all activities will fail).
         */
        fun empty() = MockConfiguration()
    }
}
// ─────────────────────────────────────────────────────────────────────────────
// Emit Mock Rules
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class EmitMockRule(
    val match: EmitMockMatcher = EmitMockMatcher(),
    val response: EmitMockResponse = EmitMockResponse()
) {
    /**
     * Check if this rule matches the given event type and source.
     */
    fun matches(type: String, source: String): Boolean {
        if (match.type != null && !globMatches(match.type, type)) {
            return false
        }
        if (match.source != null && !globMatches(match.source, source)) {
            return false
        }
        return true
    }
}

@Serializable
data class EmitMockMatcher(
    val type: String? = null,
    val source: String? = null
)

@Serializable
data class EmitMockResponse(
    val output: JsonElement? = null,
    val error: String? = null
)
// ─────────────────────────────────────────────────────────────────────────────
// Listen Mock Rules
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ListenMockRule(
    val match: ListenMockMatcher = ListenMockMatcher(),
    val response: ListenMockResponse = ListenMockResponse()
) {
    /**
     * Check if this rule matches the given event type filter.
     */
    fun matches(eventType: String): Boolean {
        return match.type == null || globMatches(match.type, eventType)
    }
}

@Serializable
data class ListenMockMatcher(
    val type: String? = null
)

@Serializable
data class ListenMockResponse(
    /** The CloudEvent data to return as the listen result */
    val data: JsonElement? = null,
    val error: String? = null
)
// ─────────────────────────────────────────────────────────────────────────────
// HTTP Mock Rules
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class HttpMockRule(
    val match: HttpMockMatcher,
    val response: HttpMockResponse
) {
    /**
     * Check if this rule matches the given URL, method, and query parameters.
     */
    fun matches(url: String, method: String, query: Map<String, String> = emptyMap()): Boolean {
        if (match.method != null && !match.method.equals(method, ignoreCase = true)) {
            return false
        }
        if (match.url != null && !globMatches(match.url, url)) {
            return false
        }
        if (match.query != null && !match.query.all { (key, value) -> query[key] == value }) {
            return false
        }
        return true
    }
}

@Serializable
data class HttpMockMatcher(
    val url: String? = null,
    val method: String? = null,
    val query: Map<String, String>? = null
)

@Serializable
data class HttpMockResponse(
    val status: Int = 200,
    val body: JsonElement? = null,
    val headers: Map<String, String> = emptyMap(),
    val error: String? = null
)
// ─────────────────────────────────────────────────────────────────────────────
// Script Mock Rules
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ScriptMockRule(
    val match: ScriptMockMatcher,
    val response: ScriptMockResponse
) {
    /**
     * Check if this rule matches the given language.
     */
    fun matches(language: String): Boolean {
        return !(match.language != null && !match.language.equals(language, ignoreCase = true))
    }
}

@Serializable
data class ScriptMockMatcher(
    val language: String? = null
)

@Serializable
data class ScriptMockResponse(
    val output: JsonElement? = null,
    val exitCode: Int = 0
)
// ─────────────────────────────────────────────────────────────────────────────
// Shell Mock Rules
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ShellMockRule(
    val match: ShellMockMatcher,
    val response: ShellMockResponse
) {
    /**
     * Check if this rule matches the given command.
     */
    fun matches(command: String): Boolean {
        return !(match.command != null && !globMatches(match.command, command))
    }
}

@Serializable
data class ShellMockMatcher(
    val command: String? = null
)

@Serializable
data class ShellMockResponse(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0
)
// ─────────────────────────────────────────────────────────────────────────────
// Function Mock Rules
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class FunctionMockRule(
    val match: FunctionMockMatcher = FunctionMockMatcher(),
    val response: FunctionMockResponse = FunctionMockResponse()
) {
    /**
     * Check if this rule matches the given function reference.
     */
    fun matches(functionRef: String): Boolean {
        return match.functionRef == null || globMatches(match.functionRef, functionRef)
    }
}

@Serializable
data class FunctionMockMatcher(
    val functionRef: String? = null
)

@Serializable
data class FunctionMockResponse(
    val output: JsonElement? = null,
    val error: String? = null
)
// ─────────────────────────────────────────────────────────────────────────────
// Utility Functions
// ─────────────────────────────────────────────────────────────────────────────
/**
 * Simple glob pattern matching.
 * Supports `*` (matches any characters) and `?` (matches single character).
 */
internal fun globMatches(pattern: String, input: String): Boolean {
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
