// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.states.DoState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.TaskState
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.ListenerQueryKey
import com.lemline.runner.repositories.ListenerRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import jakarta.inject.Inject
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base class for testing ListenerRepository implementations.
 *
 * Extends OutboxRepositoryTest to inherit all standard outbox tests,
 * and adds listener-specific tests.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ListenerRepositoryTest : OutboxRepositoryTest<ListenerModel>() {

    @Inject
    override lateinit var repository: ListenerRepository

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    // Fixed test values for workflow identification
    private val testNamespace = WorkflowNamespace("test-namespace")
    private val testName = WorkflowName("test-workflow")
    private val testVersion = WorkflowVersion("1.0.0")
    private val testNodePosition = NodePosition("/do/listenTask")

    @BeforeEach
    fun setupParentRecords() = runTest {
        // Clear existing data
        repository.deleteAll()

        // Create parent definition record
        val definition = DefinitionModel(
            namespace = testNamespace,
            name = testName,
            version = testVersion,
            definition = """
                document:
                  dsl: '1.0.0'
                  namespace: $testNamespace
                  name: $testName
                  version: '$testVersion'
                do:
                  - listenTask:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
            """.trimIndent()
        )
        // Try to insert, ignore if already exists (from parallel tests)
        try {
            definitionRepository.insert(definition)
        } catch (_: Exception) {
            // Definition already exists, which is fine
        }
    }

    override fun createRandomEntity(): ListenerModel {
        val now = Clock.System.now()
        val workflowId = WorkflowId(IDV7.random())

        val workflowInfo = WorkflowInfo(
            workflowNamespace = testNamespace,
            workflowName = testName,
            workflowVersion = testVersion
        )

        // Create a proper NodeStack with the listen task position
        // The nodeStack.lastPosition must equal testNodePosition for correct serialization
        val nodeStack = NodeStack(
            listOf(
                NodePosition.root to RootState(
                    startedAt = now,
                    workflowId = workflowId,
                    workflowInput = JsonNull
                ),
                NodePosition("/do") to DoState(startedAt = now),
                testNodePosition to TaskState(startedAt = now)
            )
        )

        val listenStarted = WorkflowEvent.ListenStarted(
            nodeStack = nodeStack,
            rawOutput = JsonNull,
            config = ListenConfig(
                strategy = ListenStrategy.ONE,
                filters = listOf(EventFilter(type = "com.example.Event")),
                readAs = ListenAndReadAs.DATA,
                timeoutAt = null
            )
        )

        return ListenerModel(
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = listenStarted
            ),
            timeoutAt = null,
            outboxScheduledFor = now
        ).also {
            it.outboxAttemptCount = Random.nextInt(0, 5)
            it.outboxDelayedUntil = now
        }
    }

    override fun changeDelayedUntil(model: ListenerModel) =
        model.copy().apply { outboxDelayedUntil = Instant.random() }

    // ========== findByKeys tests ==========

    @Test
    fun `findByKeys should return empty list for empty keys`() = runTest {
        // Given - insert a listener
        val listener = createRandomEntity()
        repository.insert(listener)

        // When
        val result = repository.findByKeys(emptyList())

        // Then
        result.shouldBeEmpty()
    }

    @Test
    fun `findByKeys should find listener by single key without correlation`() = runTest {
        // Given
        val listener = createRandomEntity()
        repository.insert(listener)

        val key = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = null
        )

        // When
        val result = repository.findByKeys(listOf(key))

        // Then
        result shouldHaveSize 1
        result.first().id shouldBe listener.id
    }

    @Test
    fun `findByKeys should find listener with null correlation when key has correlation value`() = runTest {
        // Given - listener with null correlation values (Mode 2: first-sets-baseline)
        val listener = createRandomEntity().apply {
            correlationValues = null
        }
        repository.insert(listener)

        val key = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = """{"orderId":"123"}"""
        )

        // When
        val result = repository.findByKeys(listOf(key))

        // Then
        result shouldHaveSize 1
        result.first().id shouldBe listener.id
    }

    @Test
    fun `findByKeys should find listener with matching correlation value`() = runTest {
        // Given
        val correlationJson = """{"orderId":"123"}"""
        val listener = createRandomEntity().apply {
            correlationValues = correlationJson
        }
        repository.insert(listener)

        val key = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = correlationJson
        )

        // When
        val result = repository.findByKeys(listOf(key))

        // Then
        result shouldHaveSize 1
        result.first().id shouldBe listener.id
    }

    @Test
    fun `findByKeys should not find listener with non-matching correlation value`() = runTest {
        // Given
        val listener = createRandomEntity().apply {
            correlationValues = """{"orderId":"123"}"""
        }
        repository.insert(listener)

        val key = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = """{"orderId":"456"}"""
        )

        // When
        val result = repository.findByKeys(listOf(key))

        // Then
        result.shouldBeEmpty()
    }

    @Test
    fun `findByKeys should find multiple listeners matching different keys`() = runTest {
        // Given - two listeners with different positions
        val position1 = NodePosition("/do/listen1")
        val position2 = NodePosition("/do/listen2")

        val listener1 = createListenerWithPosition(position1)
        val listener2 = createListenerWithPosition(position2)
        repository.insert(listOf(listener1, listener2))

        val keys = listOf(
            ListenerQueryKey(
                workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
                position = position1,
                correlationValuesJson = null
            ),
            ListenerQueryKey(
                workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
                position = position2,
                correlationValuesJson = null
            )
        )

        // When
        val result = repository.findByKeys(keys)

        // Then
        result shouldHaveSize 2
        result.map { it.id } shouldContainExactlyInAnyOrder listOf(listener1.id, listener2.id)
    }

    @Test
    fun `findByKeys should find listener matching any of multiple keys`() = runTest {
        // Given - one listener that matches the first key
        val listener = createRandomEntity()
        repository.insert(listener)

        val keys = listOf(
            ListenerQueryKey(
                workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
                position = testNodePosition,
                correlationValuesJson = null
            ),
            ListenerQueryKey(
                workflowInfo = WorkflowInfo(WorkflowNamespace("other-namespace"), testName, testVersion),
                position = testNodePosition,
                correlationValuesJson = null
            )
        )

        // When
        val result = repository.findByKeys(keys)

        // Then
        result shouldHaveSize 1
        result.first().id shouldBe listener.id
    }

    @Test
    fun `findByKeys should handle mixed correlation keys`() = runTest {
        // Given - two listeners: one with null correlation, one with specific correlation
        val correlationJson = """{"orderId":"123"}"""
        val listener1 = createRandomEntity().apply {
            correlationValues = null
        }
        val listener2 = createRandomEntity().apply {
            correlationValues = correlationJson
        }
        repository.insert(listOf(listener1, listener2))

        // Key without correlation should find listener1 only (since no correlation filter)
        // Key with correlation should find both (null matches, and exact match)
        val keyWithCorrelation = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = correlationJson
        )

        // When
        val result = repository.findByKeys(listOf(keyWithCorrelation))

        // Then - should find both (null correlation OR exact match)
        result shouldHaveSize 2
        result.map { it.id } shouldContainExactlyInAnyOrder listOf(listener1.id, listener2.id)
    }

    @Test
    fun `findByKeys should not find completed listeners`() = runTest {
        // Given - a completed listener
        val listener = createRandomEntity().apply {
            outboxCompletedAt = Clock.System.now()
        }
        repository.insert(listener)

        val key = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = null
        )

        // When
        val result = repository.findByKeys(listOf(key))

        // Then
        result.shouldBeEmpty()
    }

    @Test
    fun `findByKeys should not find failed listeners`() = runTest {
        // Given - a failed listener
        val listener = createRandomEntity().apply {
            outboxFailedAt = Clock.System.now()
        }
        repository.insert(listener)

        val key = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = null
        )

        // When
        val result = repository.findByKeys(listOf(key))

        // Then
        result.shouldBeEmpty()
    }

    @Test
    fun `findByKeys should return empty when no matching listeners exist`() = runTest {
        // Given - a listener with different workflow info
        val listener = createRandomEntity()
        repository.insert(listener)

        val key = ListenerQueryKey(
            workflowInfo = WorkflowInfo(WorkflowNamespace("non-existent-namespace"), testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = null
        )

        // When
        val result = repository.findByKeys(listOf(key))

        // Then
        result.shouldBeEmpty()
    }

    @Test
    fun `findByKeys should find all listeners matching a single key`() = runTest {
        // Given - multiple active listeners for the same workflow position
        val listener1 = createRandomEntity()
        val listener2 = createRandomEntity()
        repository.insert(listOf(listener1, listener2))

        val key = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = null
        )

        // When
        val result = repository.findByKeys(listOf(key))

        // Then
        result shouldHaveSize 2
        result.map { it.id } shouldContainExactlyInAnyOrder listOf(listener1.id, listener2.id)
    }

    @Test
    fun `findByKeys with null correlation should find listener that has correlation value`() = runTest {
        // Given - listener with specific correlation values
        val listener = createRandomEntity().apply {
            correlationValues = """{"orderId":"123"}"""
        }
        repository.insert(listener)

        // Key WITHOUT correlation should find ALL listeners (no correlation filter)
        val key = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = null
        )

        // When
        val result = repository.findByKeys(listOf(key))

        // Then - should find the listener since no correlation filter is applied
        result shouldHaveSize 1
        result.first().id shouldBe listener.id
    }

    @Test
    fun `findByKeys should find only matching listeners when multiple have different correlations`() = runTest {
        // Given - three listeners with different correlation states
        val correlationA = """{"orderId":"A"}"""
        val correlationB = """{"orderId":"B"}"""

        val listenerNull = createRandomEntity().apply {
            correlationValues = null  // Mode 2: awaiting first event
        }
        val listenerA = createRandomEntity().apply {
            correlationValues = correlationA
        }
        val listenerB = createRandomEntity().apply {
            correlationValues = correlationB
        }
        repository.insert(listOf(listenerNull, listenerA, listenerB))

        // Key with correlationA should find: listenerNull (Mode 2) and listenerA (exact match)
        val keyA = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = correlationA
        )

        // When
        val result = repository.findByKeys(listOf(keyA))

        // Then - should find null and A, but NOT B
        result shouldHaveSize 2
        result.map { it.id } shouldContainExactlyInAnyOrder listOf(listenerNull.id, listenerA.id)
    }

    @Test
    fun `findByKeys should find listeners across multiple keys with different correlations`() = runTest {
        // Given - listeners with different correlations for different positions
        val position1 = NodePosition("/do/listen1")
        val position2 = NodePosition("/do/listen2")
        val correlationJson = """{"orderId":"123"}"""

        val listener1 = createListenerWithPosition(position1).apply {
            correlationValues = correlationJson
        }
        val listener2 = createListenerWithPosition(position2).apply {
            correlationValues = null  // Mode 2
        }
        repository.insert(listOf(listener1, listener2))

        val keys = listOf(
            ListenerQueryKey(
                workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
                position = position1,
                correlationValuesJson = correlationJson  // matches listener1
            ),
            ListenerQueryKey(
                workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
                position = position2,
                correlationValuesJson = """{"orderId":"456"}"""  // matches listener2 (null correlation)
            )
        )

        // When
        val result = repository.findByKeys(keys)

        // Then - should find both
        result shouldHaveSize 2
        result.map { it.id } shouldContainExactlyInAnyOrder listOf(listener1.id, listener2.id)
    }

    @Test
    fun `findByKeys should only find active listeners among mixed states`() = runTest {
        // Given - one active, one completed, one failed listener
        val active = createRandomEntity()
        val completed = createRandomEntity().apply {
            outboxCompletedAt = Clock.System.now()
        }
        val failed = createRandomEntity().apply {
            outboxFailedAt = Clock.System.now()
        }
        repository.insert(listOf(active, completed, failed))

        val key = ListenerQueryKey(
            workflowInfo = WorkflowInfo(testNamespace, testName, testVersion),
            position = testNodePosition,
            correlationValuesJson = null
        )

        // When
        val result = repository.findByKeys(listOf(key))

        // Then - should only find the active listener
        result shouldHaveSize 1
        result.first().id shouldBe active.id
    }

    // ========== Helper methods ==========

    private fun createListenerWithPosition(position: NodePosition): ListenerModel {
        val now = Clock.System.now()
        val workflowId = WorkflowId(IDV7.random())

        val workflowInfo = WorkflowInfo(
            workflowNamespace = testNamespace,
            workflowName = testName,
            workflowVersion = testVersion
        )

        val nodeStack = NodeStack(
            listOf(
                NodePosition.root to RootState(
                    startedAt = now,
                    workflowId = workflowId,
                    workflowInput = JsonNull
                ),
                NodePosition("/do") to DoState(startedAt = now),
                position to TaskState(startedAt = now)
            )
        )

        val listenStarted = WorkflowEvent.ListenStarted(
            nodeStack = nodeStack,
            rawOutput = JsonNull,
            config = ListenConfig(
                strategy = ListenStrategy.ONE,
                filters = listOf(EventFilter(type = "com.example.Event")),
                readAs = ListenAndReadAs.DATA,
                timeoutAt = null
            )
        )

        return ListenerModel(
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = listenStarted
            ),
            timeoutAt = null,
            outboxScheduledFor = now
        ).also {
            it.outboxAttemptCount = Random.nextInt(0, 5)
            it.outboxDelayedUntil = now
        }
    }
}
