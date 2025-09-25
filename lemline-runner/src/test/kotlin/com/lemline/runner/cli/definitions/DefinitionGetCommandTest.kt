// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definitions

import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache as Workflows
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.cli.common.InteractiveWorkflowSelector
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.serverlessworkflow.api.types.Workflow
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Field
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import picocli.CommandLine

class DefinitionGetCommandTest {

    private lateinit var command: DefinitionGetCommand
    private lateinit var definitionRepository: DefinitionRepository
    private lateinit var selector: InteractiveWorkflowSelector
    private lateinit var objectMapper: ObjectMapper

    private var workflowNamespace = WorkflowNamespace("test")
    private var workflowName = WorkflowName("testWorkflow")
    private var workflowVersion = WorkflowVersion("1.0.0")
    private lateinit var workflowDefinition: DefinitionModel
    private lateinit var cmd: CommandLine
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream

    @BeforeEach
    fun setup() {
        // Create mocks
        definitionRepository = mockk()
        selector = mockk()
        objectMapper = mockk()

        // Create command and inject mocks
        command = DefinitionGetCommand()
        injectField(command, "definitionRepository", definitionRepository)
        injectField(command, "selector", selector)
        injectField(command, "objectMapper", objectMapper)
        injectField(command, "mixin", GlobalMixin())

        workflowNamespace = WorkflowNamespace("test")
        workflowName = WorkflowName("testWorkflow")
        workflowVersion = WorkflowVersion("1.0.0")
        workflowDefinition = DefinitionModel(
            namespace = workflowNamespace,
            name = workflowName,
            version = workflowVersion,
            definition = """
                document:
                  dsl: 1.0.0
                  namespace: $workflowNamespace
                  name: $workflowName
                  version: '$workflowVersion'
                do:
                  - wait30Seconds:
                      wait: PT30S
            """.trimIndent()
        )

        coEvery {
            definitionRepository.findByNameAndVersion(
                workflowNamespace,
                workflowName,
                workflowVersion
            )
        } returns workflowDefinition
        coEvery { definitionRepository.listByName(workflowNamespace, workflowName) } returns listOf(workflowDefinition)

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
    inner class DirectFetchTests {
        @Test
        fun `should fetch and display workflow when name and version are provided`() {
            // When
            val exitCode =
                cmd.execute(workflowNamespace.toString(), workflowName.toString(), workflowVersion.toString())

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain workflowDefinition.definition
            coVerify { definitionRepository.findByNameAndVersion(workflowNamespace, workflowName, workflowVersion) }
        }

        @Test
        fun `should display error when workflow not found`() {
            // Given
            val nonExistentName = WorkflowName("nonExistentWorkflow")
            val nonExistentVersion = WorkflowVersion("9.9.9")
            coEvery {
                definitionRepository.findByNameAndVersion(
                    workflowNamespace,
                    nonExistentName,
                    nonExistentVersion
                )
            } returns null

            // When
            val exitCode =
                cmd.execute(workflowNamespace.toString(), nonExistentName.toString(), nonExistentVersion.toString())

            // Then
            exitCode shouldBe 0 // Command doesn't throw, just prints error
            errStream.toString() shouldContain "not found"
            coVerify {
                definitionRepository.findByNameAndVersion(
                    workflowNamespace,
                    nonExistentName,
                    nonExistentVersion
                )
            }
        }

        @Test
        fun `should output in JSON format when requested`() {
            // Given
            val jsonOutput =
                """{"document":{"dsl":"1.0.0","namespace":"test","name":"testWorkflow","version":"1.0.0"},"do":[{"wait30Seconds":{"wait":"PT30S"}}]}"""
            val workflow = mockk<Workflow>()

            // Mock static method
            mockkStatic(Workflows::class)
            every { Workflows.parse(workflowDefinition.definition) } returns workflow

            // Mock the entire chain of method calls
            every {
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(any<Workflow>())
            } returns jsonOutput

            // When
            val exitCode = cmd.execute(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--format",
                "JSON"
            )

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain jsonOutput
            verify { Workflows.parse(workflowDefinition.definition) }
            verify { objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(any<Workflow>()) }
        }
    }

    @Nested
    inner class InteractiveSelectionTests {
        @Test
        fun `should use selector when only name is provided`() {
            // Given
            val selectionList = listOf(1 to workflowDefinition)
            coEvery { selector.prepareSelection(workflowNamespace, filterName = workflowName) } returns selectionList

            // When
            val exitCode = cmd.execute(workflowNamespace.toString(), workflowName.toString())

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain workflowDefinition.definition
            coVerify { selector.prepareSelection(workflowNamespace, filterName = workflowName) }
        }

        @Test
        fun `should use selector when no parameters are provided`() {
            // Given
            val selectionList = listOf(1 to workflowDefinition)
            coEvery { selector.prepareSelection(workflowNamespace, filterName = null) } returns selectionList

            // When
            val exitCode = cmd.execute(workflowNamespace.toString())

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain workflowDefinition.definition
            coVerify { selector.prepareSelection(workflowNamespace, filterName = null) }
        }

        @Test
        fun `should handle empty selection list`() {
            // Given
            coEvery { selector.prepareSelection(workflowNamespace, filterName = null) } returns null

            // When
            val exitCode = cmd.execute(workflowNamespace.toString())

            // Then
            exitCode shouldBe 0
            // No output expected as selector already prints the "No workflows found" message
            coVerify { selector.prepareSelection(workflowNamespace, filterName = null) }
        }
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
