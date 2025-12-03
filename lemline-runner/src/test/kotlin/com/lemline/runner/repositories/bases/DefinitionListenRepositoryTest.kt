// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.models.DefinitionListenModel
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.DefinitionListenRepository
import com.lemline.runner.repositories.DefinitionRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base class for testing DefinitionListenRepository implementations.
 *
 * Tests cover:
 * - Basic CRUD operations for listen task definitions
 * - Finding listen tasks by definition
 * - Finding listen tasks by definition and position
 * - Deletion by definition
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class DefinitionListenRepositoryTest {

    @Inject
    lateinit var repository: DefinitionListenRepository

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    /**
     * Cleans up the database before each test.
     */
    @BeforeEach
    fun setupTest() = runTest {
        repository.deleteAll()
    }

    /**
     * Creates a definition in the database (required for foreign key).
     */
    private suspend fun createDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ) {
        val definition = DefinitionModel(
            namespace = namespace,
            name = name,
            version = version,
            definition = """
                document:
                  dsl: '1.0.0'
                  namespace: ${namespace}
                  name: ${name}
                  version: '${version}'
                do:
                  - task1:
                      set:
                        result: done
            """.trimIndent()
        )
        definitionRepository.insert(definition)
    }

    /**
     * Creates a random model with a valid definition in the database.
     */
    private suspend fun createRandomModelWithDefinition(): DefinitionListenModel {
        val model = DefinitionListenModel.random()
        createDefinition(model.workflowNamespace, model.workflowName, model.workflowVersion)
        return model
    }

    @Test
    fun `insert should persist a new entity`() = runTest {
        val model = createRandomModelWithDefinition()

        repository.insert(model) shouldBe 1

        val retrieved = repository.findById(model.id)
        retrieved shouldNotBe null
        retrieved?.id shouldBe model.id
        retrieved?.workflowNamespace shouldBe model.workflowNamespace
        retrieved?.workflowName shouldBe model.workflowName
        retrieved?.workflowVersion shouldBe model.workflowVersion
        retrieved?.nodePosition shouldBe model.nodePosition
        retrieved?.strategy shouldBe model.strategy
        retrieved?.readAs shouldBe model.readAs
    }

    @Test
    fun `insert duplicate should fail`() = runTest {
        val model = createRandomModelWithDefinition()
        repository.insert(model) shouldBe 1

        // Try to insert again with same ID
        repository.insert(model) shouldBe 0
    }

    @Test
    fun `findByDefinition should return listen tasks for a specific definition`() = runTest {
        // Create definition and listen tasks
        val namespace = WorkflowNamespace.random()
        val name = WorkflowName.random()
        val version = WorkflowVersion.random()
        createDefinition(namespace, name, version)

        val task1 = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            nodePosition = NodePosition("/do/listen1")
        )
        val task2 = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            nodePosition = NodePosition("/do/listen2")
        )

        repository.insert(task1)
        repository.insert(task2)

        // Create another definition with different listen tasks
        val otherModel = createRandomModelWithDefinition()
        repository.insert(otherModel)

        val results = repository.findByDefinition(namespace, name, version)
        results shouldHaveSize 2
        results.map { it.id } shouldContainExactlyInAnyOrder listOf(task1.id, task2.id)
    }

    @Test
    fun `findByDefinition should return empty list for non-existent definition`() = runTest {
        val results = repository.findByDefinition(
            WorkflowNamespace.random(),
            WorkflowName.random(),
            WorkflowVersion.random()
        )
        results.shouldBeEmpty()
    }

    @Test
    fun `findByDefinitionAndPosition should return specific listen task`() = runTest {
        val namespace = WorkflowNamespace.random()
        val name = WorkflowName.random()
        val version = WorkflowVersion.random()
        createDefinition(namespace, name, version)

        val position = NodePosition("/do/myListenTask")
        val task = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            nodePosition = position
        )
        repository.insert(task)

        val result = repository.findByDefinitionAndPosition(namespace, name, version, position)
        result shouldNotBe null
        result?.id shouldBe task.id
        result?.nodePosition shouldBe position
    }

    @Test
    fun `findByDefinitionAndPosition should return null for non-existent position`() = runTest {
        val namespace = WorkflowNamespace.random()
        val name = WorkflowName.random()
        val version = WorkflowVersion.random()
        createDefinition(namespace, name, version)

        val task = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            nodePosition = NodePosition("/do/listen1")
        )
        repository.insert(task)

        val result = repository.findByDefinitionAndPosition(
            namespace, name, version,
            NodePosition("/do/nonExistent")
        )
        result shouldBe null
    }

    @Test
    fun `deleteByDefinition should remove all listen tasks for a definition`() = runTest {
        val namespace = WorkflowNamespace.random()
        val name = WorkflowName.random()
        val version = WorkflowVersion.random()
        createDefinition(namespace, name, version)

        // Insert multiple listen tasks for the same definition
        val task1 = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            nodePosition = NodePosition("/do/listen1")
        )
        val task2 = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            nodePosition = NodePosition("/do/listen2")
        )
        repository.insert(task1)
        repository.insert(task2)

        // Insert a listen task for a different definition
        val otherModel = createRandomModelWithDefinition()
        repository.insert(otherModel)

        // Delete listen tasks for the first definition
        val deleted = repository.deleteByDefinition(namespace, name, version)
        deleted shouldBe 2

        // Verify deletion
        repository.findById(task1.id) shouldBe null
        repository.findById(task2.id) shouldBe null

        // Verify other definition's listen task is intact
        repository.findById(otherModel.id) shouldNotBe null
    }

    @Test
    fun `deleteByDefinition should return 0 for non-existent definition`() = runTest {
        val deleted = repository.deleteByDefinition(
            WorkflowNamespace.random(),
            WorkflowName.random(),
            WorkflowVersion.random()
        )
        deleted shouldBe 0
    }

    @Test
    fun `insert batch should persist multiple entities`() = runTest {
        val namespace = WorkflowNamespace.random()
        val name = WorkflowName.random()
        val version = WorkflowVersion.random()
        createDefinition(namespace, name, version)

        val tasks = List(5) { index ->
            DefinitionListenModel.random().copy(
                workflowNamespace = namespace,
                workflowName = name,
                workflowVersion = version,
                nodePosition = NodePosition("/do/listen$index")
            )
        }

        repository.insert(tasks) shouldBe 5

        val retrieved = repository.findByDefinition(namespace, name, version)
        retrieved shouldHaveSize 5
    }

    @Test
    fun `deleteAll should remove all entities`() = runTest {
        // Insert some entities
        repeat(3) {
            val model = createRandomModelWithDefinition()
            repository.insert(model)
        }

        repository.countAll() shouldBe 3L

        repository.deleteAll()

        repository.countAll() shouldBe 0L
    }
}
