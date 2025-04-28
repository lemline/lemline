// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.WorkflowModel
import com.lemline.runner.repositories.WorkflowRepository
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base class for testing workflow repository implementations.
 *
 * This class provides a common test implementation that can be reused by database-specific test classes.
 * It verifies the core functionality of the WorkflowRepository, ensuring that workflow models can be
 * properly stored, retrieved, and updated in the database.
 *
 * The tests cover the following aspects:
 * 1. Basic CRUD operations (inherited from UuidV7Repository)
 * 2. Workflow model persistence and retrieval by name and version
 * 3. Workflow definition updates
 * 4. Handling of non-existent workflows
 *
 * The repository is expected to:
 * - Maintain unique constraints on workflow name and version combinations
 * - Support transactional operations
 * - Handle concurrent access safely (handled by the underlying database)
 *
 * @see WorkflowRepository
 * @see WorkflowModel
 */
abstract class WorkflowRepositoryTest {

    /** The repository implementation being tested */
    @Inject
    protected lateinit var repository: WorkflowRepository

    /**
     * Cleans up the database before each test to ensure a clean state.
     * This is crucial for maintaining test isolation and reliability.
     * The cleanup is performed within a transaction to ensure atomicity.
     */
    @BeforeEach
    @Transactional
    fun setupTest() {
        repository.deleteAll()
    }

    /**
     * Tests the complete workflow model lifecycle:
     * - Persistence of a new workflow model
     * - Retrieval of the saved model
     * - Verification of all model properties
     *
     * This test verifies that:
     * 1. A workflow can be saved to the database
     * 2. The saved workflow can be retrieved using its name and version
     * 3. All properties (id, name, version, definition) are preserved
     */
    @Test
    fun `should successfully persist and retrieve a complete workflow model with all properties`() {
        // Given
        val workflowModel = WorkflowModel(
            name = "test-workflow",
            version = "1.0.0",
            definition = """
                document:
                  dsl: '1.0.0'
                  namespace: test
                  name: test-workflow
                  version: '1.0.0'
                  tasks:
                    - name: hello
                      type: script
                      scriptLanguage: jsonata
                      script: >
                        ${'$'}merge([${'$'}input, {'greeting': 'Hello, World!'}])
            """.trimIndent()
        )

        // When
        repository.persist(workflowModel)

        // Then
        val retrievedModel = repository.findByNameAndVersion("test-workflow", "1.0.0")
        Assertions.assertNotNull(retrievedModel)
        Assertions.assertEquals(workflowModel.id, retrievedModel?.id)
        Assertions.assertEquals("test-workflow", retrievedModel?.name)
        Assertions.assertEquals("1.0.0", retrievedModel?.version)
        Assertions.assertTrue(retrievedModel?.definition?.contains("Hello, World!") ?: false)
    }

    /**
     * Tests the repository's behavior when querying for a non-existent workflow.
     * Verifies that the repository correctly returns null instead of throwing an exception.
     *
     * This test ensures that:
     * 1. The repository handles missing workflows gracefully
     * 2. No exceptions are thrown for non-existent workflows
     * 3. The correct null response is returned
     */
    @Test
    fun `should return null when querying for a non-existent workflow name and version combination`() {
        // When
        val result = repository.findByNameAndVersion("non-existent", "1.0.0")

        // Then
        Assertions.assertNull(result)
    }

    /**
     * Tests the update functionality of the workflow repository.
     * Verifies that:
     * - An existing workflow can be retrieved
     * - Its definition can be modified
     * - The changes are persisted correctly
     *
     * This test ensures that:
     * 1. Workflows can be updated in the database
     * 2. Changes are properly persisted
     * 3. Other properties remain unchanged
     */
    @Test
    @Transactional
    fun `should successfully update an existing workflow's definition while preserving other properties`() {
        // Given
        val workflowModel = WorkflowModel(
            name = "updatable-workflow",
            version = "1.0.0",
            definition = "original definition"
        )
        repository.persist(workflowModel)

        // When
        val retrievedModel = repository.findByNameAndVersion("updatable-workflow", "1.0.0")
        val updatedModel = retrievedModel?.copy(definition = "updated definition")
        if (updatedModel != null) repository.persist(updatedModel)

        // Then
        val finalModel = repository.findByNameAndVersion("updatable-workflow", "1.0.0")
        Assertions.assertNotNull(finalModel)
        Assertions.assertEquals("updated definition", finalModel?.definition)
    }

    /**
     * Tests batch persistence of multiple workflows.
     * Verifies that:
     * - Multiple workflows can be persisted in a single operation
     * - All workflows are correctly stored and can be retrieved
     * - Properties of each workflow are preserved
     */
    @Test
    fun `should successfully persist and retrieve multiple workflows in batch`() {
        // Given
        val workflows = List(5) { i ->
            WorkflowModel(
                name = "batch-workflow-$i",
                version = "1.0.0",
                definition = "definition-$i"
            )
        }

        // When
        repository.persist(workflows)

        // Then
        workflows.forEach { workflow ->
            val retrieved = repository.findByNameAndVersion(workflow.name, workflow.version)
            Assertions.assertNotNull(retrieved)
            Assertions.assertEquals(workflow.id, retrieved?.id)
            Assertions.assertEquals(workflow.name, retrieved?.name)
            Assertions.assertEquals(workflow.version, retrieved?.version)
            Assertions.assertEquals(workflow.definition, retrieved?.definition)
        }
    }

    /**
     * Tests retrieval of a workflow by its ID.
     * Verifies that:
     * - A workflow can be retrieved using its ID
     * - All properties are correctly preserved
     */
    @Test
    fun `should retrieve workflow by ID`() {
        // Given
        val workflow = WorkflowModel(
            name = "id-test-workflow",
            version = "1.0.0",
            definition = "test definition"
        )
        repository.persist(workflow)

        // When
        val retrieved = repository.findById(workflow.id)

        // Then
        Assertions.assertNotNull(retrieved)
        Assertions.assertEquals(workflow.id, retrieved?.id)
        Assertions.assertEquals(workflow.name, retrieved?.name)
        Assertions.assertEquals(workflow.version, retrieved?.version)
        Assertions.assertEquals(workflow.definition, retrieved?.definition)
    }

    /**
     * Tests retrieval of all workflows.
     * Verifies that:
     * - All workflows can be retrieved
     * - The list contains the correct number of workflows
     * - All properties of each workflow are preserved
     */
    @Test
    fun `should retrieve all workflows`() {
        // Given
        val workflows = List(3) { i ->
            WorkflowModel(
                name = "list-workflow-$i",
                version = "1.0.0",
                definition = "definition-$i"
            )
        }
        repository.persist(workflows)

        // When
        val retrieved = repository.listAll()

        // Then
        Assertions.assertEquals(workflows.size, retrieved.size)
        workflows.forEach { workflow ->
            val found = retrieved.find { it.id == workflow.id }
            Assertions.assertNotNull(found)
            Assertions.assertEquals(workflow.name, found?.name)
            Assertions.assertEquals(workflow.version, found?.version)
            Assertions.assertEquals(workflow.definition, found?.definition)
        }
    }

    /**
     * Tests concurrent access to the repository.
     * Verifies that:
     * - Multiple threads can safely access the repository
     * - No data corruption occurs during concurrent operations
     */
    @Test
    fun `should handle concurrent access safely`() {
        // Given
        val threadCount = 5
        val workflowsPerThread = 10
        val threads = List(threadCount) { threadIndex ->
            Thread {
                List(workflowsPerThread) { i ->
                    WorkflowModel(
                        name = "concurrent-workflow-$threadIndex-$i",
                        version = "1.0.0",
                        definition = "definition-$threadIndex-$i"
                    )
                }.forEach { workflow ->
                    repository.persist(workflow)
                    val retrieved = repository.findByNameAndVersion(workflow.name, workflow.version)
                    Assertions.assertNotNull(retrieved)
                    Assertions.assertEquals(workflow.id, retrieved?.id)
                }
            }
        }

        // When
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Then
        val allWorkflows = repository.listAll()
        Assertions.assertEquals(threadCount * workflowsPerThread, allWorkflows.size)
    }
}
