// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.complete

import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

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
class RunShellExecutionTest {

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can execute simple command`() = runTest {
        val yaml = """
            do:
              - echoHello:
                  run:
                    shell:
                      command: echo Hello World
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("Hello World", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can execute command with arguments`() = runTest {
        val yaml = """
            do:
              - echoWithArgs:
                  run:
                    shell:
                      command: echo
                      arguments:
                        "Hello": World
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertTrue((output as JsonPrimitive).content.contains("Hello World"))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can use environment variables`() = runTest {
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("TestValue", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can return stdout`() = runTest {
        val yaml = """
            do:
              - echoStdout:
                  run:
                    shell:
                      command: echo Testing stdout
                    return: stdout
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("Testing stdout", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can return stderr`() = runTest {
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("Error message", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can return exit code`() = runTest {
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals(42, (output as JsonPrimitive).int)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can return all output`() = runTest {
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("stdout", output["stdout"]?.jsonPrimitive?.content)
        assertEquals("stderr", output["stderr"]?.jsonPrimitive?.content)
        assertEquals(5, output["code"]?.jsonPrimitive?.int)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can use expressions in command`() = runTest {
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("Hello Alice", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can use expressions in arguments`() = runTest {
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertTrue((output as JsonPrimitive).content.contains("Hello World"))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can use expressions in environment variables`() = runTest {
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("FromWorkflow", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can chain with other tasks`() = runTest {
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Should have both data and hasData
        assertEquals("TestData", output["data"]?.jsonPrimitive?.content)
        assertEquals(true, output["hasData"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell output can be transformed with output as`() = runTest {
        val yaml = $$"""
            do:
              - getInfo:
                  run:
                    shell:
                      command: echo "test output"
                  output:
                    as: '${ {result: .} }'
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("test output", output["result"]?.jsonPrimitive?.content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can execute multiple commands in sequence`() = runTest {
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Last shell output should be "Second"
        assertEquals("Second", output["result"]?.jsonPrimitive?.content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `shell can list files in directory`() = runTest {
        val yaml = """
            do:
              - listFiles:
                  run:
                    shell:
                      command: ls -1
                      arguments:
                        "": /tmp
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // Should return some output (directory listing)
        assertTrue((output as JsonPrimitive).content.isNotEmpty())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `shell can execute command on Windows`() = runTest {
        val yaml = """
            do:
              - echoWin:
                  run:
                    shell:
                      command: cmd
                      arguments:
                        "/c": echo Hello Windows
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertTrue((output as JsonPrimitive).content.contains("Hello Windows"))
    }
}
