// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.core.workflows.WorkflowCache
import com.lemline.common.values.info
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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

    @Test
    fun `post commit invalidation preserves concurrently recreated definitions for every delete scope`() = runTest {
        val source = """
            document:
              dsl: '1.0.0'
              namespace: recreate
              name: trigger
              version: '1.0.0'
            schedule:
              on:
                one:
                  with: { type: old }
            do:
              - result:
                  set: { value: old }
        """.trimIndent()
        val model = DefinitionModel.from(source)
        val connection = mockk<java.sql.Connection>()
        coEvery { mockedRepository.findByNameAndVersion(any(), any(), any(), any()) } returns model
        coEvery { mockedRepository.listByName(any(), any(), any()) } returns listOf(model)
        coEvery { mockedRepository.listAllInNamespace(any(), any()) } returns listOf(model)
        coEvery { mockedRepository.countAllInNamespace(any(), any()) } returns 1
        coEvery { mockedListenService.removeListeners(any(), any(), any()) } returns 0
        coEvery { mockedRepository.delete(model, any()) } returns 1
        coEvery { mockedRepository.delete(listOf(model), any()) } returns 1
        coEvery { mockedRepository.deleteAllInNamespace(any(), any()) } returns 1
        coEvery { mockedDatabaseConfig.withTransaction<DefinitionService.DeleteResult>(any(), any()) } coAnswers {
            val result = secondArg<suspend (java.sql.Connection) -> DefinitionService.DeleteResult>()(connection)
            // A save publishes its new entry after deletion commits but before eviction runs.
            WorkflowCache.parseYamlAndPut(source.replace("old", "new"))
            result
        }
        for (scope in 0..2) {
            val original = WorkflowCache.parseYamlAndPut(source)
            when (scope) {
                0 -> service.delete(model.namespace, model.name, model.version)
                1 -> service.deleteAllVersions(model.namespace, model.name)
                else -> service.deleteAllInNamespace(model.namespace)
            }
            WorkflowCache.getEventSchedules(original.info.namespace).single()
                .consumption.filters.single().standard.type shouldBe "new"
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
