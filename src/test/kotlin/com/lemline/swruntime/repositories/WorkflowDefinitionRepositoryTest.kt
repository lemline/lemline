package com.lemline.swruntime.repositories

import com.lemline.swruntime.PostgresTestResource
import com.lemline.swruntime.models.WorkflowDefinition
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@QuarkusTest
@Testcontainers
@QuarkusTestResource(PostgresTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
class WorkflowDefinitionRepositoryTest {

    @Inject
    lateinit var repository: WorkflowDefinitionRepository

    @Inject
    lateinit var entityManager: EntityManager

    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("swruntime_test")
            .withUsername("test")
            .withPassword("test")
    }

    @BeforeAll
    fun setup() {
        postgres.start()
    }

    @AfterAll
    fun cleanup() {
        postgres.stop()
    }

    @BeforeEach
    @Transactional
    fun setupTest() {
        // Clear the database before each test
        entityManager.createQuery("DELETE FROM WorkflowDefinition").executeUpdate()
    }

    @Test
    @DisplayName("should return null when no workflow found")
    @Transactional
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
    @DisplayName("should return workflow when found")
    @Transactional
    fun `should return workflow when found`() {
        // Given
        val name = "test-workflow"
        val version = "1.0.0"
        val workflow = WorkflowDefinition().apply {
            this.name = name
            this.version = version
            this.definition = "test definition"
        }
        entityManager.persist(workflow)
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