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
}
