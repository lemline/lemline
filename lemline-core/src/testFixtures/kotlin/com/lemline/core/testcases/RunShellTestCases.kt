// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.TestMocks
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test cases for Shell execution.
 * Tests shell command execution using mocks.
 *
 * Note: These tests use mocked shell execution. Real shell execution tests
 * should be in the runner module with actual shell commands.
 */
object RunShellTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "shell can execute simple command",
            mockConfig = TestMocks.shellConfig,
            yaml = """
                do:
                  - echoHello:
                      run:
                        shell:
                          command: echo Hello World
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "shell can execute command with arguments",
            mockConfig = TestMocks.shellConfig,
            yaml = """
                do:
                  - echoWithArgs:
                      run:
                        shell:
                          command: echo
                          arguments:
                            "Hello": World
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "shell can use environment variables",
            mockConfig = TestMocks.shellConfig,
            yaml = $$"""
                do:
                  - printEnv:
                      run:
                        shell:
                          command: sh
                          arguments:
                            "-c": echo $MY_VAR
                          environment:
                            MY_VAR: TestValue
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "shell can return stdout",
            mockConfig = TestMocks.shellConfig,
            yaml = """
                do:
                  - echoStdout:
                      run:
                        shell:
                          command: echo Testing stdout
                        return: stdout
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "shell can use expressions in command",
            mockConfig = TestMocks.shellConfig,
            yaml = $$"""
                do:
                  - setGreeting:
                      set:
                        greeting: Hello
                        name: Alice
                  - echoGreeting:
                      run:
                        shell:
                          command: ${ "echo " + .greeting + " " + .name }
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "shell can use expressions in arguments",
            mockConfig = TestMocks.shellConfig,
            yaml = $$"""
                do:
                  - setName:
                      set:
                        name: World
                  - echoName:
                      run:
                        shell:
                          command: echo
                          arguments:
                            "Hello": ${ .name }
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "shell can use expressions in environment variables",
            mockConfig = TestMocks.shellConfig,
            yaml = $$"""
                do:
                  - setVar:
                      set:
                        varValue: FromWorkflow
                  - printVar:
                      run:
                        shell:
                          command: sh
                          arguments:
                            "-c": echo $MY_VAR
                          environment:
                            MY_VAR: ${ .varValue }
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "shell can chain with other tasks",
            mockConfig = TestMocks.shellConfig,
            yaml = $$"""
                do:
                  - getInfo:
                      run:
                        shell:
                          command: echo TestData
                  - processInfo:
                      set:
                        data: ${ . }
                        hasData: true
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutput(
                buildJsonObject {
                    put("data", "Hello World")
                    put("hasData", true)
                }
            )
        ),

        WorkflowTestCase(
            name = "shell output can be transformed with output as",
            mockConfig = TestMocks.shellConfig,
            yaml = $$"""
                do:
                  - getInfo:
                      run:
                        shell:
                          command: echo "test output"
                      output:
                        as: '${ {result: .} }'
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutput(buildJsonObject { put("result", "Hello World") })
        ),

        WorkflowTestCase(
            name = "shell can execute multiple commands in sequence",
            mockConfig = TestMocks.shellConfig,
            yaml = $$"""
                do:
                  - first:
                      run:
                        shell:
                          command: echo First
                  - second:
                      run:
                        shell:
                          command: echo Second
                  - combine:
                      set:
                        result: ${ . }
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutput(buildJsonObject { put("result", "Hello World") })
        ),

        WorkflowTestCase(
            name = "shell can list files in directory",
            mockConfig = TestMocks.shellConfig,
            yaml = """
                do:
                  - listFiles:
                      run:
                        shell:
                          command: ls -1
                          arguments:
                            "": /tmp
            """.trimIndent(),
            tags = setOf("shell"),
            validate = expectOutputMatching("file list output") { output ->
                output is JsonPrimitive
            }
        )
    )
}
