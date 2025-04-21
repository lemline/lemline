// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.tests.resources.PostgresTestResource
import com.lemline.worker.models.WorkflowModel
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.*

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("postgresql")
class WorkflowRepositoryTest {

    @Inject
    lateinit var repository: WorkflowRepository

    @Inject
    lateinit var entityManager: EntityManager

    @BeforeEach
    @Transactional
    fun setupTest() {
        // Clear the database before each test
        entityManager.createQuery("DELETE FROM WorkflowModel").executeUpdate()
    }

    @Test
    fun `should return null when no workflow found`() {
        // Given
        val name = "non-existent"
        val version = "1.0.0"

        // When
        val result = repository.findByNameAndVersion(name, version)

        // Then
        Assertions.assertNull(result)
    }

    @Test
    @Transactional
    fun `should return workflow when found`() {
        // Given
        val name = "test-workflow"
        val version = "1.0.0"
        val workflowModel = WorkflowModel().apply {
            this.name = name
            this.version = version
            this.definition = "test definition"
        }
        entityManager.persist(workflowModel)
        entityManager.flush()

        // When
        val result = repository.findByNameAndVersion(name, version)

        // Then
        Assertions.assertNotNull(result)
        Assertions.assertEquals(name, result?.name)
        Assertions.assertEquals(version, result?.version)
        Assertions.assertEquals("test definition", result?.definition)
    }
}
