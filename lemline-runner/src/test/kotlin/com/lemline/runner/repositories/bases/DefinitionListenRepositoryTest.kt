// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
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
 * - Basic CRUD operations
 * - Finding filters by definition
 * - Finding filters by event type
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
        retrieved?.filterIndex shouldBe model.filterIndex
        retrieved?.eventType shouldBe model.eventType
        retrieved?.eventSource shouldBe model.eventSource
        retrieved?.eventSubject shouldBe model.eventSubject
        retrieved?.correlations shouldBe model.correlations
    }

    @Test
    fun `insert duplicate should fail`() = runTest {
        val model = createRandomModelWithDefinition()
        repository.insert(model) shouldBe 1

        // Try to insert again with same ID
        repository.insert(model) shouldBe 0
    }

    @Test
    fun `findByDefinition should return filters for a specific definition`() = runTest {
        // Create definition and filters
        val namespace = WorkflowNamespace.random()
        val name = WorkflowName.random()
        val version = WorkflowVersion.random()
        createDefinition(namespace, name, version)

        val filter1 = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            filterIndex = 0
        )
        val filter2 = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            filterIndex = 1
        )

        repository.insert(filter1)
        repository.insert(filter2)

        // Create another definition with different filters
        val otherModel = createRandomModelWithDefinition()
        repository.insert(otherModel)

        val results = repository.findByDefinition(namespace, name, version)
        results shouldHaveSize 2
        results.map { it.id } shouldContainExactlyInAnyOrder listOf(filter1.id, filter2.id)
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
    fun `findByEventType should return filters matching the event type`() = runTest {
        val eventType = "com.example.test-event"

        // Create filter with specific event type
        val model1 = createRandomModelWithDefinition().copy(eventType = eventType)
        repository.insert(model1)

        // Create filter with different event type
        val model2 = createRandomModelWithDefinition().copy(eventType = "com.example.other-event")
        repository.insert(model2)

        // Create wildcard filter (no event type)
        val model3 = createRandomModelWithDefinition().copy(eventType = null)
        repository.insert(model3)

        val results = repository.findByEventType(eventType)
        results shouldHaveSize 1
        results[0].id shouldBe model1.id
    }

    @Test
    fun `findWildcardFilters should return filters with no event type`() = runTest {
        // Create filter with event type
        val model1 = createRandomModelWithDefinition().copy(eventType = "com.example.test")
        repository.insert(model1)

        // Create wildcard filters
        val model2 = createRandomModelWithDefinition().copy(eventType = null)
        repository.insert(model2)
        val model3 = createRandomModelWithDefinition().copy(eventType = null)
        repository.insert(model3)

        val results = repository.findWildcardFilters()
        results shouldHaveSize 2
        results.map { it.id } shouldContainExactlyInAnyOrder listOf(model2.id, model3.id)
    }

    @Test
    fun `deleteByDefinition should remove all filters for a definition`() = runTest {
        val namespace = WorkflowNamespace.random()
        val name = WorkflowName.random()
        val version = WorkflowVersion.random()
        createDefinition(namespace, name, version)

        // Insert multiple filters for the same definition
        val filter1 = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            filterIndex = 0
        )
        val filter2 = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            filterIndex = 1
        )
        repository.insert(filter1)
        repository.insert(filter2)

        // Insert a filter for a different definition
        val otherModel = createRandomModelWithDefinition()
        repository.insert(otherModel)

        // Delete filters for the first definition
        val deleted = repository.deleteByDefinition(namespace, name, version)
        deleted shouldBe 2

        // Verify deletion
        repository.findById(filter1.id) shouldBe null
        repository.findById(filter2.id) shouldBe null

        // Verify other definition's filter is intact
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

        val filters = List(5) { index ->
            DefinitionListenModel.random().copy(
                workflowNamespace = namespace,
                workflowName = name,
                workflowVersion = version,
                filterIndex = index
            )
        }

        repository.insert(filters) shouldBe 5

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
