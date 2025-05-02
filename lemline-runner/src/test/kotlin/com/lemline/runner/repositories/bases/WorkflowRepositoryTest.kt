// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.WorkflowModel
import com.lemline.runner.repositories.WorkflowRepository
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import jakarta.inject.Inject
import java.sql.SQLException
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
            definition = "test-definition"
        )

        // When
        repository.insert(workflowModel)

        // Then
        val retrievedModel = repository.findByNameAndVersion(workflowModel.name, workflowModel.version)
        retrievedModel shouldNotBe null
        retrievedModel?.id shouldBe workflowModel.id
        retrievedModel?.name shouldBe workflowModel.name
        retrievedModel?.version shouldBe workflowModel.version
        retrievedModel?.definition shouldContain workflowModel.definition
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
        result shouldBe null
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
    fun `should successfully insert a new workflow version`() {
        // Given
        val original = WorkflowModel(
            name = "updatable-workflow",
            version = "1.0.0",
            definition = "original definition"
        )
        repository.insert(original)

        // When
        val updated = WorkflowModel(
            name = original.name,
            version = "1.1.0",
            definition = "updated definition"
        )
        repository.insert(updated)

        // Then
        val retrieved = repository.findByNameAndVersion(original.name, updated.version)
        retrieved shouldNotBe null
        retrieved!!.definition shouldBe "updated definition"
        retrieved.name shouldBe original.name
        retrieved.version shouldBe updated.version
        repository.count() shouldBe 2L
    }

    @Test
    fun `should successfully updating an existing workflow definition`() {
        // Given
        val original = WorkflowModel(
            name = "updatable-workflow",
            version = "1.0.0",
            definition = "original definition"
        )
        repository.insert(original)

        // When
        val updated = original.copy(definition = "updated definition")

        // Then
        shouldNotThrowAny { repository.upsert(updated) }
        repository.findById(original.id)?.definition shouldBe "updated definition"
        repository.count() shouldBe 1L
    }

    @Test
    fun `should fail inserting a new workflow with same name and version`() {
        // Given
        val original = WorkflowModel(
            name = "updatable-workflow",
            version = "1.0.0",
            definition = "original definition"
        )
        repository.insert(original)

        // When
        val updated = WorkflowModel(
            name = original.name,
            version = original.version,
            definition = "updated definition",
        )

        // Then
        shouldThrow<SQLException> { repository.insert(updated) }

        repository.findById(original.id) shouldNotBe null
        repository.findById(updated.id) shouldBe null
        repository.count() shouldBe 1L
    }


    @Test
    fun `should successfully insert a batch of workflows`() {
        // Given
        val workflows = List(5) { i ->
            WorkflowModel(
                name = "batch-workflow-$i",
                version = "1.0.0",
                definition = "definition-$i"
            )
        }

        // When
        repository.upsert(workflows)

        // Then
        workflows.forEach { workflow ->
            val retrieved = repository.findByNameAndVersion(workflow.name, workflow.version)
            retrieved shouldNotBe null
            retrieved?.id shouldBe workflow.id
            retrieved?.name shouldBe workflow.name
            retrieved?.version shouldBe workflow.version
            retrieved?.definition shouldBe workflow.definition
        }
        repository.count() shouldBe workflows.size.toLong()
    }

    @Test
    fun `should successfully update a batch of workflows`() {
        // Given
        val originals = List(5) { i ->
            WorkflowModel(
                name = "batch-workflow-$i",
                version = "1.0.0",
                definition = "definition-$i"
            )
        }
        repository.upsert(originals)

        // When
        val updated = originals.mapIndexed { i, model -> model.copy(definition = "updated definition-$i") }

        // Then
        shouldNotThrowAny { repository.upsert(updated) }
        originals.forEachIndexed { i, model ->
            repository.findById(model.id)?.definition shouldBe "updated definition-$i"
        }
        repository.count() shouldBe originals.size.toLong()
    }

//    @Test
//    fun `should fail insert a batch of workflows if at least one should be an upsert`() {
//        // Given
//        val originals = List(5) { i ->
//            WorkflowModel(
//                name = " original-$i",
//                version = "1.0.0",
//                definition = "original-$i"
//            )
//        }
//        repository.upsert(originals)
//
//        // When
//        val newWorkflows = MutableList(4) { i ->
//            WorkflowModel(
//                name = "different-$i",
//                version = "1.0.0",
//                definition = "different-$i"
//            )
//        }
//        newWorkflows.add(
//            WorkflowModel(
//                name = originals[4].name,
//                version = originals[4].version,
//                definition = "different-4"
//            )
//        )
//
//        // When
//        shouldThrow<SQLException> { repository.insert(newWorkflows) }
//
//        // Then the insert should have failed all together
//        repository.count() shouldBe originals.size.toLong()
//        originals.forEach { original ->
//            val retrieved = repository.findByNameAndVersion(original.name, original.version)
//            retrieved shouldNotBe null
//            retrieved?.id shouldBe original.id
//            retrieved?.name shouldBe original.name
//            retrieved?.version shouldBe original.version
//            retrieved?.definition shouldBe original.definition
//        }
//    }

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
        repository.insert(workflow)

        // When
        val retrieved = repository.findById(workflow.id)

        // Then
        retrieved shouldNotBe null
        retrieved?.id shouldBe workflow.id
        retrieved?.name shouldBe workflow.name
        retrieved?.version shouldBe workflow.version
        retrieved?.definition shouldBe workflow.definition
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
        repository.upsert(workflows)

        // When
        val retrieved = repository.listAll()

        // Then
        retrieved shouldHaveSize workflows.size
        workflows.forEach { workflow ->
            val found = retrieved.find { it.id == workflow.id }
            found shouldNotBe null
            found?.name shouldBe workflow.name
            found?.version shouldBe workflow.version
            found?.definition shouldBe workflow.definition
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
                val workflowsToPersist = List(workflowsPerThread) { i ->
                    WorkflowModel(
                        name = "concurrent-workflow-$threadIndex-$i",
                        version = "1.0.0",
                        definition = "definition-$threadIndex-$i"
                    )
                }
                repository.upsert(workflowsToPersist)

                workflowsToPersist.forEach { workflow ->
                    val retrieved = repository.findByNameAndVersion(workflow.name, workflow.version!!)
                    retrieved shouldNotBe null
                    retrieved?.id shouldBe workflow.id
                }
            }
        }

        // When
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Then
        val allWorkflows = repository.listAll()
        allWorkflows.size shouldBe (threadCount * workflowsPerThread)
    }
}
