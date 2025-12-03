// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

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
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.definitions.DefinitionListenCache
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.DefinitionListenFilterModel
import com.lemline.runner.models.DefinitionListenModel
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.DefinitionListenRepository
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.ListenerRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import jakarta.inject.Inject
import java.net.URI
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests for CloudEventHandler - matching CloudEvents against active listeners.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExperimentalTime
@ExperimentalSerializationApi
internal class CloudEventHandlerTest {

    @Inject
    lateinit var cloudEventHandler: CloudEventHandler

    @Inject
    lateinit var listenerRepository: ListenerRepository

    @Inject
    lateinit var definitionListenRepository: DefinitionListenRepository

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var definitionListenCache: DefinitionListenCache

    private val testNamespace = WorkflowNamespace("test-namespace")
    private val testName = WorkflowName("test-workflow")
    private val testVersion = WorkflowVersion("1.0.0")
    private val testEventType = "com.example.TestEvent"

    @BeforeEach
    fun setup() = runTest {
        listenerRepository.deleteAll()
        definitionListenRepository.deleteAll()
        definitionListenCache.clear()

        // Create workflow definition (required for foreign key)
        definitionRepository.insert(
            DefinitionModel(
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
                                  type: $testEventType
                """.trimIndent()
            )
        )
    }

    @Test
    fun `CloudEvent with no matching filters returns 0 affected`() = runTest {
        // Given: No definition filters in cache
        val cloudEvent = createCloudEvent(type = testEventType)

        // When
        val affected = cloudEventHandler.handleCloudEvent(cloudEvent)

        // Then
        affected shouldBe 0
    }

    @Test
    fun `CloudEvent with matching filter but no active listener returns 0`() = runTest {
        // Given: Definition listen task and filter exist but no listener
        val listenTask = createDefinitionListenTask()
        val filter = createDefinitionFilter(listenId = listenTask.id, eventType = testEventType)
        definitionListenCache.addListenTaskForTesting(listenTask)
        definitionListenCache.addFilterForTesting(filter, testNamespace, testName, testVersion)

        val cloudEvent = createCloudEvent(type = testEventType)

        // When
        val affected = cloudEventHandler.handleCloudEvent(cloudEvent)

        // Then
        affected shouldBe 0
    }

    @Test
    fun `ONE strategy - CloudEvent matches and completes listener`() = runTest {
        // Given: Definition filter and active listener
        val listenTask = createDefinitionListenTask(strategy = ListenStrategy.ONE)
        val filter = createDefinitionFilter(listenId = listenTask.id, eventType = testEventType)
        definitionListenCache.addListenTaskForTesting(listenTask)
        definitionListenCache.addFilterForTesting(filter, testNamespace, testName, testVersion)

        val listener = createAndInsertListener(
            listenDefinitionId = listenTask.id,
            filters = listOf(EventFilter(type = testEventType))
        )

        val cloudEvent = createCloudEvent(type = testEventType)

        // When
        val affected = cloudEventHandler.handleCloudEvent(cloudEvent)

        // Then
        affected shouldBe 1

        // Listener should be marked completed
        val updated = listenerRepository.findById(listener.id)
        updated shouldNotBe null
        updated!!.outboxCompletedAt shouldNotBe null
    }

    @Test
    fun `ONE strategy - non-matching event type does not affect listener`() = runTest {
        // Given: Listener expecting TestEvent
        val listenTask = createDefinitionListenTask(strategy = ListenStrategy.ONE)
        val filter = createDefinitionFilter(listenId = listenTask.id, eventType = testEventType)
        definitionListenCache.addListenTaskForTesting(listenTask)
        definitionListenCache.addFilterForTesting(filter, testNamespace, testName, testVersion)

        val listener = createAndInsertListener(
            listenDefinitionId = listenTask.id,
            filters = listOf(EventFilter(type = testEventType))
        )

        // When: CloudEvent with different type
        val cloudEvent = createCloudEvent(type = "com.example.OtherEvent")
        val affected = cloudEventHandler.handleCloudEvent(cloudEvent)

        // Then
        affected shouldBe 0

        // Listener should NOT be completed
        val updated = listenerRepository.findById(listener.id)
        updated?.outboxCompletedAt shouldBe null
    }

    @Test
    fun `ALL strategy - tracks matched filter indices`() = runTest {
        // Given: Listener with 3 filters
        val listenTask = createDefinitionListenTask(strategy = ListenStrategy.ALL)
        val filter1 = createDefinitionFilter(listenId = listenTask.id, eventType = "com.example.Event1", filterIndex = 0)
        val filter2 = createDefinitionFilter(listenId = listenTask.id, eventType = "com.example.Event2", filterIndex = 1)
        val filter3 = createDefinitionFilter(listenId = listenTask.id, eventType = "com.example.Event3", filterIndex = 2)
        definitionListenCache.addListenTaskForTesting(listenTask)
        definitionListenCache.addFilterForTesting(filter1, testNamespace, testName, testVersion)
        definitionListenCache.addFilterForTesting(filter2, testNamespace, testName, testVersion)
        definitionListenCache.addFilterForTesting(filter3, testNamespace, testName, testVersion)

        val listener = createAndInsertListener(
            listenDefinitionId = listenTask.id,
            filters = listOf(
                EventFilter(type = "com.example.Event1"),
                EventFilter(type = "com.example.Event2"),
                EventFilter(type = "com.example.Event3")
            )
        )

        // When: First event
        val cloudEvent1 = createCloudEvent(type = "com.example.Event1")
        val affected1 = cloudEventHandler.handleCloudEvent(cloudEvent1)

        // Then: Progress updated but not completed
        affected1 shouldBe 1
        val after1 = listenerRepository.findById(listener.id)
        after1?.outboxCompletedAt shouldBe null
        after1?.matchedFilterIndices shouldNotBe null

        // When: Second event
        val cloudEvent2 = createCloudEvent(type = "com.example.Event2")
        val affected2 = cloudEventHandler.handleCloudEvent(cloudEvent2)

        // Then: Still not completed
        affected2 shouldBe 1
        val after2 = listenerRepository.findById(listener.id)
        after2?.outboxCompletedAt shouldBe null

        // When: Third event - completes the set
        val cloudEvent3 = createCloudEvent(type = "com.example.Event3")
        val affected3 = cloudEventHandler.handleCloudEvent(cloudEvent3)

        // Then: Now completed
        affected3 shouldBe 1
        val after3 = listenerRepository.findById(listener.id)
        after3?.outboxCompletedAt shouldNotBe null
    }

    @Test
    fun `ANY strategy - completes on first matching event`() = runTest {
        // Given: Listener with 2 filters, either can match
        val listenTask = createDefinitionListenTask(strategy = ListenStrategy.ANY)
        val filter1 = createDefinitionFilter(listenId = listenTask.id, eventType = "com.example.Event1", filterIndex = 0)
        val filter2 = createDefinitionFilter(listenId = listenTask.id, eventType = "com.example.Event2", filterIndex = 1)
        definitionListenCache.addListenTaskForTesting(listenTask)
        definitionListenCache.addFilterForTesting(filter1, testNamespace, testName, testVersion)
        definitionListenCache.addFilterForTesting(filter2, testNamespace, testName, testVersion)

        val listener = createAndInsertListener(
            listenDefinitionId = listenTask.id,
            filters = listOf(
                EventFilter(type = "com.example.Event1"),
                EventFilter(type = "com.example.Event2")
            )
        )

        // When: First matching event
        val cloudEvent = createCloudEvent(type = "com.example.Event2")
        val affected = cloudEventHandler.handleCloudEvent(cloudEvent)

        // Then: Completed immediately
        affected shouldBe 1
        val updated = listenerRepository.findById(listener.id)
        updated?.outboxCompletedAt shouldNotBe null
    }

    @Test
    fun `wildcard filter matches any event type`() = runTest {
        // Given: Wildcard filter (no event type)
        val listenTask = createDefinitionListenTask(strategy = ListenStrategy.ONE)
        val wildcardFilter = createDefinitionFilter(listenId = listenTask.id, eventType = null)
        definitionListenCache.addListenTaskForTesting(listenTask)
        definitionListenCache.addFilterForTesting(wildcardFilter, testNamespace, testName, testVersion)

        val listener = createAndInsertListener(
            listenDefinitionId = listenTask.id,
            filters = listOf(EventFilter(type = null)) // Wildcard
        )

        // When: Any event
        val cloudEvent = createCloudEvent(type = "com.example.AnyEvent")
        val affected = cloudEventHandler.handleCloudEvent(cloudEvent)

        // Then: Matches
        affected shouldBe 1
        val updated = listenerRepository.findById(listener.id)
        updated?.outboxCompletedAt shouldNotBe null
    }

    @Test
    fun `multiple listeners can match same event`() = runTest {
        // Given: Two listeners for same event type
        val listenTask = createDefinitionListenTask(strategy = ListenStrategy.ONE)
        val filter = createDefinitionFilter(listenId = listenTask.id, eventType = testEventType)
        definitionListenCache.addListenTaskForTesting(listenTask)
        definitionListenCache.addFilterForTesting(filter, testNamespace, testName, testVersion)

        val listener1 = createAndInsertListener(
            listenDefinitionId = listenTask.id,
            filters = listOf(EventFilter(type = testEventType)),
            idSuffix = "-1"
        )
        val listener2 = createAndInsertListener(
            listenDefinitionId = listenTask.id,
            filters = listOf(EventFilter(type = testEventType)),
            idSuffix = "-2"
        )

        // When
        val cloudEvent = createCloudEvent(type = testEventType)
        val affected = cloudEventHandler.handleCloudEvent(cloudEvent)

        // Then: Both affected
        affected shouldBe 2

        val updated1 = listenerRepository.findById(listener1.id)
        val updated2 = listenerRepository.findById(listener2.id)
        updated1?.outboxCompletedAt shouldNotBe null
        updated2?.outboxCompletedAt shouldNotBe null
    }

    // Helper functions

    private fun createCloudEvent(
        type: String,
        source: String = "https://test.example.com",
        data: String? = """{"key":"value"}"""
    ): CloudEvent {
        val builder = CloudEventBuilder.v1()
            .withId(IDV7.random().toString())
            .withType(type)
            .withSource(URI.create(source))

        if (data != null) {
            builder.withData("application/json", data.toByteArray())
        }

        return builder.build()
    }

    private suspend fun createDefinitionListenTask(
        strategy: ListenStrategy = ListenStrategy.ONE,
        readAs: ListenAndReadAs = ListenAndReadAs.DATA
    ): DefinitionListenModel {
        val model = DefinitionListenModel(
            id = IDV7.random(),
            workflowNamespace = testNamespace,
            workflowName = testName,
            workflowVersion = testVersion,
            nodePosition = NodePosition("/do/listenTask"),
            strategy = strategy,
            readAs = readAs
        )
        // Insert into database for foreign key constraint
        definitionListenRepository.insert(model)
        return model
    }

    private fun createDefinitionFilter(
        listenId: IDV7,
        eventType: String?,
        filterIndex: Int = 0
    ): DefinitionListenFilterModel {
        return DefinitionListenFilterModel(
            id = IDV7.random(),
            listenId = listenId,
            filterIndex = filterIndex,
            eventId = null,
            eventType = eventType,
            eventSource = null,
            eventSubject = null,
            eventDatacontenttype = null,
            eventDataschema = null,
            eventTime = null,
            eventData = null,
            correlations = null
        )
    }

    private suspend fun createAndInsertListener(
        listenDefinitionId: IDV7,
        filters: List<EventFilter>,
        idSuffix: String = ""
    ): ListenerModel {
        val workflowId = WorkflowId(IDV7.random())

        val nodeStack = NodeStack(
            listOf(
                NodePosition.root to RootState(
                    startedAt = Clock.System.now(),
                    workflowId = workflowId,
                    workflowInput = JsonNull
                )
            )
        )

        val config = ListenConfig(
            strategy = ListenStrategy.ONE,
            filters = filters,
            readAs = ListenAndReadAs.DATA
        )

        val listenStarted = WorkflowEvent.ListenStarted(
            nodeStack = nodeStack,
            rawOutput = JsonNull,
            config = config
        )

        val listenerId = nodeStack.deriveIdempotentId("-listen$idSuffix")

        val listener = ListenerModel(
            id = listenerId,
            listenDefinitionId = listenDefinitionId,
            instanceMessage = InstanceMessage(
                workflowInfo = WorkflowInfo(
                    workflowNamespace = testNamespace,
                    workflowName = testName,
                    workflowVersion = testVersion
                ),
                workflowState = listenStarted
            ),
            workflowId = workflowId,
            workflowPosition = listenStarted.nodePosition,
            timeoutAt = null,
            outboxScheduledFor = Clock.System.now()
        )

        listenerRepository.insert(listener)
        return listener
    }
}
