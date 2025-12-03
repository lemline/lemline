// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.models.DefinitionListenFilterModel
import com.lemline.runner.models.DefinitionListenModel
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.DefinitionListenFilterRepository
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
 * Abstract base class for testing DefinitionListenFilterRepository implementations.
 *
 * Tests cover:
 * - Basic CRUD operations for event filter definitions
 * - Finding filters by listen task ID
 * - Finding filters by event type
 * - Finding wildcard filters
 * - Deletion by listen task ID
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class DefinitionListenFilterRepositoryTest {

    @Inject
    lateinit var repository: DefinitionListenFilterRepository

    @Inject
    lateinit var listenRepository: DefinitionListenRepository

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    /**
     * Cleans up the database before each test.
     */
    @BeforeEach
    fun setupTest() = runTest {
        repository.deleteAll()
        listenRepository.deleteAll()
    }

    /**
     * Creates a workflow definition in the database (required for foreign key).
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
     * Creates a listen task definition (required for foreign key on filters).
     */
    private suspend fun createListenTask(
        namespace: WorkflowNamespace = WorkflowNamespace.random(),
        name: WorkflowName = WorkflowName.random(),
        version: WorkflowVersion = WorkflowVersion.random()
    ): DefinitionListenModel {
        createDefinition(namespace, name, version)
        val listenTask = DefinitionListenModel.random().copy(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version
        )
        listenRepository.insert(listenTask)
        return listenTask
    }

    /**
     * Creates a random filter model with a valid listen task in the database.
     */
    private suspend fun createRandomFilterWithListenTask(): DefinitionListenFilterModel {
        val listenTask = createListenTask()
        return DefinitionListenFilterModel.random().copy(listenId = listenTask.id)
    }

    @Test
    fun `insert should persist a new entity`() = runTest {
        val listenTask = createListenTask()
        val filter = DefinitionListenFilterModel.random().copy(listenId = listenTask.id)

        repository.insert(filter) shouldBe 1

        val retrieved = repository.findById(filter.id)
        retrieved shouldNotBe null
        retrieved?.id shouldBe filter.id
        retrieved?.listenId shouldBe filter.listenId
        retrieved?.filterIndex shouldBe filter.filterIndex
        retrieved?.eventType shouldBe filter.eventType
        retrieved?.eventSource shouldBe filter.eventSource
        retrieved?.eventSubject shouldBe filter.eventSubject
        retrieved?.correlations shouldBe filter.correlations
    }

    @Test
    fun `insert duplicate should fail`() = runTest {
        val filter = createRandomFilterWithListenTask()
        repository.insert(filter) shouldBe 1

        // Try to insert again with same ID
        repository.insert(filter) shouldBe 0
    }

    @Test
    fun `findByListenId should return filters for a specific listen task`() = runTest {
        val listenTask = createListenTask()

        val filter1 = DefinitionListenFilterModel.random().copy(
            listenId = listenTask.id,
            filterIndex = 0
        )
        val filter2 = DefinitionListenFilterModel.random().copy(
            listenId = listenTask.id,
            filterIndex = 1
        )

        repository.insert(filter1)
        repository.insert(filter2)

        // Create another listen task with different filter
        val otherListenTask = createListenTask()
        val otherFilter = DefinitionListenFilterModel.random().copy(listenId = otherListenTask.id)
        repository.insert(otherFilter)

        val results = repository.findByListenId(listenTask.id)
        results shouldHaveSize 2
        results.map { it.id } shouldContainExactlyInAnyOrder listOf(filter1.id, filter2.id)
    }

    @Test
    fun `findByListenId should return empty list for non-existent listen task`() = runTest {
        val results = repository.findByListenId(IDV7.random())
        results.shouldBeEmpty()
    }

    @Test
    fun `findByEventType should return filters matching the event type`() = runTest {
        val eventType = "com.example.test-event"

        // Create filter with specific event type
        val filter1 = createRandomFilterWithListenTask().copy(eventType = eventType)
        repository.insert(filter1)

        // Create filter with different event type
        val filter2 = createRandomFilterWithListenTask().copy(eventType = "com.example.other-event")
        repository.insert(filter2)

        // Create wildcard filter (no event type)
        val filter3 = createRandomFilterWithListenTask().copy(eventType = null)
        repository.insert(filter3)

        val results = repository.findByEventType(eventType)
        results shouldHaveSize 1
        results[0].id shouldBe filter1.id
    }

    @Test
    fun `findWildcardFilters should return filters with no event type`() = runTest {
        // Create filter with event type
        val filter1 = createRandomFilterWithListenTask().copy(eventType = "com.example.test")
        repository.insert(filter1)

        // Create wildcard filters
        val filter2 = createRandomFilterWithListenTask().copy(eventType = null)
        repository.insert(filter2)
        val filter3 = createRandomFilterWithListenTask().copy(eventType = null)
        repository.insert(filter3)

        val results = repository.findWildcardFilters()
        results shouldHaveSize 2
        results.map { it.id } shouldContainExactlyInAnyOrder listOf(filter2.id, filter3.id)
    }

    @Test
    fun `deleteByListenId should remove all filters for a listen task`() = runTest {
        val listenTask = createListenTask()

        // Insert multiple filters for the same listen task
        val filter1 = DefinitionListenFilterModel.random().copy(
            listenId = listenTask.id,
            filterIndex = 0
        )
        val filter2 = DefinitionListenFilterModel.random().copy(
            listenId = listenTask.id,
            filterIndex = 1
        )
        repository.insert(filter1)
        repository.insert(filter2)

        // Insert a filter for a different listen task
        val otherFilter = createRandomFilterWithListenTask()
        repository.insert(otherFilter)

        // Delete filters for the first listen task
        val deleted = repository.deleteByListenId(listenTask.id)
        deleted shouldBe 2

        // Verify deletion
        repository.findById(filter1.id) shouldBe null
        repository.findById(filter2.id) shouldBe null

        // Verify other listen task's filter is intact
        repository.findById(otherFilter.id) shouldNotBe null
    }

    @Test
    fun `deleteByListenId should return 0 for non-existent listen task`() = runTest {
        val deleted = repository.deleteByListenId(IDV7.random())
        deleted shouldBe 0
    }

    @Test
    fun `insert batch should persist multiple entities`() = runTest {
        val listenTask = createListenTask()

        val filters = List(5) { index ->
            DefinitionListenFilterModel.random().copy(
                listenId = listenTask.id,
                filterIndex = index
            )
        }

        repository.insert(filters) shouldBe 5

        val retrieved = repository.findByListenId(listenTask.id)
        retrieved shouldHaveSize 5
    }

    @Test
    fun `deleteAll should remove all entities`() = runTest {
        // Insert some entities
        repeat(3) {
            val filter = createRandomFilterWithListenTask()
            repository.insert(filter)
        }

        repository.countAll() shouldBe 3L

        repository.deleteAll()

        repository.countAll() shouldBe 0L
    }
}
