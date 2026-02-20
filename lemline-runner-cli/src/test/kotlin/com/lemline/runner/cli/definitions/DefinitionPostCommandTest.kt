// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definitions

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.workflows.WorkflowCache as Workflows
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionService
import com.lemline.runner.definitions.DefinitionService.SaveResult
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.serverlessworkflow.api.types.Workflow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.time.ExperimentalTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine

@ExperimentalTime
class DefinitionPostCommandTest {

    private lateinit var command: DefinitionPostCommand
    private lateinit var mockedDefinitionService: DefinitionService
    private lateinit var cmd: CommandLine
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream

    @TempDir
    lateinit var tempDir: File

    private var workflowNamespace = WorkflowNamespace("test")
    private var workflowName = WorkflowName("testWorkflow")
    private var workflowVersion = WorkflowVersion("1.0.0")
    private lateinit var workflowDefinition: String
    private lateinit var workflowModel: DefinitionModel
    private lateinit var workflow: Workflow

    @BeforeEach
    fun setup() {
        // Create mocks
        mockedDefinitionService = mockk()

        // Mock static methods
        mockkStatic(Workflows::class)
        mockkObject(DefinitionModel.Companion)

        // Create command and inject mocks
        command = DefinitionPostCommand().apply {
            definitionService = mockedDefinitionService
            mixin = GlobalMixin()
        }

        workflowNamespace = WorkflowNamespace("test")
        workflowName = WorkflowName("testWorkflow")
        workflowVersion = WorkflowVersion("1.0.0")
        workflowDefinition = """
            document:
              dsl: 1.0.0
              namespace: test
              name: $workflowName
              version: '$workflowVersion'
            do:
              - wait30Seconds:
                  wait: PT30S
        """.trimIndent()

        workflow = mockk(relaxed = true)

        workflowModel = DefinitionModel(
            namespace = workflowNamespace,
            name = workflowName,
            version = workflowVersion,
            definition = workflowDefinition
        )

        // Save original streams
        originalOut = System.out
        originalErr = System.err

        // Set up capture streams
        outStream = ByteArrayOutputStream()
        errStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
        System.setErr(PrintStream(errStream))

        cmd = CommandLine(command)
    }

    @AfterEach
    fun cleanup() {
        // Restore original streams
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    @Nested
    inner class SingleFileTests {
        private lateinit var workflowFile: File

        @BeforeEach
        fun setupFile() {
            workflowFile = File(tempDir, "workflow.yaml")
            workflowFile.writeText(workflowDefinition)

            // Mock DefinitionModel.from(String) to return our workflowModel
            every { DefinitionModel.from(any<String>()) } returns workflowModel
        }

        @Test
        fun `should create workflow from file`() {
            // Given
            coEvery { mockedDefinitionService.save(workflowModel, false) } returns SaveResult.CREATED

            // When
            val exitCode = cmd.execute("--file", workflowFile.absolutePath)

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain "successfully created"
            coVerify { mockedDefinitionService.save(workflowModel, false) }
        }

        @Test
        fun `should handle existing workflow without force flag`() {
            // Given
            coEvery { mockedDefinitionService.save(workflowModel, false) } returns SaveResult.ALREADY_EXISTS

            // When
            val exitCode = cmd.execute("--file", workflowFile.absolutePath)

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain "already exists"
            coVerify { mockedDefinitionService.save(workflowModel, false) }
        }

        @Test
        fun `should update existing workflow with force flag`() {
            // Given
            coEvery { mockedDefinitionService.save(workflowModel, true) } returns SaveResult.UPDATED

            // When
            val exitCode = cmd.execute("--file", workflowFile.absolutePath, "--force")

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain "successfully updated"
            coVerify { mockedDefinitionService.save(workflowModel, true) }
        }

        @Test
        fun `should handle non-existent file`() {
            // Given
            val nonExistentFile = File(tempDir, "non-existent.yaml")

            // When
            val exitCode = cmd.execute("--file", nonExistentFile.absolutePath)

            // Then
            exitCode shouldBeGreaterThan 0 // We expect a non-zero exit code for errors
            errStream.toString() shouldContain "does not exist"
        }
    }

    @Nested
    inner class DirectoryTests {
        private lateinit var workflowDir: File
        private lateinit var workflowFile1: File
        private lateinit var workflowFile2: File
        private lateinit var nestedDir: File
        private lateinit var nestedFile: File

        @BeforeEach
        fun setupDirectory() {
            workflowDir = File(tempDir, "workflows")
            workflowDir.mkdir()

            workflowFile1 = File(workflowDir, "workflow1.yaml")
            workflowFile1.writeText(workflowDefinition)

            workflowFile2 = File(workflowDir, "workflow2.yaml")
            workflowFile2.writeText(workflowDefinition)

            nestedDir = File(workflowDir, "nested")
            nestedDir.mkdir()

            nestedFile = File(nestedDir, "nested-workflow.yaml")
            nestedFile.writeText(workflowDefinition)

            // Mock DefinitionModel.from(String) and DefinitionService.save()
            every { DefinitionModel.from(any<String>()) } returns workflowModel
            coEvery { mockedDefinitionService.save(workflowModel, false) } returns SaveResult.CREATED
        }

        @Test
        fun `should process files in directory`() {
            // When
            val exitCode = cmd.execute("--directory", workflowDir.absolutePath)

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain "Processing files in directory"
            // Should process 2 files in the main directory but not the nested one
            coVerify(exactly = 2) { mockedDefinitionService.save(workflowModel, false) }
        }

        @Test
        fun `should process files recursively with recursive flag`() {
            // When
            val exitCode = cmd.execute("--directory", workflowDir.absolutePath, "--recursive")

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain "recursively"
            // Should process all 3 files (2 in main dir + 1 in nested dir)
            coVerify(exactly = 3) { mockedDefinitionService.save(workflowModel, false) }
        }

        @Test
        fun `should handle non-existent directory`() {
            // Given
            val nonExistentDir = File(tempDir, "non-existent-dir")

            // When
            val exitCode = cmd.execute("--directory", nonExistentDir.absolutePath)

            // Then
            exitCode shouldBeGreaterThan 0 // We expect a non-zero exit code for errors
            errStream.toString() shouldContain "does not exist"
        }
    }

    @Test
    fun `should require at least one source`() {
        // When
        val exitCode = cmd.execute()

        // Then
        exitCode shouldBeGreaterThan 0 // We expect a parameter validation exit code (2)
        errStream.toString() shouldContain "You must specify at least one file"
    }

    @Test
    fun `should require directory with recursive flag`() {
        // When
        val exitCode = cmd.execute("--recursive")

        // Then
        exitCode shouldBeGreaterThan 0 // We expect a parameter validation exit code (2)
        errStream.toString() shouldContain "can only be used with"
    }
}
