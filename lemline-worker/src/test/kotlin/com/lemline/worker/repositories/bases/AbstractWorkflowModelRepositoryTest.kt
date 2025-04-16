package com.lemline.worker.repositories.bases

import com.lemline.worker.models.WorkflowModel
import com.lemline.worker.repositories.WorkflowRepository
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base class for workflow repository tests.
 * This provides common test implementation that can be reused by database-specific test classes.
 */
abstract class AbstractWorkflowModelRepositoryTest {

    @Inject
    protected lateinit var repository: WorkflowRepository

    @Inject
    protected lateinit var entityManager: EntityManager

    @BeforeEach
    @Transactional
    fun setupTest() {
        // Clear the database before each test
        entityManager.createQuery("DELETE FROM WorkflowModel").executeUpdate()
    }

    @Test
    fun `should persist and retrieve workflow model`() {
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

    @Test
    fun `should return null when workflow with given name and version does not exist`() {
        // When
        val result = repository.findByNameAndVersion("non-existent", "1.0.0")

        // Then
        Assertions.assertNull(result)
    }

    @Test
    @Transactional
    fun `should update existing workflow`() {
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

    @Transactional
    protected fun persistAndFlush(model: WorkflowModel): WorkflowModel {
        entityManager.persist(model)
        entityManager.flush()
        return model
    }
} 