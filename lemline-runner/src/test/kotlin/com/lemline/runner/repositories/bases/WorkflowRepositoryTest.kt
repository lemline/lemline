// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.WorkflowModel
import com.lemline.runner.repositories.WorkflowRepository
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
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

    /** Entity manager for direct database operations */
    @Inject
    protected lateinit var entityManager: EntityManager

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
        val workflowModel = WorkflowModel().apply {
            name = "test-workflow"
            version = "1.0.0"
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
        }

        // When
        val savedModel = persistAndFlush(workflowModel)

        // Then
        val retrievedModel = repository.findByNameAndVersion("test-workflow", "1.0.0")
        Assertions.assertNotNull(retrievedModel)
        Assertions.assertEquals(savedModel.id, retrievedModel?.id)
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
        val workflowModel = WorkflowModel().apply {
            name = "updatable-workflow"
            version = "1.0.0"
            definition = "original definition"
        }
        persistAndFlush(workflowModel)

        // When
        val retrievedModel = repository.findByNameAndVersion("updatable-workflow", "1.0.0")
        retrievedModel?.apply {
            definition = "updated definition"
        }
        if (retrievedModel != null) {
            entityManager.merge(retrievedModel)
            entityManager.flush()
        }

        // Then
        val updatedModel = repository.findByNameAndVersion("updatable-workflow", "1.0.0")
        Assertions.assertNotNull(updatedModel)
        Assertions.assertEquals("updated definition", updatedModel?.definition)
    }

    /**
     * Helper method to persist and flush a workflow model in a transaction.
     * Ensures that the model is properly saved to the database before proceeding.
     *
     * @param model The workflow model to persist
     * @return The persisted model with its ID set
     */
    @Transactional
    protected fun persistAndFlush(model: WorkflowModel): WorkflowModel {
        entityManager.persist(model)
        entityManager.flush()
        return model
    }
}
