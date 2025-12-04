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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

    // Track completed workflows to avoid losing events during multi-workflow tests
    private val completedWorkflows = mutableMapOf<WorkflowId, JsonObject>()

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
        delay(200) // Allow time for database write
        val listeners = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listeners.size shouldBe 1

        // Send matching CloudEvent
        sendCloudEvent(
            type = "com.example.TestEvent",
            data = """{"message": "hello"}"""
        )

        // Allow time for CloudEvent processing
        delay(500)

        // Verify listener was completed by CloudEvent processing
        val listenersAfterEvent = findListenersByWorkflow(testNamespace, workflowName, testVersion)
            .filter { it.outboxCompletedAt == null } // Only count active listeners
        listenersAfterEvent.size shouldBe 0 // 0 because listener was completed

        // Process until workflow completes
        val result = processUntilCompletion(workflowId)

        // Then: Workflow should complete successfully
        result shouldNotBe null
        result!!["status"]?.jsonPrimitive?.content shouldBe "completed"
        result["received"]?.jsonPrimitive?.content shouldBe "true"
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
        delay(200)
        val listeners = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listeners.size shouldBe 1

        // Send EventB (the second option)
        sendCloudEvent(
            type = "com.example.EventB",
            data = """{"source": "B"}"""
        )

        // Allow time for CloudEvent processing
        delay(500)

        // Process until workflow completes
        val result = processUntilCompletion(workflowId)

        // Then: Workflow should complete
        result shouldNotBe null
        result!!["matched"]?.jsonPrimitive?.content shouldBe "true"
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
        delay(200)
        val listenersBeforeEvents = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listenersBeforeEvents.size shouldBe 1

        // Send first event
        sendCloudEvent(
            type = "com.example.First",
            data = """{"order": 1}"""
        )

        // Allow processing
        delay(300)

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

        // Allow time for CloudEvent processing
        delay(500)

        // Process until workflow completes
        val result = processUntilCompletion(workflowId)

        // Then: Workflow should complete
        result shouldNotBe null
        result!!["allReceived"]?.jsonPrimitive?.content shouldBe "true"
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
        delay(200)
        val listenersInitial = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listenersInitial.size shouldBe 1

        // Send wrong event type
        sendCloudEvent(
            type = "com.example.WrongEvent",
            data = """{"wrong": true}"""
        )

        // Allow processing
        delay(300)

        // Process messages
        processMessages(workflowId, maxIterations = 20)

        // Then: Listener should still be active (wrong event type shouldn't match)
        val listeners = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listeners.size shouldBe 1 // Still active because event type didn't match
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
        delay(300)
        val listeners = findListenersByWorkflow(testNamespace, workflowName, testVersion)
        listeners.size shouldBe 2

        // Send one event - both workflows should receive it
        sendCloudEvent(
            type = "com.example.SharedEvent",
            data = """{"broadcast": true}"""
        )

        // Allow time for CloudEvent processing
        delay(500)

        // Process until both complete
        val result1 = processUntilCompletion(workflowId1)
        val result2 = processUntilCompletion(workflowId2)

        // Then: Both workflows should complete
        result1 shouldNotBe null
        result2 shouldNotBe null
        result1!!["received"]?.jsonPrimitive?.content shouldBe "true"
        result2!!["received"]?.jsonPrimitive?.content shouldBe "true"
    }

    // Helper functions

    /**
     * Helper to find listeners by workflow definition.
     * Queries listeners by workflow identity (namespace, name, version).
     */
    private suspend fun findListenersByWorkflow(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): List<ListenerModel> {
        // Get all listen tasks from the workflow and query listeners for each position
        val workflow = DefinitionCache.getWorkflow(namespace, name, version)
            ?: return emptyList()

        val listenTasks = definitionListenService.extractListenTasks(workflow)
        val keys = listenTasks.map { listenTask ->
            ListenerQueryKey(
                namespace = namespace,
                name = name,
                version = version,
                position = listenTask.nodePosition,
                correlationValuesJson = null
            )
        }
        return listenerRepository.findByKeys(keys)
    }

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

    private suspend fun processUntilListenStarted(
        mainWorkflowId: WorkflowId,
        timeoutMs: Long = 10000
    ): WorkflowEvent.ListenStarted? {
        val startTime = System.currentTimeMillis()

        // Initial delay to let handlers start processing
        delay(100)

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
                                delay(100)
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
            delay(50)
        }
        return null
    }

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

    private suspend fun processUntilCompletion(
        mainWorkflowId: WorkflowId,
        timeoutMs: Long = 10000
    ): JsonObject? {
        // Check if already completed (from previous processing)
        completedWorkflows[mainWorkflowId]?.let { return it }

        val startTime = System.currentTimeMillis()
        var result: JsonObject? = null
        var emptyIterations = 0
        val maxEmptyIterations = 50 // Wait up to 2.5 seconds if no messages

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
                delay(50)
            } else {
                emptyIterations = 0
            }
        }

        return result
    }

    @Suppress("NestedBlockDepth")
    private suspend fun processMessages(
        mainWorkflowId: WorkflowId,
        maxIterations: Int = 100
    ): JsonObject? {
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

            // Check events
            val events = eventsSink.received().toList()
            if (events.isNotEmpty()) {
                eventsSink.clear()
                hasActivity = true
                for (eventMsg in events) {
                    val event = InstanceMessage.fromJsonString<WorkflowEvent>(eventMsg.payload)
                    when (val state = event.workflowState) {
                        is WorkflowEvent.WorkflowCompleted -> {
                            // Store all completions to avoid losing events in multi-workflow tests
                            completedWorkflows[event.workflowId] = state.output.jsonObject
                            if (event.workflowId == mainWorkflowId) {
                                return state.output.jsonObject
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
            }

            // If no activity, allow a tiny wait for messages to appear
            if (!hasActivity) {
                delay(10)
                // Break if still no activity after the wait
                if (commandsSink.received().isEmpty() && eventsSink.received().isEmpty()) {
                    break
                }
            }
        }

        return null
    }
}
