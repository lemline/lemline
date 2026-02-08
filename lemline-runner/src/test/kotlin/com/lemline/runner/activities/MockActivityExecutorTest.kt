// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.lemline.runner.activities

import com.lemline.core.activities.mock.HttpMockMatcher
import com.lemline.core.activities.mock.HttpMockResponse
import com.lemline.core.activities.mock.HttpMockRule
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.activities.mock.MockNotFoundException
import com.lemline.core.activities.mock.MockedActivityException
import com.lemline.core.activities.mock.ScriptMockMatcher
import com.lemline.core.activities.mock.ScriptMockResponse
import com.lemline.core.activities.mock.ScriptMockRule
import com.lemline.core.activities.mock.ShellMockMatcher
import com.lemline.core.activities.mock.ShellMockResponse
import com.lemline.core.activities.mock.ShellMockRule
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.processors.RunScriptConfig
import com.lemline.core.processors.RunShellConfig
import com.lemline.core.random.random
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent.CallHttpStarted
import com.lemline.core.states.WorkflowEvent.RunScriptStarted
import com.lemline.core.states.WorkflowEvent.RunShellStarted
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Unit tests for [MockActivityExecutor].
 *
 * Note: CallFunctionStarted is no longer an ActivityStarted event.
 * Functions are now handled by the orchestrator as Suspension events.
 */
class MockActivityExecutorTest : FunSpec({

    val testNodeStack = NodeStack.random()
    val emptyInput = JsonObject(emptyMap())

    context("HTTP activity execution") {

        test("returns mock response for matching HTTP call") {
            val config = MockConfiguration(
                httpMocks = listOf(
                    HttpMockRule(
                        HttpMockMatcher(url = "*api.example.com*", method = "GET"),
                        HttpMockResponse(
                            status = 200,
                            body = buildJsonObject { put("id", 123) }
                        )
                    )
                )
            )
            val executor = MockActivityExecutor(config)

            val event = CallHttpStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = CallHttpConfig(
                    method = "GET",
                    url = "https://api.example.com/users/123"
                )
            )

            val result = executor.execute(event)

            result.toString() shouldContain "123"
        }

        test("throws MockNotFoundException when no mock matches") {
            val config = MockConfiguration(
                httpMocks = listOf(
                    HttpMockRule(
                        HttpMockMatcher(url = "*other.com*"),
                        HttpMockResponse(status = 200)
                    )
                )
            )
            val executor = MockActivityExecutor(config)

            val event = CallHttpStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = CallHttpConfig(
                    method = "GET",
                    url = "https://api.example.com/users"
                )
            )

            val exception = shouldThrow<MockNotFoundException> {
                executor.execute(event)
            }
            exception.message shouldContain "api.example.com"
        }

        test("throws MockedActivityException when mock has error") {
            val config = MockConfiguration(
                httpMocks = listOf(
                    HttpMockRule(
                        HttpMockMatcher(url = "*failing.api*"),
                        HttpMockResponse(status = 500, error = "Service unavailable")
                    )
                )
            )
            val executor = MockActivityExecutor(config)

            val event = CallHttpStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = CallHttpConfig(
                    method = "GET",
                    url = "https://failing.api/endpoint"
                )
            )

            val exception = shouldThrow<MockedActivityException> {
                executor.execute(event)
            }
            exception.message shouldBe "Service unavailable"
        }

        test("returns empty object when body is null") {
            val config = MockConfiguration(
                httpMocks = listOf(
                    HttpMockRule(
                        HttpMockMatcher(url = "*"),
                        HttpMockResponse(status = 204, body = null)
                    )
                )
            )
            val executor = MockActivityExecutor(config)

            val event = CallHttpStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = CallHttpConfig(method = "DELETE", url = "https://any.url")
            )

            val result = executor.execute(event)
            result shouldBe JsonObject(emptyMap())
        }
    }

    context("Script activity execution") {

        test("returns mock output for matching script") {
            val config = MockConfiguration(
                scriptMocks = listOf(
                    ScriptMockRule(
                        ScriptMockMatcher(language = "javascript"),
                        ScriptMockResponse(
                            output = buildJsonObject { put("computed", 42) },
                            exitCode = 0
                        )
                    )
                )
            )
            val executor = MockActivityExecutor(config)

            val event = RunScriptStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = RunScriptConfig(
                    language = "javascript",
                    code = "return 42;"
                )
            )

            val result = executor.execute(event)
            result.toString() shouldContain "42"
        }

        test("throws MockNotFoundException when no script mock matches") {
            val config = MockConfiguration(
                scriptMocks = listOf(
                    ScriptMockRule(
                        ScriptMockMatcher(language = "python"),
                        ScriptMockResponse(exitCode = 0)
                    )
                )
            )
            val executor = MockActivityExecutor(config)

            val event = RunScriptStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = RunScriptConfig(language = "ruby", code = "puts 'hello'")
            )

            val exception = shouldThrow<MockNotFoundException> {
                executor.execute(event)
            }
            exception.message shouldContain "ruby"
        }

        test("throws MockedActivityException for non-zero exit code") {
            val config = MockConfiguration(
                scriptMocks = listOf(
                    ScriptMockRule(
                        ScriptMockMatcher(language = "python"),
                        ScriptMockResponse(exitCode = 1)
                    )
                )
            )
            val executor = MockActivityExecutor(config)

            val event = RunScriptStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = RunScriptConfig(language = "python", code = "raise Exception()")
            )

            val exception = shouldThrow<MockedActivityException> {
                executor.execute(event)
            }
            exception.message shouldContain "exit code: 1"
        }
    }

    context("Shell activity execution") {

        test("returns mock stdout for matching command") {
            val config = MockConfiguration(
                shellMocks = listOf(
                    ShellMockRule(
                        ShellMockMatcher(command = "echo*"),
                        ShellMockResponse(stdout = "mocked output", exitCode = 0)
                    )
                )
            )
            val executor = MockActivityExecutor(config)

            val event = RunShellStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = RunShellConfig(command = "echo hello")
            )

            val result = executor.execute(event)
            result.jsonPrimitive.content shouldBe "mocked output"
        }

        test("throws MockNotFoundException when no shell mock matches") {
            val config = MockConfiguration(
                shellMocks = listOf(
                    ShellMockRule(
                        ShellMockMatcher(command = "ls*"),
                        ShellMockResponse(stdout = "files")
                    )
                )
            )
            val executor = MockActivityExecutor(config)

            val event = RunShellStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = RunShellConfig(command = "pwd")
            )

            val exception = shouldThrow<MockNotFoundException> {
                executor.execute(event)
            }
            exception.message shouldContain "pwd"
        }

        test("throws MockedActivityException for non-zero exit code") {
            val config = MockConfiguration(
                shellMocks = listOf(
                    ShellMockRule(
                        ShellMockMatcher(command = "failing-cmd"),
                        ShellMockResponse(stdout = "", stderr = "Error", exitCode = 1)
                    )
                )
            )
            val executor = MockActivityExecutor(config)

            val event = RunShellStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = RunShellConfig(command = "failing-cmd")
            )

            val exception = shouldThrow<MockedActivityException> {
                executor.execute(event)
            }
            exception.message shouldContain "exit code: 1"
        }
    }

    context("Empty executor") {

        test("throws MockNotFoundException for any HTTP call") {
            val executor = MockActivityExecutor.empty()

            val event = CallHttpStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = CallHttpConfig(method = "GET", url = "https://any.url")
            )

            shouldThrow<MockNotFoundException> {
                executor.execute(event)
            }
        }

        test("throws MockNotFoundException for any script") {
            val executor = MockActivityExecutor.empty()

            val event = RunScriptStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = RunScriptConfig(language = "python", code = "pass")
            )

            shouldThrow<MockNotFoundException> {
                executor.execute(event)
            }
        }

        test("throws MockNotFoundException for any shell command") {
            val executor = MockActivityExecutor.empty()

            val event = RunShellStarted(
                nodeStack = testNodeStack,
                input = emptyInput,
                config = RunShellConfig(command = "any command")
            )

            shouldThrow<MockNotFoundException> {
                executor.execute(event)
            }
        }
    }
})
