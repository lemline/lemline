// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test cases for Shell execution.
 * Tests shell command execution with various return types and configurations.
 */
object RunShellTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "shell can execute simple command",
            yaml = """
                do:
                  - echoHello:
                      run:
                        shell:
                          command: echo Hello World
            """.trimIndent(),
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("Hello World") { output ->
                (output as? JsonPrimitive)?.content == "Hello World"
            }
        ),

        WorkflowTestCase(
            name = "shell can execute command with arguments",
            yaml = """
                do:
                  - echoWithArgs:
                      run:
                        shell:
                          command: echo
                          arguments:
                            "Hello": World
            """.trimIndent(),
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("Hello World") { output ->
                (output as? JsonPrimitive)?.content?.contains("Hello World") == true
            }
        ),

        WorkflowTestCase(
            name = "shell can use environment variables",
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
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("TestValue") { output ->
                (output as? JsonPrimitive)?.content == "TestValue"
            }
        ),

        WorkflowTestCase(
            name = "shell can return stdout",
            yaml = """
                do:
                  - echoStdout:
                      run:
                        shell:
                          command: echo Testing stdout
                        return: stdout
            """.trimIndent(),
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("Testing stdout") { output ->
                (output as? JsonPrimitive)?.content == "Testing stdout"
            }
        ),

        WorkflowTestCase(
            name = "shell can return stderr",
            yaml = """
                do:
                  - echoStderr:
                      run:
                        shell:
                          command: sh
                          arguments:
                            "-c": echo Error message >&2
                        return: stderr
            """.trimIndent(),
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("Error message") { output ->
                (output as? JsonPrimitive)?.content == "Error message"
            }
        ),

        WorkflowTestCase(
            name = "shell can return exit code",
            yaml = """
                do:
                  - exitCode:
                      run:
                        shell:
                          command: sh
                          arguments:
                            "-c": exit 42
                        return: code
            """.trimIndent(),
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("exit code 42") { output ->
                (output as? JsonPrimitive)?.int == 42
            }
        ),

        WorkflowTestCase(
            name = "shell can return all output",
            yaml = """
                do:
                  - allOutput:
                      run:
                        shell:
                          command: sh
                          arguments:
                            "-c": echo stdout && echo stderr >&2 && exit 5
                        return: all
            """.trimIndent(),
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("stdout, stderr, and code") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["stdout"]?.jsonPrimitive?.content == "stdout" &&
                    obj["stderr"]?.jsonPrimitive?.content == "stderr" &&
                    obj["code"]?.jsonPrimitive?.int == 5
            }
        ),

        WorkflowTestCase(
            name = "shell can use expressions in command",
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
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("Hello Alice") { output ->
                (output as? JsonPrimitive)?.content == "Hello Alice"
            }
        ),

        WorkflowTestCase(
            name = "shell can use expressions in arguments",
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
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("Hello World") { output ->
                (output as? JsonPrimitive)?.content?.contains("Hello World") == true
            }
        ),

        WorkflowTestCase(
            name = "shell can use expressions in environment variables",
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
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("FromWorkflow") { output ->
                (output as? JsonPrimitive)?.content == "FromWorkflow"
            }
        ),

        WorkflowTestCase(
            name = "shell can chain with other tasks",
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
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("data=TestData, hasData=true") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["data"]?.jsonPrimitive?.content == "TestData" &&
                    obj["hasData"]?.jsonPrimitive?.content == "true"
            }
        ),

        WorkflowTestCase(
            name = "shell output can be transformed with output as",
            yaml = $$"""
                do:
                  - getInfo:
                      run:
                        shell:
                          command: echo "test output"
                      output:
                        as: '${ {result: .} }'
            """.trimIndent(),
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("result=test output") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.content == "test output"
            }
        ),

        WorkflowTestCase(
            name = "shell can execute multiple commands in sequence",
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
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("result=Second") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.content == "Second"
            }
        ),

        WorkflowTestCase(
            name = "shell can list files in directory",
            yaml = """
                do:
                  - listFiles:
                      run:
                        shell:
                          command: ls -1
                          arguments:
                            "": /tmp
            """.trimIndent(),
            tags = setOf("unix-only", "shell"),
            validate = expectOutputMatching("non-empty output") { output ->
                (output as? JsonPrimitive)?.content?.isNotEmpty() == true
            }
        ),

        WorkflowTestCase(
            name = "shell can execute command on Windows",
            yaml = """
                do:
                  - echoWin:
                      run:
                        shell:
                          command: cmd
                          arguments:
                            "/c": echo Hello Windows
            """.trimIndent(),
            tags = setOf("windows-only", "shell"),
            validate = expectOutputMatching("Hello Windows") { output ->
                (output as? JsonPrimitive)?.content?.contains("Hello Windows") == true
            }
        )
    )
}
