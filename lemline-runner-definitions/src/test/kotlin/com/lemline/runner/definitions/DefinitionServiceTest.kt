// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.listeners.DefinitionListenService
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@ExperimentalTime
@ExperimentalSerializationApi
class DefinitionServiceTest {

    private lateinit var service: DefinitionService
    private lateinit var mockedRepository: DefinitionRepository
    private lateinit var mockedListenService: DefinitionListenService
    private lateinit var mockedDatabaseConfig: DatabaseConfig

    private val namespace1 = WorkflowNamespace("namespace1")
    private val namespace2 = WorkflowNamespace("namespace2")
    private val workflowName = WorkflowName("test-workflow")

    private val definition1 = DefinitionModel(
        namespace = namespace1,
        name = workflowName,
        version = WorkflowVersion("1.0.0"),
        definition = "def1"
    )
    private val definition2 = DefinitionModel(
        namespace = namespace2,
        name = workflowName,
        version = WorkflowVersion("1.0.0"),
        definition = "def2"
    )
    private val definition3 = DefinitionModel(
        namespace = namespace1,
        name = WorkflowName("another-workflow"),
        version = WorkflowVersion("2.0.0"),
        definition = "def3"
    )

    @BeforeEach
    fun setup() {
        mockedRepository = mockk()
        mockedListenService = mockk()
        mockedDatabaseConfig = mockk()

        service = DefinitionService().apply {
            val repoField = DefinitionService::class.java.getDeclaredField("definitionRepository")
            repoField.isAccessible = true
            repoField.set(this, mockedRepository)

            val listenField = DefinitionService::class.java.getDeclaredField("definitionListenService")
            listenField.isAccessible = true
            listenField.set(this, mockedListenService)

            val dbField = DefinitionService::class.java.getDeclaredField("databaseConfig")
            dbField.isAccessible = true
            dbField.set(this, mockedDatabaseConfig)
        }
    }

    @Nested
    inner class ListAllTests {

        @Test
        fun `listAll should return all workflows across namespaces`() = runTest {
            // Given
            val allWorkflows = listOf(definition1, definition2, definition3)
            coEvery { mockedRepository.listAll(null) } returns allWorkflows

            // When
            val result = service.listAll()

            // Then
            result shouldHaveSize 3
            coVerify { mockedRepository.listAll(null) }
        }

        @Test
        fun `listAll should return empty list when no workflows exist`() = runTest {
            // Given
            coEvery { mockedRepository.listAll(null) } returns emptyList()

            // When
            val result = service.listAll()

            // Then
            result shouldHaveSize 0
            coVerify { mockedRepository.listAll(null) }
        }

        @Test
        fun `listAll should delegate to repository`() = runTest {
            // Given - repository returns sorted results (sorting is handled by repository)
            val sortedWorkflows = listOf(definition3, definition1, definition2)
            coEvery { mockedRepository.listAll(null) } returns sortedWorkflows

            // When
            val result = service.listAll()

            // Then
            result shouldHaveSize 3
            result shouldBe sortedWorkflows
            coVerify { mockedRepository.listAll(null) }
        }
    }

    @Nested
    inner class ListAllInNamespaceTests {

        @Test
        fun `listAllInNamespace should return workflows only from specified namespace`() = runTest {
            // Given
            val namespaceWorkflows = listOf(definition1, definition3)
            coEvery { mockedRepository.listAllInNamespace(namespace1, null) } returns namespaceWorkflows

            // When
            val result = service.listAllInNamespace(namespace1)

            // Then
            result shouldHaveSize 2
            result.all { it.namespace == namespace1 } shouldBe true
            coVerify { mockedRepository.listAllInNamespace(namespace1, null) }
        }

        @Test
        fun `listAllInNamespace should return empty list when namespace has no workflows`() = runTest {
            // Given
            val emptyNamespace = WorkflowNamespace("empty")
            coEvery { mockedRepository.listAllInNamespace(emptyNamespace, null) } returns emptyList()

            // When
            val result = service.listAllInNamespace(emptyNamespace)

            // Then
            result shouldHaveSize 0
            coVerify { mockedRepository.listAllInNamespace(emptyNamespace, null) }
        }
    }

    @Nested
    inner class ListByNameTests {

        @Test
        fun `listByName should return all versions of a workflow`() = runTest {
            // Given
            val v1 = definition1
            val v2 = definition1.copy(version = WorkflowVersion("2.0.0"), definition = "def-v2")
            coEvery { mockedRepository.listByName(namespace1, workflowName, null) } returns listOf(v1, v2)

            // When
            val result = service.listByName(namespace1, workflowName)

            // Then
            result shouldHaveSize 2
            result.all { it.name == workflowName } shouldBe true
            coVerify { mockedRepository.listByName(namespace1, workflowName, null) }
        }

        @Test
        fun `listByName should return empty list when workflow does not exist`() = runTest {
            // Given
            val nonExistentName = WorkflowName("non-existent")
            coEvery { mockedRepository.listByName(namespace1, nonExistentName, null) } returns emptyList()

            // When
            val result = service.listByName(namespace1, nonExistentName)

            // Then
            result shouldHaveSize 0
            coVerify { mockedRepository.listByName(namespace1, nonExistentName, null) }
        }
    }
}
