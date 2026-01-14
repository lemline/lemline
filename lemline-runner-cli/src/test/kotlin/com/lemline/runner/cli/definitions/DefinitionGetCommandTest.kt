// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definitions

import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.workflows.WorkflowCache as Workflows
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.cli.common.InteractiveWorkflowSelector
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionService
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
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import picocli.CommandLine

@ExperimentalTime
@ExperimentalSerializationApi
class DefinitionGetCommandTest {

    private lateinit var command: DefinitionGetCommand
    private lateinit var mockedDefinitionService: DefinitionService
    private lateinit var mockedSelector: InteractiveWorkflowSelector
    private lateinit var mockedObjectMapper: ObjectMapper

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
        mockedDefinitionService = mockk()
        mockedSelector = mockk()
        mockedObjectMapper = mockk()

        // Create command and inject mocks
        command = DefinitionGetCommand().apply {
            definitionService = mockedDefinitionService
            selector = mockedSelector
            objectMapper = mockedObjectMapper
            mixin = GlobalMixin()
        }

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
            mockedDefinitionService.findByNameAndVersion(
                workflowNamespace,
                workflowName,
                workflowVersion
            )
        } returns workflowDefinition

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
            coVerify { mockedDefinitionService.findByNameAndVersion(workflowNamespace, workflowName, workflowVersion) }
        }

        @Test
        fun `should display error when workflow not found`() {
            // Given
            val nonExistentName = WorkflowName("nonExistentWorkflow")
            val nonExistentVersion = WorkflowVersion("9.9.9")
            coEvery {
                mockedDefinitionService.findByNameAndVersion(
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
                mockedDefinitionService.findByNameAndVersion(
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
            every { Workflows.parseYaml(workflowDefinition.definition) } returns workflow

            // Mock the entire chain of method calls
            every {
                mockedObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(any<Workflow>())
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
            verify { Workflows.parseYaml(workflowDefinition.definition) }
            verify { mockedObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(any<Workflow>()) }
        }
    }

    @Nested
    inner class InteractiveSelectionTests {
        @Test
        fun `should use selector when only namespace and name are provided`() {
            // Given
            val selectionList = listOf(1 to workflowDefinition)
            coEvery {
                mockedSelector.prepareSelection(
                    workflowNamespace,
                    filterName = workflowName
                )
            } returns selectionList

            // When
            val exitCode = cmd.execute(workflowNamespace.toString(), workflowName.toString())

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain workflowDefinition.definition
            coVerify { mockedSelector.prepareSelection(workflowNamespace, filterName = workflowName) }
        }

        @Test
        fun `should use selector when only namespace is provided`() {
            // Given
            val selectionList = listOf(1 to workflowDefinition)
            coEvery { mockedSelector.prepareSelection(workflowNamespace, filterName = null) } returns selectionList

            // When
            val exitCode = cmd.execute(workflowNamespace.toString())

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain workflowDefinition.definition
            coVerify { mockedSelector.prepareSelection(workflowNamespace, filterName = null) }
        }

        @Test
        fun `should list all workflows across namespaces when no parameters are provided`() {
            // Given
            val selectionList = listOf(1 to workflowDefinition)
            coEvery { mockedSelector.prepareSelection(null, filterName = null) } returns selectionList

            // When
            val exitCode = cmd.execute()

            // Then
            exitCode shouldBe 0
            outStream.toString() shouldContain workflowDefinition.definition
            coVerify { mockedSelector.prepareSelection(null, filterName = null) }
        }

        @Test
        fun `should handle empty selection list when no namespace provided`() {
            // Given
            coEvery { mockedSelector.prepareSelection(null, filterName = null) } returns null

            // When
            val exitCode = cmd.execute()

            // Then
            exitCode shouldBe 0
            // No output expected as selector already prints the "No workflows found" message
            coVerify { mockedSelector.prepareSelection(null, filterName = null) }
        }

        @Test
        fun `should handle empty selection list when namespace provided`() {
            // Given
            coEvery { mockedSelector.prepareSelection(workflowNamespace, filterName = null) } returns null

            // When
            val exitCode = cmd.execute(workflowNamespace.toString())

            // Then
            exitCode shouldBe 0
            // No output expected as selector already prints the "No workflows found" message
            coVerify { mockedSelector.prepareSelection(workflowNamespace, filterName = null) }
        }
    }
}
