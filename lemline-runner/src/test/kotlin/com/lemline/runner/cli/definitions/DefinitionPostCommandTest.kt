// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definitions

import com.lemline.core.workflows.Workflows
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.verify
import io.serverlessworkflow.api.types.Workflow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.Field
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine

class DefinitionPostCommandTest {

    private lateinit var command: DefinitionPostCommand
    private lateinit var definitionRepository: DefinitionRepository
    private lateinit var cmd: CommandLine
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream

    @TempDir
    lateinit var tempDir: File

    private lateinit var workflowName: String
    private lateinit var workflowVersion: String
    private lateinit var workflowDefinition: String
    private lateinit var workflowModel: DefinitionModel
    private lateinit var workflow: Workflow

    @BeforeEach
    fun setup() {
        // Create mocks
        definitionRepository = mockk()

        // Mock static methods
        mockkStatic(Workflows::class)
        mockkObject(DefinitionModel.Companion)

        // Create command and inject mocks
        command = DefinitionPostCommand()
        injectField(command, "definitionRepository", definitionRepository)
        injectField(command, "mixin", GlobalMixin())

        workflowName = "testWorkflow"
        workflowVersion = "1.0.0"
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

            // Mock the entire chain of calls
            every { Workflows.parse(any()) } returns workflow
            every { DefinitionModel.from(any()) } returns workflowModel
        }

        @Test
        fun `should create workflow from file`() {
            // Given
            every { definitionRepository.insert(workflowModel) } returns 1

            // When
            val exitCode = cmd.execute("--file", workflowFile.absolutePath)

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain "successfully created"
            verify { definitionRepository.insert(workflowModel) }
        }

        @Test
        fun `should handle existing workflow without force flag`() {
            // Given
            every { definitionRepository.insert(workflowModel) } returns 0

            // When
            val exitCode = cmd.execute("--file", workflowFile.absolutePath)

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain "already exists"
            verify { definitionRepository.insert(workflowModel) }
            verify(exactly = 0) { definitionRepository.update(any<DefinitionModel>()) }
        }

        @Test
        fun `should update existing workflow with force flag`() {
            // Given
            every { definitionRepository.insert(workflowModel) } returns 0
            every { definitionRepository.update(workflowModel) } returns 1

            // When
            val exitCode = cmd.execute("--file", workflowFile.absolutePath, "--force")

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain "successfully updated"
            verify { definitionRepository.insert(workflowModel) }
            verify { definitionRepository.update(workflowModel) }
        }

        @Test
        fun `should handle non-existent file`() {
            // Given
            val nonExistentFile = File(tempDir, "non-existent.yaml")

            // When
            val exitCode = cmd.execute("--file", nonExistentFile.absolutePath)
            
            // Then
            exitCode shouldBe 1 // We expect a non-zero exit code for errors
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

            // Mock the entire chain of calls
            every { Workflows.parse(any()) } returns workflow
            every { DefinitionModel.from(any()) } returns workflowModel
            every { definitionRepository.insert(workflowModel) } returns 1
        }

        @Test
        fun `should process files in directory`() {
            // When
            val exitCode = cmd.execute("--directory", workflowDir.absolutePath)

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain "Processing files in directory"
            // Should process 2 files in the main directory but not the nested one
            verify(exactly = 2) { definitionRepository.insert(workflowModel) }
        }

        @Test
        fun `should process files recursively with recursive flag`() {
            // When
            val exitCode = cmd.execute("--directory", workflowDir.absolutePath, "--recursive")

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain "recursively"
            // Should process all 3 files (2 in main dir + 1 in nested dir)
            verify(exactly = 3) { definitionRepository.insert(workflowModel) }
        }

        @Test
        fun `should handle non-existent directory`() {
            // Given
            val nonExistentDir = File(tempDir, "non-existent-dir")

            // When
            val exitCode = cmd.execute("--directory", nonExistentDir.absolutePath)
            
            // Then
            exitCode shouldBe 1 // We expect a non-zero exit code for errors
            errStream.toString() shouldContain "does not exist"
        }
    }

    @Test
    fun `should require at least one source`() {
        // When
        val exitCode = cmd.execute()
        
        // Then
        exitCode shouldBe 2 // We expect a parameter validation exit code (2)
        errStream.toString() shouldContain "You must specify at least one file"
    }

    @Test
    fun `should require directory with recursive flag`() {
        // When
        val exitCode = cmd.execute("--recursive")
        
        // Then
        exitCode shouldBe 2 // We expect a parameter validation exit code (2)
        errStream.toString() shouldContain "can only be used with"
    }

    // Helper method to inject dependencies using reflection
    private fun injectField(target: Any, fieldName: String, value: Any) {
        val field = findField(target.javaClass, fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    // Helper method to find a field in a class or its superclasses
    private fun findField(clazz: Class<*>, fieldName: String): Field {
        try {
            return clazz.getDeclaredField(fieldName)
        } catch (e: NoSuchFieldException) {
            val superClass = clazz.superclass
            if (superClass != null) {
                return findField(superClass, fieldName)
            }
            throw e
        }
    }
}
