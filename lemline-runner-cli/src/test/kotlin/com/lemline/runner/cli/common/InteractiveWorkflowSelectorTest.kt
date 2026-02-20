// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.common

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionService
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InteractiveWorkflowSelectorTest {

    private lateinit var selector: InteractiveWorkflowSelector
    private lateinit var mockedDefinitionService: DefinitionService

    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream

    private val namespace1 = WorkflowNamespace("namespace1")
    private val namespace2 = WorkflowNamespace("namespace2")
    private val workflowName1 = WorkflowName("workflow-a")
    private val workflowName2 = WorkflowName("workflow-b")

    private val definition1v1 = DefinitionModel(
        namespace = namespace1,
        name = workflowName1,
        version = WorkflowVersion("1.0.0"),
        definition = "def1v1"
    )
    private val definition1v2 = DefinitionModel(
        namespace = namespace1,
        name = workflowName1,
        version = WorkflowVersion("2.0.0"),
        definition = "def1v2"
    )
    private val definition2v1 = DefinitionModel(
        namespace = namespace1,
        name = workflowName2,
        version = WorkflowVersion("1.0.0"),
        definition = "def2v1"
    )
    private val definitionNs2 = DefinitionModel(
        namespace = namespace2,
        name = workflowName1,
        version = WorkflowVersion("1.0.0"),
        definition = "defNs2"
    )

    @BeforeEach
    fun setup() {
        mockedDefinitionService = mockk()
        selector = InteractiveWorkflowSelector(mockedDefinitionService)

        // Capture stdout
        originalOut = System.out
        outStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
    }

    @AfterEach
    fun cleanup() {
        System.setOut(originalOut)
    }

    @Nested
    inner class PrepareSelectionTests {

        @Test
        fun `should list all workflows when no namespace provided`() = runTest {
            // Given
            val allWorkflows = listOf(definition1v1, definition1v2, definition2v1, definitionNs2)
            coEvery { mockedDefinitionService.listAll() } returns allWorkflows

            // When
            val result = selector.prepareSelection(workflowNamespace = null, filterName = null)

            // Then
            result shouldNotBe null
            result!! shouldHaveSize 4
            coVerify { mockedDefinitionService.listAll() }
        }

        @Test
        fun `should list workflows in namespace when namespace provided`() = runTest {
            // Given
            val namespaceWorkflows = listOf(definition1v1, definition1v2, definition2v1)
            coEvery { mockedDefinitionService.listAllInNamespace(namespace1) } returns namespaceWorkflows

            // When
            val result = selector.prepareSelection(workflowNamespace = namespace1, filterName = null)

            // Then
            result shouldNotBe null
            result!! shouldHaveSize 3
            coVerify { mockedDefinitionService.listAllInNamespace(namespace1) }
        }

        @Test
        fun `should list workflows by name when namespace and name provided`() = runTest {
            // Given
            val namedWorkflows = listOf(definition1v1, definition1v2)
            coEvery { mockedDefinitionService.listByName(namespace1, workflowName1) } returns namedWorkflows

            // When
            val result = selector.prepareSelection(workflowNamespace = namespace1, filterName = workflowName1)

            // Then
            result shouldNotBe null
            result!! shouldHaveSize 2
            coVerify { mockedDefinitionService.listByName(namespace1, workflowName1) }
        }

        @Test
        fun `should return null when no workflows found`() = runTest {
            // Given
            coEvery { mockedDefinitionService.listAll() } returns emptyList()

            // When
            val result = selector.prepareSelection(workflowNamespace = null, filterName = null)

            // Then
            result shouldBe null
            outStream.toString() shouldContain "No workflows found"
        }

        @Test
        fun `should return null with filter message when no workflows match name filter`() = runTest {
            // Given
            val nonExistentName = WorkflowName("non-existent")
            coEvery { mockedDefinitionService.listByName(namespace1, nonExistentName) } returns emptyList()

            // When
            val result = selector.prepareSelection(workflowNamespace = namespace1, filterName = nonExistentName)

            // Then
            result shouldBe null
            outStream.toString() shouldContain "No workflows found matching name 'non-existent'"
        }
    }

    @Nested
    inner class OutputFormatTests {

        @Test
        fun `should display namespace column in output`() = runTest {
            // Given
            val allWorkflows = listOf(definition1v1, definitionNs2)
            coEvery { mockedDefinitionService.listAll() } returns allWorkflows

            // When
            selector.prepareSelection(workflowNamespace = null, filterName = null)

            // Then
            val output = outStream.toString()
            output shouldContain "Namespace"
            output shouldContain "Name"
            output shouldContain "Version"
            output shouldContain "namespace1"
            output shouldContain "namespace2"
        }

        @Test
        fun `should display all three columns with proper headers`() = runTest {
            // Given
            coEvery { mockedDefinitionService.listAllInNamespace(namespace1) } returns listOf(definition1v1)

            // When
            selector.prepareSelection(workflowNamespace = namespace1, filterName = null)

            // Then
            val output = outStream.toString()
            output shouldContain "#"
            output shouldContain "Namespace"
            output shouldContain "Name"
            output shouldContain "Version"
        }

        @Test
        fun `should group multiple versions under same namespace and name`() = runTest {
            // Given
            val workflows = listOf(definition1v1, definition1v2)
            coEvery { mockedDefinitionService.listByName(namespace1, workflowName1) } returns workflows

            // When
            val result = selector.prepareSelection(workflowNamespace = namespace1, filterName = workflowName1)

            // Then
            result shouldNotBe null
            result!! shouldHaveSize 2
            val output = outStream.toString()
            output shouldContain "1.0.0"
            output shouldContain "2.0.0"
        }

        @Test
        fun `should number items sequentially across all groups`() = runTest {
            // Given
            val workflows = listOf(definition1v1, definition1v2, definition2v1)
            coEvery { mockedDefinitionService.listAllInNamespace(namespace1) } returns workflows

            // When
            val result = selector.prepareSelection(workflowNamespace = namespace1, filterName = null)

            // Then
            result shouldNotBe null
            result!! shouldHaveSize 3
            result[0].first shouldBe 1
            result[1].first shouldBe 2
            result[2].first shouldBe 3
        }
    }

    @Nested
    inner class SortingTests {

        @Test
        fun `should sort workflows by namespace then name then version`() = runTest {
            // Given - workflows in random order
            val workflows = listOf(definitionNs2, definition2v1, definition1v2, definition1v1)
            coEvery { mockedDefinitionService.listAll() } returns workflows

            // When
            val result = selector.prepareSelection(workflowNamespace = null, filterName = null)

            // Then
            result shouldNotBe null
            result!! shouldHaveSize 4
            // Should be sorted: namespace1/workflow-a (1.0.0, 2.0.0), namespace1/workflow-b, namespace2/workflow-a
        }

        @Test
        fun `should sort versions using semver`() = runTest {
            // Given
            val v3 = DefinitionModel(namespace1, workflowName1, WorkflowVersion("3.0.0"), "v3")
            val v10 = DefinitionModel(namespace1, workflowName1, WorkflowVersion("10.0.0"), "v10")
            val workflows = listOf(v10, definition1v1, v3, definition1v2)
            coEvery { mockedDefinitionService.listByName(namespace1, workflowName1) } returns workflows

            // When
            val result = selector.prepareSelection(workflowNamespace = namespace1, filterName = workflowName1)

            // Then
            result shouldNotBe null
            result!! shouldHaveSize 4
            // Versions should be sorted: 1.0.0, 2.0.0, 3.0.0, 10.0.0 (not lexicographically)
            result[0].second.version shouldBe WorkflowVersion("1.0.0")
            result[1].second.version shouldBe WorkflowVersion("2.0.0")
            result[2].second.version shouldBe WorkflowVersion("3.0.0")
            result[3].second.version shouldBe WorkflowVersion("10.0.0")
        }
    }

    @Nested
    inner class SelectionListTests {

        @Test
        fun `should return correct definition model for each number`() = runTest {
            // Given
            val workflows = listOf(definition1v1, definition1v2, definition2v1)
            coEvery { mockedDefinitionService.listAllInNamespace(namespace1) } returns workflows

            // When
            val result = selector.prepareSelection(workflowNamespace = namespace1, filterName = null)

            // Then
            result shouldNotBe null
            result!!.find { it.first == 1 }?.second shouldBe definition1v1
            result.find { it.first == 2 }?.second shouldBe definition1v2
            result.find { it.first == 3 }?.second shouldBe definition2v1
        }

        @Test
        fun `should handle single workflow`() = runTest {
            // Given
            coEvery { mockedDefinitionService.listAllInNamespace(namespace1) } returns listOf(definition1v1)

            // When
            val result = selector.prepareSelection(workflowNamespace = namespace1, filterName = null)

            // Then
            result shouldNotBe null
            result!! shouldHaveSize 1
            result[0].first shouldBe 1
            result[0].second shouldBe definition1v1
        }
    }
}
