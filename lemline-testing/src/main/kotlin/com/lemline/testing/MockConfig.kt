// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * DSL builder for mock configuration used in E2E tests.
 *
 * This generates a YAML file that the runner loads via `--mock-config` flag.
 *
 * ## Usage
 *
 * ```kotlin
 * val mocks = MockConfig {
 *     http {
 *         match { url = "*api.example.com*"; method = "GET" }
 *         respond { status = 200; body = buildJsonObject { put("id", 123) } }
 *     }
 *     script {
 *         match { language = "javascript" }
 *         respond { output = buildJsonObject { put("result", 42) } }
 *     }
 *     shell {
 *         match { command = "echo*" }
 *         respond { stdout = "mocked"; exitCode = 0 }
 *     }
 * }
 * ```
 */
class MockConfig private constructor(
    internal val httpMocks: List<HttpMock>,
    internal val scriptMocks: List<ScriptMock>,
    internal val shellMocks: List<ShellMock>
) {
    /**
     * Convert to YAML format for --mock-config file.
     */
    fun toYaml(): String = buildString {
        if (httpMocks.isNotEmpty()) {
            appendLine("http:")
            for (mock in httpMocks) {
                appendLine("  - match:")
                mock.matcher.url?.let { appendLine("      url: \"$it\"") }
                mock.matcher.method?.let { appendLine("      method: $it") }
                appendLine("    response:")
                appendLine("      status: ${mock.response.status}")
                mock.response.body?.let { appendLine("      body: $it") }
                mock.response.error?.let { appendLine("      error: \"$it\"") }
            }
        }

        if (scriptMocks.isNotEmpty()) {
            appendLine("script:")
            for (mock in scriptMocks) {
                appendLine("  - match:")
                mock.matcher.language?.let { appendLine("      language: $it") }
                appendLine("    response:")
                mock.response.output?.let { appendLine("      output: $it") }
                appendLine("      exitCode: ${mock.response.exitCode}")
            }
        }

        if (shellMocks.isNotEmpty()) {
            appendLine("shell:")
            for (mock in shellMocks) {
                appendLine("  - match:")
                mock.matcher.command?.let { appendLine("      command: \"$it\"") }
                appendLine("    response:")
                appendLine("      stdout: \"${mock.response.stdout}\"")
                mock.response.stderr?.let { appendLine("      stderr: \"$it\"") }
                appendLine("      exitCode: ${mock.response.exitCode}")
            }
        }
    }

    class Builder {
        private val httpMocks = mutableListOf<HttpMock>()
        private val scriptMocks = mutableListOf<ScriptMock>()
        private val shellMocks = mutableListOf<ShellMock>()

        fun http(block: HttpMockBuilder.() -> Unit) {
            httpMocks.add(HttpMockBuilder().apply(block).build())
        }

        fun script(block: ScriptMockBuilder.() -> Unit) {
            scriptMocks.add(ScriptMockBuilder().apply(block).build())
        }

        fun shell(block: ShellMockBuilder.() -> Unit) {
            shellMocks.add(ShellMockBuilder().apply(block).build())
        }

        fun build() = MockConfig(httpMocks.toList(), scriptMocks.toList(), shellMocks.toList())
    }

    companion object {
        operator fun invoke(block: Builder.() -> Unit): MockConfig =
            Builder().apply(block).build()

        /**
         * Empty mock config (all activities will fail with MockNotFoundException).
         */
        fun empty() = MockConfig(emptyList(), emptyList(), emptyList())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HTTP Mocks
// ─────────────────────────────────────────────────────────────────────────────

data class HttpMock(
    val matcher: HttpMatcher,
    val response: HttpResponse
)

data class HttpMatcher(
    val url: String? = null,
    val method: String? = null
)

data class HttpResponse(
    val status: Int = 200,
    val body: JsonElement? = null,
    val error: String? = null
)

class HttpMockBuilder {
    private var matcher = HttpMatcher()
    private var response = HttpResponse()

    fun match(block: HttpMatcherBuilder.() -> Unit) {
        matcher = HttpMatcherBuilder().apply(block).build()
    }

    fun respond(block: HttpResponseBuilder.() -> Unit) {
        response = HttpResponseBuilder().apply(block).build()
    }

    fun build() = HttpMock(matcher, response)
}

class HttpMatcherBuilder {
    var url: String? = null
    var method: String? = null

    fun build() = HttpMatcher(url, method)
}

class HttpResponseBuilder {
    var status: Int = 200
    var body: JsonElement? = null
    var error: String? = null

    fun build() = HttpResponse(status, body, error)
}

// ─────────────────────────────────────────────────────────────────────────────
// Script Mocks
// ─────────────────────────────────────────────────────────────────────────────

data class ScriptMock(
    val matcher: ScriptMatcher,
    val response: ScriptResponse
)

data class ScriptMatcher(
    val language: String? = null
)

data class ScriptResponse(
    val output: JsonElement? = null,
    val exitCode: Int = 0
)

class ScriptMockBuilder {
    private var matcher = ScriptMatcher()
    private var response = ScriptResponse()

    fun match(block: ScriptMatcherBuilder.() -> Unit) {
        matcher = ScriptMatcherBuilder().apply(block).build()
    }

    fun respond(block: ScriptResponseBuilder.() -> Unit) {
        response = ScriptResponseBuilder().apply(block).build()
    }

    fun build() = ScriptMock(matcher, response)
}

class ScriptMatcherBuilder {
    var language: String? = null

    fun build() = ScriptMatcher(language)
}

class ScriptResponseBuilder {
    var output: JsonElement? = null
    var exitCode: Int = 0

    fun build() = ScriptResponse(output, exitCode)
}

// ─────────────────────────────────────────────────────────────────────────────
// Shell Mocks
// ─────────────────────────────────────────────────────────────────────────────

data class ShellMock(
    val matcher: ShellMatcher,
    val response: ShellResponse
)

data class ShellMatcher(
    val command: String? = null
)

data class ShellResponse(
    val stdout: String = "",
    val stderr: String? = null,
    val exitCode: Int = 0
)

class ShellMockBuilder {
    private var matcher = ShellMatcher()
    private var response = ShellResponse()

    fun match(block: ShellMatcherBuilder.() -> Unit) {
        matcher = ShellMatcherBuilder().apply(block).build()
    }

    fun respond(block: ShellResponseBuilder.() -> Unit) {
        response = ShellResponseBuilder().apply(block).build()
    }

    fun build() = ShellMock(matcher, response)
}

class ShellMatcherBuilder {
    var command: String? = null

    fun build() = ShellMatcher(command)
}

class ShellResponseBuilder {
    var stdout: String = ""
    var stderr: String? = null
    var exitCode: Int = 0

    fun build() = ShellResponse(stdout, stderr, exitCode)
}
