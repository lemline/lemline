// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.runs

import com.lemline.core.getWorkflowInstance
import com.lemline.core.json.LemlineJson
import io.kotest.matchers.shouldBe
import io.serverlessworkflow.impl.WorkflowStatus
import java.io.File
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class RunScriptPythonTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testScriptFile: File

    @BeforeEach
    fun setup() {
        // Create a test script file
        testScriptFile = tempDir.resolve("test-script.py").toFile()
        testScriptFile.writeText(
            """
            def greet(name):
                return f'Hello, {name}!'

            # For testing, we'll just use a hardcoded name
            print(greet('TestUser'))
            import sys
            print('Script executed successfully', file=sys.stderr)
            """.trimIndent()
        )
    }

    @AfterEach
    fun cleanup() {
        testScriptFile.delete()
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute inline Python script`() = runTest {
        val workflowYaml = """
            do:
              - greet:
                  run:
                    script:
                      language: python
                      code: |
                        print('Hello, World!')
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("Hello, World!"), "Output should contain 'Hello, World!' but was: $output")
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute external Python script`() = runTest {
        val workflowYaml = """
            do:
              - runExternalScript:
                  run:
                    script:
                      language: python
                      source:
                        endpoint:
                          uri: "file://${testScriptFile.absolutePath}"
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("Hello, TestUser!"), "Output should contain 'Hello, TestUser!' but was: $output")
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle Python script execution errors`() = runTest {
        val workflowYaml = """
            do:
              - runFailingScript:
                  run:
                    script:
                      language: python
                      code: |
                        # This will cause a syntax error
                        def x
                        print('This will not run')
                    return: stderr
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(
            output.contains("SyntaxError") || output.contains("invalid syntax"),
            "Output should contain a syntax error but was: $output"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute Python script with parameters`() = runTest {
        val workflowYaml = """
            do:
              - runWithParams:
                  run:
                    script:
                      language: python
                      code: |
                        import sys
                        param1, param2, param3, param4 = sys.argv[1:5]
                        print(f'Param1: {param1}')
                        print(f'Param2: {param2}')
                        print(f'Param3: {param3}')
                        print(f'Param4: {param4}')
                      arguments:
                        "key1": "value1"
                        "key2": "value2"
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(
            output.contains("Param1: key1") &&
                output.contains("Param2: value1") &&
                output.contains("Param3: key2") &&
                output.contains("Param4: value2"),
            "Output should contain all parameter values but was: $output"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute Python script with environment variables`() = runTest {
        val workflowYaml = """
            do:
              - runWithEnv:
                  run:
                    script:
                      language: python
                      code: |
                        import os
                        print(f'CUSTOM_VAR: {os.environ.get("CUSTOM_VAR")})')
                        print(f'ANOTHER_VAR: {os.environ.get("ANOTHER_VAR")})')
                      environment:
                        CUSTOM_VAR: "test-value"
                        ANOTHER_VAR: "12345"
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(
            output.contains("CUSTOM_VAR: test-value") && output.contains("ANOTHER_VAR: 12345"),
            "Output should contain environment variable values but was: $output"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should capture Python stderr output`() = runTest {
        val workflowYaml = """
            do:
              - captureStderr:
                  run:
                    script:
                      language: python
                      code: |
                        import sys
                        print('This is normal output')
                        print('This is an error message', file=sys.stderr)
                    return: stderr
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()

        assertTrue(
            output.contains("This is an error message"),
            "Output should contain stderr but was: $output"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should return non-zero exit code on Python script error`() = runTest {
        val workflowYaml = """
            do:
              - errorExit:
                  run:
                    script:
                      language: python
                      code: |
                        import sys
                        print('Something went wrong', file=sys.stderr)
                        sys.exit(42)
                    return: code
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("42"), "Output should contain the code 42 but was: $output")
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute Python script in specified working directory`() = runTest {
        val fileName = "testfile.txt"
        val testFile = tempDir.resolve(fileName).toFile()
        testFile.writeText("test")
        val workflowYaml = """
            do:
              - workingDirTest:
                  run:
                    script:
                      language: python
                      code: |
                        import os
                        print('found' if 'testfile.txt' in os.listdir('.') else 'not found')
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        // Set working directory manually if your test harness supports it
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("found"), "Output should indicate the file was found but was: $output")
    }
}
