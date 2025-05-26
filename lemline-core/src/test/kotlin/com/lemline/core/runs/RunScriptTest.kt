// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.runs

import com.lemline.core.getWorkflowInstance
import com.lemline.core.json.LemlineJson
import io.kotest.matchers.shouldBe
import io.serverlessworkflow.impl.WorkflowStatus
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class RunScriptTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testScriptFile: File

    @BeforeEach
    fun setup() {
        // Create a test script file
        testScriptFile = tempDir.resolve("test-script.js").toFile()
        testScriptFile.writeText(
            """
            function greet(name) {
                return 'Hello, ' + name + '!';
            }

            // For testing, we'll just use a hardcoded name
            console.log(greet('TestUser'));
            // Write some output to stderr for testing
            console.error('Script executed successfully');
        """.trimIndent()
        )
    }

    @AfterEach
    fun cleanup() {
        testScriptFile.delete()
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute inline JavaScript script`() = runTest {
        val workflowYaml = """
            do:
              - greet:
                  run:
                    script:
                      language: js
                      code: >
                        console.log('Hello, World!');
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains the expected stdout
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("Hello, World!"), "Output should contain 'Hello, World!' but was: $output")
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute external JavaScript script`() = runTest {
        val workflowYaml = """
            do:
              - runExternalScript:
                  run:
                    script:
                      language: js
                      source:
                        endpoint:
                          uri: "file://${testScriptFile.absolutePath}"
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        // Assert workflow completed successfully
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Assert the output contains the expected output
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("Hello, TestUser!"), "Output should contain 'Hello, TestUser!' but was: $output")
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle script execution errors`() = runTest {
        val workflowYaml = """
            do:
              - runFailingScript:
                  run:
                    script:
                      language: js
                      code: |
                        // This will cause a syntax error
                        const x
                        console.log('This will not run');
                    return: stderr
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        // The workflow should complete but the script should have failed
        instance.status shouldBe WorkflowStatus.COMPLETED

        // The output should contain the error message
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(
            output.contains("SyntaxError") || output.contains("Unexpected token"),
            "Output should contain a syntax error but was: $output"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute script with parameters`() = runTest {
        val workflowYaml = """
            do:
              - runWithParams:
                  run:
                    script:
                      language: js
                      code: >
                        const [param1, param2, param3, param4] = process.argv.slice(2);
                        console.log(`Param1: ` + param1);
                        console.log(`Param2: ` + param2);
                        console.log(`Param3: ` + param3);
                        console.log(`Param4: ` + param4);
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
    fun `should execute script with environment variables`() = runTest {
        val workflowYaml = """
            do:
              - runWithEnv:
                  run:
                    script:
                      language: js
                      code: >
                        console.log(`CUSTOM_VAR: `+ process.env.CUSTOM_VAR);
                        console.log(`ANOTHER_VAR: `+ process.env.ANOTHER_VAR);
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
    fun `should execute script with await true`() = runTest {
        val file = tempDir.resolve("async_complete.txt").absolutePathString()
        val workflowYaml = """
            do:
              - runAsync:
                  run:
                    script:
                      language: js
                      code: >
                        const fs = await import('fs');
                        fs.writeFileSync('$file', 'done');
              - verifyAsync:
                  run:
                    script:
                      language: js
                      code: >
                        const fs = await import('fs');
                        const fileExists = fs.existsSync('$file');
                        console.log(`File exists: ` + fileExists);
        """.trimIndent()

        println(workflowYaml)
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(
            output.contains("File exists: true"),
            "Async script should have created the file"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute script with await false`() = runTest {
        val file = tempDir.resolve("async_complete.txt").absolutePathString()
        val workflowYaml = """
            do:
              - runAsync:
                  run:
                    script:
                      language: js
                      code: >
                        const fs = await import('fs');
                        // wait for 100ms
                        await new Promise(resolve => setTimeout(resolve, 100));
                        fs.writeFileSync('$file', 'done');
                    await: false
              - verifyAsync:
                  run:
                    script:
                      language: js
                      code: >
                        const fs = await import('fs');
                        const fileExists = fs.existsSync('$file');
                        console.log(`File exists: ` + fileExists);
        """.trimIndent()

        println(workflowYaml)
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        println("Output: $output")
        assertTrue(
            output.contains("File exists: false"),
            "Async script should have created the file"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should return stderr output`() = runTest {
        val workflowYaml = """
            do:
              - runWithStderr:
                  run:
                    script:
                      language: js
                      code: >
                        console.error('This is an error message');
                        console.log('This is stdout');
                        console.error('Another error');
                    return: stderr
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(
            output.contains("This is an error message") &&
                output.contains("Another error") &&
                !output.contains("This is stdout"),
            "Output should contain only stderr messages but was: $output"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should return exit code`() = runTest {
        val workflowYaml = """
            do:
              - runWithExitCode:
                  run:
                    script:
                      language: js
                      code: >
                        process.exit(42);
                    return: code
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString().trim()
        assertTrue(
            output == "42",
            "Output should be the exit code 42 but was: $output"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should return all script outputs`() = runTest {
        val workflowYaml = """
            do:
              - runWithAllOutputs:
                  run:
                    script:
                      language: js
                      code: >
                        console.log('Standard output');
                        console.error('Error output');
                        process.exit(1);
                    return: all
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        // Accept both with and without trailing newlines
        val normalizedOutput = output.replace("\\n", "").replace("\n", "")
        assertTrue(
            normalizedOutput.contains("\"stdout\":\"Standard output\"") &&
                normalizedOutput.contains("\"stderr\":\"Error output\"") &&
                normalizedOutput.contains("\"code\":1"),
            "Output should contain all script outputs but was: $output"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle script errors with nonzero exit code`() = runTest {
        val workflowYaml = """
            do:
              - runWithError:
                  run:
                    script:
                      language: js
                      code: >
                        throw new Error('Intentional failure');
                    return: all
        """.trimIndent()

        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)
        instance.run()
        instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(
            output.contains("stderr") && output.contains("Intentional failure"),
            "Output should contain error information but was: $output"
        )
    }
}
