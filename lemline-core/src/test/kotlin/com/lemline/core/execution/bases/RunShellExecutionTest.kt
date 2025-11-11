// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.bases

import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Integration tests for Shell execution using CompleteOrchestrator.
 *
 * Tests shell command execution to verify:
 * - Basic command execution
 * - Arguments and environment variables
 * - Different return types (stdout, stderr, code, all, none)
 * - Expression evaluation in commands
 * - Integration with workflow context
 */
@ExperimentalTime
abstract class RunShellExecutionTest : FunSpec() {

    init {
        test("shell can execute simple command").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - echoHello:
                      run:
                        shell:
                          command: echo Hello World
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("Hello World", (output as JsonPrimitive).content)
        }

        test("shell can execute command with arguments").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - echoWithArgs:
                      run:
                        shell:
                          command: echo
                          arguments:
                            "Hello": World
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertTrue((output as JsonPrimitive).content.contains("Hello World"))
        }

        test("shell can use environment variables").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
                do:
                  - printEnv:
                      run:
                        shell:
                          command: sh
                          arguments:
                            "-c": echo $MY_VAR
                          environment:
                            MY_VAR: TestValue
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("TestValue", (output as JsonPrimitive).content)
        }

        test("shell can return stdout").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - echoStdout:
                      run:
                        shell:
                          command: echo Testing stdout
                        return: stdout
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("Testing stdout", (output as JsonPrimitive).content)
        }

        test("shell can return stderr").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - echoStderr:
                      run:
                        shell:
                          command: sh
                          arguments:
                            "-c": echo Error message >&2
                        return: stderr
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("Error message", (output as JsonPrimitive).content)
        }

        test("shell can return exit code").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - exitCode:
                      run:
                        shell:
                          command: sh
                          arguments:
                            "-c": exit 42
                        return: code
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals(42, (output as JsonPrimitive).int)
        }

        test("shell can return all output").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - allOutput:
                      run:
                        shell:
                          command: sh
                          arguments:
                            "-c": echo stdout && echo stderr >&2 && exit 5
                        return: all
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("stdout", output["stdout"]?.jsonPrimitive?.content)
            assertEquals("stderr", output["stderr"]?.jsonPrimitive?.content)
            assertEquals(5, output["code"]?.jsonPrimitive?.int)
        }

        test("shell can use expressions in command").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
                do:
                  - setGreeting:
                      set:
                        greeting: Hello
                        name: Alice
                  - echoGreeting:
                      run:
                        shell:
                          command: ${ "echo " + .greeting + " " + .name }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("Hello Alice", (output as JsonPrimitive).content)
        }

        test("shell can use expressions in arguments").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
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
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertTrue((output as JsonPrimitive).content.contains("Hello World"))
        }

        test("shell can use expressions in environment variables").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
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
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("FromWorkflow", (output as JsonPrimitive).content)
        }

        test("shell can chain with other tasks").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
                do:
                  - getInfo:
                      run:
                        shell:
                          command: echo TestData
                  - processInfo:
                      set:
                        data: ${ . }
                        hasData: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            // Should have both data and hasData
            assertEquals("TestData", output["data"]?.jsonPrimitive?.content)
            assertEquals(true, output["hasData"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("shell output can be transformed with output as").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
                do:
                  - getInfo:
                      run:
                        shell:
                          command: echo "test output"
                      output:
                        as: '${ {result: .} }'
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("test output", output["result"]?.jsonPrimitive?.content)
        }

        test("shell can execute multiple commands in sequence").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
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
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            // Last shell output should be "Second"
            assertEquals("Second", output["result"]?.jsonPrimitive?.content)
        }

        test("shell can list files in directory").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - listFiles:
                      run:
                        shell:
                          command: ls -1
                          arguments:
                            "": /tmp
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            // Should return some output (directory listing)
            assertTrue((output as JsonPrimitive).content.isNotEmpty())
        }

        test("shell can execute command on Windows").config(enabledIf = {
            System.getProperty("os.name").lowercase().contains("windows")
        }) {
            val yaml = """
                do:
                  - echoWin:
                      run:
                        shell:
                          command: cmd
                          arguments:
                            "/c": echo Hello Windows
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertTrue((output as JsonPrimitive).content.contains("Hello Windows"))
        }
    }

    protected abstract suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0"
    ): JsonElement
}
