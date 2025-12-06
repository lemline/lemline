// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.definitions.DefinitionListenService
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.cloudevents.CLOUDEVENTS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_OUT_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_OUT_CHANNEL
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.ListenerQueryKey
import com.lemline.runner.repositories.ListenerRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.jackson.JsonFormat
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySink
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import java.net.URI
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * End-to-end tests for listen task workflow execution.
 *
 * These tests verify that listen tasks work correctly in the full messaging infrastructure:
 * 1. Workflow starts and emits ListenStarted event
 * 2. Listener is registered in database
 * 3. CloudEvent is delivered via cloudevents-in channel
 * 4. Listener matches the event and workflow resumes
 * 5. Workflow completes with the event data
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExperimentalTime
@ExperimentalSerializationApi
internal class ListenTaskEndToEndTest {

    companion object {
        /** Short delay for database writes to complete */
        private const val DB_WRITE_DELAY_MS = 200L

        /** Short delay between sequential event sends */
        private const val EVENT_INTERVAL_MS = 300L

        /** Delay for outbox scheduler to process (1s polling + buffer) */
        private const val OUTBOX_PROCESSING_DELAY_MS = 3500L

        /** Delay between message processing iterations */
        private const val PROCESSING_INTERVAL_MS = 50L
    }

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var listenerRepository: ListenerRepository

    @Inject
    lateinit var definitionListenService: DefinitionListenService

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    private lateinit var commandsSource: InMemorySource<String>
    private lateinit var commandsSink: InMemorySink<String>
    private lateinit var eventsSource: InMemorySource<String>
    private lateinit var eventsSink: InMemorySink<String>
    private lateinit var cloudEventsSource: InMemorySource<String>

    private val testNamespace = WorkflowNamespace("test")
    private val testVersion = WorkflowVersion("1.0.0")

    /**
     * Tracks completed workflows to avoid losing completion events during multi-workflow tests.
     * Maps workflow ID to its output JSON.
     */
    private val completedWorkflows = mutableMapOf<WorkflowId, JsonElement>()

    @BeforeEach
    fun setup() = runTest {
        // Initialize channels
        commandsSource = connector.source(COMMANDS_IN_CHANNEL)
        commandsSink = connector.sink(COMMANDS_OUT_CHANNEL)
        eventsSource = connector.source(EVENTS_IN_CHANNEL)
        eventsSink = connector.sink(EVENTS_OUT_CHANNEL)
        cloudEventsSource = connector.source(CLOUDEVENTS_IN_CHANNEL)

        // Clear channels
        commandsSink.clear()
        eventsSink.clear()

        // Clear database state
        listenerRepository.deleteAll()
        DefinitionCache.clear()

        // Clear tracked completions
        completedWorkflows.clear()
    }

    @Test
    fun `listen task with ONE strategy completes on first matching event`() = runTest {
        // Given: A workflow with a listen task
        val workflowName = WorkflowName("listen-one-test-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - setup:
                  set:
                    status: waiting
              - listenForEvent:
                  listen:
                    to:
                      one:
                        with:
                          type: com.example.TestEvent
              - complete:
                  set:
                    status: completed
                    received: true
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        // When: Start the workflow
        val workflowId = startWorkflow(workflowName)

        // Process until ListenStarted is emitted
        val listenStartedEvent = processUntilListenStarted(workflowId)
        listenStartedEvent shouldNotBe null

        // Verify listener is registered in database
        realDelay(DB_WRITE_DELAY_MS)
        val listeners = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listeners.size shouldBe 1

        // Send matching CloudEvent
        sendCloudEvent(
            type = "com.example.TestEvent",
            data = """{"message": "hello"}"""
        )

        // Allow time for CloudEvent processing and outbox completion (1s polling interval)
        // Uses realDelay because runTest skips virtual delays but outbox runs in real time
        realDelay(OUTBOX_PROCESSING_DELAY_MS)

        // Verify listener was completed by CloudEvent processing
        val listenersAfterEvent = findListenersByWorkflow(testNamespace, workflowName, testVersion)
            .filter { it.outboxCompletedAt == null } // Only count active listeners
        listenersAfterEvent.size shouldBe 0 // 0 because listener was completed

        // Process until workflow completes
        val result = processUntilCompletion(workflowId)

        // Then: Workflow should complete successfully
        result shouldNotBe null
        result!!.jsonObject["status"]?.jsonPrimitive?.content shouldBe "completed"
        result.jsonObject["received"]?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `listen task with ANY strategy completes on first matching event from multiple types`() = runTest {
        // Given: A workflow with a listen task that accepts any of two event types
        val workflowName = WorkflowName("listen-any-test-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - listenForAnyEvent:
                  listen:
                    to:
                      any:
                        - with:
                            type: com.example.EventA
                        - with:
                            type: com.example.EventB
              - complete:
                  set:
                    matched: true
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        // When: Start the workflow
        val workflowId = startWorkflow(workflowName)

        // Process until ListenStarted is emitted
        val listenStartedEvent = processUntilListenStarted(workflowId)
        listenStartedEvent shouldNotBe null

        // Verify listener is registered
        realDelay(DB_WRITE_DELAY_MS)
        val listeners = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listeners.size shouldBe 1

        // Send EventB (the second option)
        sendCloudEvent(
            type = "com.example.EventB",
            data = """{"source": "B"}"""
        )

        // Allow time for CloudEvent processing and outbox completion
        realDelay(OUTBOX_PROCESSING_DELAY_MS)

        // Process until workflow completes
        val result = processUntilCompletion(workflowId)

        // Then: Workflow should complete
        result shouldNotBe null
        result!!.jsonObject["matched"]?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `listen task with ALL strategy waits for all events`() = runTest {
        // Given: A workflow with a listen task requiring all events
        val workflowName = WorkflowName("listen-all-test-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - listenForAllEvents:
                  listen:
                    to:
                      all:
                        - with:
                            type: com.example.First
                        - with:
                            type: com.example.Second
              - complete:
                  set:
                    allReceived: true
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        // When: Start the workflow
        val workflowId = startWorkflow(workflowName)

        // Process until ListenStarted is emitted
        val listenStartedEvent = processUntilListenStarted(workflowId)
        listenStartedEvent shouldNotBe null

        // Verify listener is registered
        realDelay(DB_WRITE_DELAY_MS)
        val listenersBeforeEvents = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listenersBeforeEvents.size shouldBe 1

        // Send first event
        sendCloudEvent(
            type = "com.example.First",
            data = """{"order": 1}"""
        )

        // Allow processing
        realDelay(EVENT_INTERVAL_MS)

        // Workflow should NOT be complete yet - listener still active
        processMessages(workflowId, maxIterations = 20)
        val listenersAfterFirst = findListenersByWorkflow(testNamespace, workflowName, testVersion)
            .filter { it.outboxCompletedAt == null } // Only count active listeners
        listenersAfterFirst.size shouldBe 1 // Still active (not completed)

        // Send second event
        sendCloudEvent(
            type = "com.example.Second",
            data = """{"order": 2}"""
        )

        // Allow time for CloudEvent processing and outbox completion
        realDelay(OUTBOX_PROCESSING_DELAY_MS)

        // Process until workflow completes
        val result = processUntilCompletion(workflowId)

        // Then: Workflow should complete
        result shouldNotBe null
        result!!.jsonObject["allReceived"]?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `non-matching event does not complete listener`() = runTest {
        // Given: A workflow listening for a specific event type
        val workflowName = WorkflowName("listen-nomatch-test-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - listenForSpecificEvent:
                  listen:
                    to:
                      one:
                        with:
                          type: com.example.ExpectedEvent
              - complete:
                  set:
                    done: true
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        // When: Start the workflow
        val workflowId = startWorkflow(workflowName)
        val listenStartedEvent = processUntilListenStarted(workflowId)
        listenStartedEvent shouldNotBe null

        // Verify listener is registered
        realDelay(DB_WRITE_DELAY_MS)
        val listenersInitial = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listenersInitial.size shouldBe 1

        // Send wrong event type
        sendCloudEvent(
            type = "com.example.WrongEvent",
            data = """{"wrong": true}"""
        )

        // Allow processing
        realDelay(EVENT_INTERVAL_MS)

        // Process messages
        processMessages(workflowId, maxIterations = 20)

        // Then: Listener should still be active (wrong event type shouldn't match)
        val listeners = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listeners.size shouldBe 1 // Still active because event type didn't match
    }

    @Test
    fun `listen task with ANY + until expression accumulates events until condition is met`() = runTest {
        // Given: A workflow with a listen task that accumulates until expression is true
        val workflowName = WorkflowName("listen-any-until-expr-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - collectReadings:
                  listen:
                    to:
                      any:
                        - with:
                            type: com.example.Reading
                      until: .[2] != null
              - complete:
                  set:
                    collected: true
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        // When: Start the workflow
        val workflowId = startWorkflow(workflowName)

        // Process until ListenStarted is emitted
        val listenStartedEvent = processUntilListenStarted(workflowId)
        listenStartedEvent shouldNotBe null

        // Verify listener is registered
        realDelay(DB_WRITE_DELAY_MS)
        val listenersBeforeEvents = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listenersBeforeEvents.size shouldBe 1

        // Send first event - should accumulate, not complete
        sendCloudEvent(
            type = "com.example.Reading",
            data = """{"value": 1}"""
        )
        realDelay(EVENT_INTERVAL_MS)

        // Verify listener still active
        processMessages(workflowId, maxIterations = 20)
        val listenersAfterFirst = findListenersByWorkflow(testNamespace, workflowName, testVersion)
            .filter { it.outboxCompletedAt == null }
        listenersAfterFirst.size shouldBe 1 // Still active

        // Send second event - should accumulate, not complete
        sendCloudEvent(
            type = "com.example.Reading",
            data = """{"value": 2}"""
        )
        realDelay(EVENT_INTERVAL_MS)

        // Verify listener still active
        processMessages(workflowId, maxIterations = 20)
        val listenersAfterSecond = findListenersByWorkflow(testNamespace, workflowName, testVersion)
            .filter { it.outboxCompletedAt == null }
        listenersAfterSecond.size shouldBe 1 // Still active

        // Send third event - should trigger completion (length >= 3)
        sendCloudEvent(
            type = "com.example.Reading",
            data = """{"value": 3}"""
        )

        // Allow time for CloudEvent processing and outbox completion
        realDelay(OUTBOX_PROCESSING_DELAY_MS)

        // Process until workflow completes
        val result = processUntilCompletion(workflowId)

        // Then: Workflow should complete
        result shouldNotBe null
        result!!.jsonObject["collected"]?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `listen task with ANY + until event completes on termination event`() = runTest {
        // Given: A workflow with a listen task that accumulates until a termination event
        val workflowName = WorkflowName("listen-any-until-event-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - monitorVitals:
                  listen:
                    to:
                      any:
                        - with:
                            type: com.hospital.vitals.temperature
                        - with:
                            type: com.hospital.vitals.bpm
                      until:
                        one:
                          with:
                            type: com.hospital.patient.discharged
              - complete:
                  set:
                    monitored: true
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        // When: Start the workflow
        val workflowId = startWorkflow(workflowName)

        // Process until ListenStarted is emitted
        val listenStartedEvent = processUntilListenStarted(workflowId)
        listenStartedEvent shouldNotBe null

        // Verify listener is registered
        realDelay(DB_WRITE_DELAY_MS)
        val listenersBeforeEvents = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listenersBeforeEvents.size shouldBe 1

        // Send temperature reading - should accumulate
        sendCloudEvent(
            type = "com.hospital.vitals.temperature",
            data = """{"temperature": 37.5}"""
        )
        realDelay(EVENT_INTERVAL_MS)

        // Verify listener still active
        processMessages(workflowId, maxIterations = 20)
        val listenersAfterTemp = findListenersByWorkflow(testNamespace, workflowName, testVersion)
            .filter { it.outboxCompletedAt == null }
        listenersAfterTemp.size shouldBe 1 // Still active

        // Send bpm reading - should accumulate
        sendCloudEvent(
            type = "com.hospital.vitals.bpm",
            data = """{"bpm": 72}"""
        )
        realDelay(EVENT_INTERVAL_MS)

        // Verify listener still active
        processMessages(workflowId, maxIterations = 20)
        val listenersAfterBpm = findListenersByWorkflow(testNamespace, workflowName, testVersion)
            .filter { it.outboxCompletedAt == null }
        listenersAfterBpm.size shouldBe 1 // Still active

        // Send termination event - should trigger completion
        sendCloudEvent(
            type = "com.hospital.patient.discharged",
            data = """{"reason": "recovered"}"""
        )

        // Allow time for CloudEvent processing and outbox completion
        realDelay(OUTBOX_PROCESSING_DELAY_MS)

        // Process until workflow completes
        val result = processUntilCompletion(workflowId)

        // Then: Workflow should complete
        result shouldNotBe null
        result!!.jsonObject["monitored"]?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `listen task with ALL strategy completes when single event matches multiple filters`() = runTest {
        // Given: A workflow with ALL strategy where filters can match the same event
        // This tests the case where one CloudEvent satisfies multiple filter conditions
        val workflowName = WorkflowName("listen-all-multi-match-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - listenForAllConditions:
                  listen:
                    to:
                      all:
                        - with:
                            type: com.example.Order
                            data: ${'$'}{ .priority == "high" }
                        - with:
                            type: com.example.Order
                            data: ${'$'}{ .amount > 1000 }
              - complete:
                  set:
                    allConditionsMet: true
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        // When: Start the workflow
        val workflowId = startWorkflow(workflowName)

        // Process until ListenStarted is emitted
        val listenStartedEvent = processUntilListenStarted(workflowId)
        listenStartedEvent shouldNotBe null

        // Verify listener is registered
        realDelay(DB_WRITE_DELAY_MS)
        val listenersBeforeEvent = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listenersBeforeEvent.size shouldBe 1

        // Send ONE event that matches BOTH filters (priority=high AND amount>1000)
        sendCloudEvent(
            type = "com.example.Order",
            data = """{"priority": "high", "amount": 2000}"""
        )

        // Allow time for CloudEvent processing and outbox completion
        realDelay(OUTBOX_PROCESSING_DELAY_MS)

        // Process until workflow completes
        val result = processUntilCompletion(workflowId)

        // Then: Workflow should complete because the single event satisfied both filters
        result shouldNotBe null
        result!!.jsonObject["allConditionsMet"]?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `multiple workflows can listen for same event type`() = runTest {
        // Given: Two workflow instances listening for the same event type
        val workflowName = WorkflowName("listen-multi-test-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - listenForSharedEvent:
                  listen:
                    to:
                      one:
                        with:
                          type: com.example.SharedEvent
              - complete:
                  set:
                    received: true
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        // Start first workflow instance
        val workflowId1 = startWorkflow(workflowName)
        val listenStarted1 = processUntilListenStarted(workflowId1)
        listenStarted1 shouldNotBe null

        // Start second workflow instance
        val workflowId2 = startWorkflow(workflowName)
        val listenStarted2 = processUntilListenStarted(workflowId2)
        listenStarted2 shouldNotBe null

        // Verify both listeners are registered
        realDelay(EVENT_INTERVAL_MS)
        val listeners = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listeners.size shouldBe 2

        // Send one event - both workflows should receive it
        sendCloudEvent(
            type = "com.example.SharedEvent",
            data = """{"broadcast": true}"""
        )

        // Allow time for CloudEvent processing and outbox completion
        realDelay(OUTBOX_PROCESSING_DELAY_MS)

        // Process until both complete
        val result1 = processUntilCompletion(workflowId1)
        val result2 = processUntilCompletion(workflowId2)

        // Then: Both workflows should complete
        result1 shouldNotBe null
        result2 shouldNotBe null
        result1!!.jsonObject["received"]?.jsonPrimitive?.content shouldBe "true"
        result2!!.jsonObject["received"]?.jsonPrimitive?.content shouldBe "true"
    }

    // ========================================
    // Output Format Verification Tests
    // These tests verify that the listen task output is always an array
    // as specified in the Serverless Workflow DSL specification.
    // ========================================

    @Test
    fun `ONE strategy output is an array with one element`() = runTest {
        // Given: A workflow with a listen task (output is directly the workflow result)
        val workflowName = WorkflowName("listen-one-output-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - listenForEvent:
                  listen:
                    to:
                      one:
                        with:
                          type: com.example.OutputTest
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        val workflowId = startWorkflow(workflowName)
        processUntilListenStarted(workflowId)
        realDelay(DB_WRITE_DELAY_MS)

        // Send event with specific data
        sendCloudEvent(
            type = "com.example.OutputTest",
            data = """{"message": "hello", "value": 42}"""
        )

        realDelay(OUTBOX_PROCESSING_DELAY_MS)
        val result = processUntilCompletion(workflowId)

        // Then: Output should be an array with exactly one element
        result shouldNotBe null
        val events = result!!.jsonArray
        events.size shouldBe 1
        events[0].jsonObject["message"]?.jsonPrimitive?.content shouldBe "hello"
        events[0].jsonObject["value"]?.jsonPrimitive?.int shouldBe 42
    }

    @Test
    fun `ANY strategy output is an array with one element`() = runTest {
        // Given: A workflow with an ANY listen task (output is directly the workflow result)
        val workflowName = WorkflowName("listen-any-output-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - listenForAnyEvent:
                  listen:
                    to:
                      any:
                        - with:
                            type: com.example.EventTypeA
                        - with:
                            type: com.example.EventTypeB
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        val workflowId = startWorkflow(workflowName)
        processUntilListenStarted(workflowId)
        realDelay(DB_WRITE_DELAY_MS)

        // Send event matching the second filter
        sendCloudEvent(
            type = "com.example.EventTypeB",
            data = """{"source": "B", "priority": 1}"""
        )

        realDelay(OUTBOX_PROCESSING_DELAY_MS)
        val result = processUntilCompletion(workflowId)

        // Then: Output should be an array with exactly one element
        result shouldNotBe null
        val events = result!!.jsonArray
        events.size shouldBe 1
        events[0].jsonObject["source"]?.jsonPrimitive?.content shouldBe "B"
        events[0].jsonObject["priority"]?.jsonPrimitive?.int shouldBe 1
    }

    @Test
    fun `ALL strategy output is an array with one element per filter`() = runTest {
        // Given: A workflow with an ALL listen task (output is directly the workflow result)
        val workflowName = WorkflowName("listen-all-output-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - listenForAllEvents:
                  listen:
                    to:
                      all:
                        - with:
                            type: com.example.FirstEvent
                        - with:
                            type: com.example.SecondEvent
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        val workflowId = startWorkflow(workflowName)
        processUntilListenStarted(workflowId)
        realDelay(DB_WRITE_DELAY_MS)

        // Send first event
        sendCloudEvent(
            type = "com.example.FirstEvent",
            data = """{"order": 1, "name": "first"}"""
        )
        realDelay(EVENT_INTERVAL_MS)

        // Send second event
        sendCloudEvent(
            type = "com.example.SecondEvent",
            data = """{"order": 2, "name": "second"}"""
        )

        realDelay(OUTBOX_PROCESSING_DELAY_MS)
        val result = processUntilCompletion(workflowId)

        // Then: Output should be an array with exactly two elements (one per filter)
        result shouldNotBe null
        val events = result!!.jsonArray
        events.size shouldBe 2

        // Verify both events are present (order may vary based on filter index)
        val eventNames = events.map { it.jsonObject["name"]?.jsonPrimitive?.content }.toSet()
        eventNames shouldBe setOf("first", "second")
    }

    @Test
    fun `ANY with until expression output is an array with accumulated events`() = runTest {
        // Given: A workflow with ANY+until expression (output is directly the workflow result)
        val workflowName = WorkflowName("listen-any-until-output-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - collectReadings:
                  listen:
                    to:
                      any:
                        - with:
                            type: com.example.Reading
                      until: .[2] != null
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        val workflowId = startWorkflow(workflowName)
        processUntilListenStarted(workflowId)
        realDelay(DB_WRITE_DELAY_MS)

        // Send three events to trigger the until condition
        sendCloudEvent(type = "com.example.Reading", data = """{"value": 10}""")
        realDelay(EVENT_INTERVAL_MS)
        sendCloudEvent(type = "com.example.Reading", data = """{"value": 20}""")
        realDelay(EVENT_INTERVAL_MS)
        sendCloudEvent(type = "com.example.Reading", data = """{"value": 30}""")

        realDelay(OUTBOX_PROCESSING_DELAY_MS)
        val result = processUntilCompletion(workflowId)

        // Then: Output should be an array with all accumulated events
        result shouldNotBe null
        val events = result!!.jsonArray
        events.size shouldBe 3

        // Verify all values are present
        val values = events.map { it.jsonObject["value"]?.jsonPrimitive?.int }.toSet()
        values shouldBe setOf(10, 20, 30)
    }

    @Test
    fun `ANY with until event output is an array with accumulated events excluding termination`() = runTest {
        // Given: A workflow with ANY+until event (output is directly the workflow result)
        val workflowName = WorkflowName("listen-any-until-event-output-${System.currentTimeMillis()}")
        val yaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: $workflowName
              version: '1.0.0'
            do:
              - monitorVitals:
                  listen:
                    to:
                      any:
                        - with:
                            type: com.example.Measurement
                      until:
                        one:
                          with:
                            type: com.example.StopMonitoring
        """.trimIndent()

        registerWorkflow(yaml, workflowName)

        val workflowId = startWorkflow(workflowName)
        processUntilListenStarted(workflowId)
        realDelay(DB_WRITE_DELAY_MS)

        // Send measurement events
        sendCloudEvent(type = "com.example.Measurement", data = """{"reading": 100}""")
        realDelay(EVENT_INTERVAL_MS)
        sendCloudEvent(type = "com.example.Measurement", data = """{"reading": 200}""")
        realDelay(EVENT_INTERVAL_MS)

        // Send termination event (should NOT be included in output)
        sendCloudEvent(type = "com.example.StopMonitoring", data = """{"reason": "complete"}""")

        realDelay(OUTBOX_PROCESSING_DELAY_MS)
        val result = processUntilCompletion(workflowId)

        // Then: Output should be an array with only the measurement events (not termination)
        result shouldNotBe null
        val events = result!!.jsonArray
        events.size shouldBe 2

        // Verify only measurement events are present (termination event excluded)
        val readings = events.map { it.jsonObject["reading"]?.jsonPrimitive?.int }.toSet()
        readings shouldBe setOf(100, 200)

        // Verify termination event data is NOT in output
        val hasReason = events.any { it.jsonObject.containsKey("reason") }
        hasReason shouldBe false
    }

    // ========================================
    // Helper Functions
    // ========================================

    /**
     * Real-time delay that doesn't get skipped by runTest.
     *
     * Unlike kotlinx.coroutines.delay which is virtual-time in runTest,
     * this delay runs in real time - essential for testing outbox schedulers
     * and other real-time async operations.
     */
    private suspend fun realDelay(millis: Long) {
        withContext(Dispatchers.Default) {
            delay(millis)
        }
    }

    /**
     * Finds all listeners registered for a workflow definition.
     *
     * @param namespace Workflow namespace
     * @param name Workflow name
     * @param version Workflow version
     * @return All listeners (both active and completed) for the workflow
     */
    private suspend fun findListenersByWorkflow(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): List<ListenerModel> {
        val workflowInfo = WorkflowInfo(namespace, name, version)
        val listenTasks = DefinitionCache.getListenTasks(workflowInfo)
        if (listenTasks.isEmpty()) return emptyList()

        val keys = listenTasks.map { listenTask ->
            ListenerQueryKey(
                workflowInfo = workflowInfo,
                position = listenTask.nodePosition,
                correlationValuesJson = null
            )
        }
        return listenerRepository.findByKeys(keys)
    }

    /**
     * Registers a workflow definition in the repository and cache.
     *
     * @param yaml The workflow YAML definition
     * @param name The workflow name (must match the name in the YAML)
     */
    private suspend fun registerWorkflow(yaml: String, name: WorkflowName) {
        val existing = definitionRepository.findByNameAndVersion(testNamespace, name, testVersion)
        if (existing != null) {
            definitionRepository.delete(existing)
        }

        val model = DefinitionModel(
            namespace = testNamespace,
            name = name,
            version = testVersion,
            definition = yaml
        )
        definitionRepository.insert(model)

        // Parse and cache workflow definition
        // Note: Listen task definitions are retrieved on-demand from the cached workflow
        DefinitionCache.parseAndPut(yaml)
    }

    /**
     * Starts a workflow instance with empty input.
     *
     * @param name The workflow name to start
     * @return The new workflow instance ID
     */
    private fun startWorkflow(name: WorkflowName): WorkflowId {
        val workflowId = WorkflowId.random()
        val workflowInfo = WorkflowInfo(testNamespace, name, testVersion)

        val initialCommand = StepByStepOrchestrator.initCmd(
            workflowId = workflowId,
            workflowInput = JsonNull,
            hasWaitingParent = true,
            startedAt = Clock.System.now()
        )

        val initialMessage = InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = initialCommand
        )

        commandsSource.send(initialMessage.toJsonString())
        return workflowId
    }

    /**
     * Processes messages until a ListenStarted event is received.
     *
     * Routes commands and events between channels until the workflow emits
     * a ListenStarted event, indicating a listen task has begun waiting.
     *
     * @param mainWorkflowId The workflow ID to monitor
     * @param timeoutMs Maximum time to wait (default: 10 seconds)
     * @return The ListenStarted event, or null if timeout reached
     */
    private suspend fun processUntilListenStarted(
        mainWorkflowId: WorkflowId,
        timeoutMs: Long = 10000
    ): WorkflowEvent.ListenStarted? {
        val startTime = System.currentTimeMillis()

        // Initial delay to let handlers start processing
        realDelay(PROCESSING_INTERVAL_MS * 2)

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Route commands first
            val commands = commandsSink.received().toList()
            if (commands.isNotEmpty()) {
                commandsSink.clear()
                for (cmd in commands) {
                    commandsSource.send(cmd.payload)
                }
            }

            // Check events for ListenStarted
            val events = eventsSink.received().toList()
            if (events.isNotEmpty()) {
                eventsSink.clear()
                for (eventMsg in events) {
                    val event = InstanceMessage.fromJsonString<WorkflowEvent>(eventMsg.payload)
                    when (val state = event.workflowState) {
                        is WorkflowEvent.ListenStarted -> {
                            if (event.workflowId == mainWorkflowId) {
                                // Forward to events channel for database processing
                                eventsSource.send(eventMsg.payload)
                                // Give time for database processing
                                realDelay(PROCESSING_INTERVAL_MS * 2)
                                return state
                            }
                        }

                        else -> {
                            eventsSource.send(eventMsg.payload)
                        }
                    }
                }
            }

            // Wait before next iteration
            realDelay(PROCESSING_INTERVAL_MS)
        }
        return null
    }

    /**
     * Sends a CloudEvent to the cloudevents-in channel.
     *
     * @param type The CloudEvent type (e.g., "com.example.OrderCreated")
     * @param data The event data as JSON string
     */
    private fun sendCloudEvent(type: String, data: String) {
        val cloudEvent = CloudEventBuilder.v1()
            .withId(IDV7.random().toString())
            .withType(type)
            .withSource(URI.create("https://test.example.com"))
            .withData("application/json", data.toByteArray())
            .build()

        val jsonFormat = JsonFormat()
        val serialized = String(jsonFormat.serialize(cloudEvent))
        cloudEventsSource.send(serialized)
    }

    /**
     * Processes messages until the specified workflow completes.
     *
     * Routes messages between channels, handles wait events automatically,
     * and tracks workflow completions across multiple concurrent workflows.
     *
     * @param mainWorkflowId The workflow ID to wait for completion
     * @param timeoutMs Maximum time to wait (default: 10 seconds)
     * @return The workflow output, or null if timeout reached
     */
    private suspend fun processUntilCompletion(
        mainWorkflowId: WorkflowId,
        timeoutMs: Long = 10000
    ): JsonElement? {
        // Check if already completed (from previous processing)
        completedWorkflows[mainWorkflowId]?.let { return it }

        val startTime = System.currentTimeMillis()
        var result: JsonElement? = null
        var emptyIterations = 0
        val maxEmptyIterations = (2500 / PROCESSING_INTERVAL_MS).toInt()

        while (result == null && System.currentTimeMillis() - startTime < timeoutMs) {
            result = processMessages(mainWorkflowId)

            // Also check stored completions
            if (result == null) {
                completedWorkflows[mainWorkflowId]?.let { return it }
            }

            // If no result and no activity, wait a bit for async processing
            if (result == null) {
                emptyIterations++
                if (emptyIterations > maxEmptyIterations) {
                    break
                }
                // Use delay to allow real async processing
                realDelay(PROCESSING_INTERVAL_MS)
            } else {
                emptyIterations = 0
            }
        }

        return result
    }

    /**
     * Routes messages between channels for one processing iteration.
     *
     * This method:
     * 1. Routes commands from sink to source for handler processing
     * 2. Processes workflow events (completion, failure, wait)
     * 3. Tracks all workflow completions to handle concurrent workflows
     * 4. Auto-resumes wait tasks for faster test execution
     *
     * @param mainWorkflowId The workflow ID to check for completion
     * @param maxIterations Maximum routing iterations before returning
     * @return The workflow output if completed, null otherwise
     * @throws AssertionError if the main workflow fails
     */
    @Suppress("NestedBlockDepth")
    private suspend fun processMessages(
        mainWorkflowId: WorkflowId,
        maxIterations: Int = 100
    ): JsonElement? {
        var iterations = 0
        var hasActivity = false

        while (iterations < maxIterations) {
            iterations++
            hasActivity = false

            // Route commands
            val commands = commandsSink.received().toList()
            if (commands.isNotEmpty()) {
                commandsSink.clear()
                hasActivity = true
                for (cmd in commands) {
                    commandsSource.send(cmd.payload)
                }
            }

            // Check events - process ALL events in batch before returning
            val events = eventsSink.received().toList()
            var foundMainCompletion: JsonElement? = null
            if (events.isNotEmpty()) {
                eventsSink.clear()
                hasActivity = true
                for (eventMsg in events) {
                    val event = InstanceMessage.fromJsonString<WorkflowEvent>(eventMsg.payload)
                    when (val state = event.workflowState) {
                        is WorkflowEvent.WorkflowCompleted -> {
                            // Store all completions to avoid losing events in multi-workflow tests
                            completedWorkflows[event.workflowId] = state.output
                            if (event.workflowId == mainWorkflowId) {
                                foundMainCompletion = state.output
                            }
                        }

                        is WorkflowEvent.WorkflowFailed -> {
                            if (event.workflowId == mainWorkflowId) {
                                throw AssertionError("Workflow failed: ${state.error}")
                            }
                        }

                        is WorkflowEvent.WaitStarted -> {
                            // Immediately resume wait for tests
                            val resumeCommand = InstanceMessage(
                                workflowInfo = event.workflowInfo,
                                workflowState = state.resume()
                            )
                            commandsSource.send(resumeCommand.toJsonString())
                        }

                        else -> {
                            eventsSource.send(eventMsg.payload)
                        }
                    }
                }
                // Return after processing ALL events in the batch
                if (foundMainCompletion != null) {
                    return foundMainCompletion
                }
            }

            // If no activity, allow a real-time wait for async handlers to process
            if (!hasActivity) {
                realDelay(PROCESSING_INTERVAL_MS)
                // Break if still no activity after the wait
                if (commandsSink.received().isEmpty() && eventsSink.received().isEmpty()) {
                    break
                }
            } else {
                // Give handlers time to process routed messages
                realDelay(PROCESSING_INTERVAL_MS)
            }
        }

        return null
    }
}
