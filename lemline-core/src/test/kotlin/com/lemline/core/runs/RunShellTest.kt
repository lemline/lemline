// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.runs

import com.lemline.core.getWorkflowInstance
import com.lemline.core.json.LemlineJson
import io.kotest.matchers.shouldBe
import io.serverlessworkflow.impl.WorkflowStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class RunShellTest {

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run simple shell command with default return type`() = runTest {
        val workflowYaml = """
            do:
              - setName:
                  set:
                    name: World
              - echoHello:
                  run:
                    shell:
                      command: \"echo\"
                      arguments:
                        "Hello": .name
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains the expected stdout (default return type)
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("Hello World"))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `should run simple shell command with default return type on Windows`() = runTest {
        val workflowYaml = """
            do:
              - echoHello:
                  run:
                    shell:
                      command: cmd
                      arguments:
                        "/c": echo Hello World
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains the expected stdout
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("Hello World"))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run shell command with return type stdout`() = runTest {
        val workflowYaml = """
            do:
              - echoTest:
                  run:
                    shell:
                      command: echo Testing stdout
                    return: stdout
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output is a string with the stdout content
        assertEquals(JsonPrimitive("Testing stdout"), instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run shell command with return type stderr`() = runTest {
        val workflowYaml = """
            do:
              - errorCommand:
                  run:
                    shell:
                      command: sh
                      arguments:
                        "-c": echo Error message >&2
                    return: stderr
                    await: false
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains the stderr content
        assertEquals(JsonPrimitive("Error message"), instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run shell command with return type code`() = runTest {
        val workflowYaml = """
            do:
              - exitCommand:
                  run:
                    shell:
                      command: sh
                      arguments:
                        "-c": exit 42
                    return: code
                    await: false
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output is the exit code
        assertEquals(JsonPrimitive(42), instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run shell command with return type all`() = runTest {
        val workflowYaml = """
            do:
              - complexCommand:
                  run:
                    shell:
                      command: sh
                      arguments:
                        "-c": echo stdout; echo stderr >&2; exit 5
                    return: all
                    await: false
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains all fields
        val expectedOutput = mapOf(
            "code" to 5,
            "stdout" to "stdout",
            "stderr" to "stderr"
        )
        assertEquals(LemlineJson.encodeToElement(expectedOutput), instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run shell command with return type none`() = runTest {
        val workflowYaml = """
            do:
              - silentCommand:
                  run:
                    shell:
                      command: echo Nothing important
                    return: none
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output is null
        assertEquals(kotlinx.serialization.json.JsonNull, instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run shell command with arguments`() = runTest {
        val workflowYaml = """
            do:
              - echoArgs:
                  run:
                    shell:
                      command: echo
                      arguments:
                        "Hello": ""
                        "from": ""
                        "arguments": ""
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains the arguments
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("Hello"))
        assertTrue(output.contains("from"))
        assertTrue(output.contains("arguments"))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run shell command with environment variables`() = runTest {
        val workflowYaml = """
            do:
              - envTest:
                  run:
                    shell:
                      command: sh
                      arguments:
                        "-c": echo ${'$'}TEST_VAR
                      environment:
                        TEST_VAR: Hello Environment
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains the environment variable value
        assertEquals(JsonPrimitive("Hello Environment"), instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run shell command with expression evaluation`() = runTest {
        val workflowYaml = """
            do:
              - expressionEcho:
                  run:
                    shell:
                      command: echo ${'$'}{ .greeting }
        """
        val instance = getWorkflowInstance(workflowYaml, JsonPrimitive("Hello Expression"))

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains the evaluated expression
        assertEquals(JsonPrimitive("Hello Expression"), instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run shell command with expression in arguments`() = runTest {
        val workflowYaml = """
            do:
              - echoWithExpressions:
                  run:
                    shell:
                      command: echo
                      arguments:
                        "${'$'}{ .prefix }": ""
                        "${'$'}{ .suffix }": ""
        """
        val input = JsonObject(
            mapOf(
                "prefix" to JsonPrimitive("Hello"),
                "suffix" to JsonPrimitive("World")
            )
        )
        val instance = getWorkflowInstance(workflowYaml, input)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains both values
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("Hello"))
        assertTrue(output.contains("World"))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should run shell command with expression in environment`() = runTest {
        val workflowYaml = """
            do:
              - envExpressionTest:
                  run:
                    shell:
                      command: sh
                      arguments:
                        "-c": echo ${'$'}DYNAMIC_VAR
                      environment:
                        DYNAMIC_VAR: ${'$'}{ .value }
        """
        val instance = getWorkflowInstance(workflowYaml, JsonPrimitive("Dynamic Value"))

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains the dynamic environment variable
        assertEquals(JsonPrimitive("Dynamic Value"), instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle await false for failing command`() = runTest {
        val workflowYaml = """
            do:
              - failingCommand:
                  run:
                    shell:
                      command: sh
                      arguments:
                        "-c": exit 1
                    await: false
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully even though command failed
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output is empty stdout (since await=false, no exception thrown)
        assertEquals(JsonPrimitive(""), instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle complex shell pipeline`() = runTest {
        val workflowYaml = """
            do:
              - pipelineCommand:
                  run:
                    shell:
                      command: sh
                      arguments:
                        "-c": echo -e "line1\nline2\nline3" | grep line2
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains the filtered line
        assertEquals(JsonPrimitive("line2"), instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle multiple shell commands in sequence`() = runTest {
        val workflowYaml = """
            do:
              - firstCommand:
                  run:
                    shell:
                      command: echo First
              - secondCommand:
                  run:
                    shell:
                      command: echo Second
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the final output is from the last command
        assertEquals(JsonPrimitive("Second"), instance.rootInstance.transformedOutput)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle shell command with input data flow`() = runTest {
        val workflowYaml = """
            do:
              - processInput:
                  run:
                    shell:
                      command: echo
                      arguments:
                        "Processing:": ""
                        "${'$'}{ .data }": ""
        """
        val input = JsonObject(
            mapOf(
                "data" to JsonPrimitive("important-data")
            )
        )
        val instance = getWorkflowInstance(workflowYaml, input)

        // Run the workflow
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains processed input
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("Processing:"))
        assertTrue(output.contains("important-data"))
    }
}
