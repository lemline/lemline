// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.inMemory

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.testcases.impl.WorkflowTestResult
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionRepository
import com.lemline.runner.listeners.ListenerRepository
import com.lemline.runner.messaging.cloudevents.CLOUDEVENTS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_OUT_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_OUT_CHANNEL
import com.lemline.runner.starters.Starter
import com.lemline.runner.testcases.TestLifecycleEventListener
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.jackson.JsonFormat
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import java.net.URI
import java.util.*
import java.util.concurrent.TimeoutException
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/**
 * Debug test for: "listen foreach raises error correctly"
 *
 * This test verifies that:
 * - When an error is raised in foreach processing, the workflow fails with that error
 * - The error type is correctly propagated
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
private class ListenForeachFailFastDebugTest {

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
    lateinit var databaseManager: DatabaseManager

    @Inject
    lateinit var lifecycleListener: TestLifecycleEventListener

    @Inject
    lateinit var starter: Starter

    @Inject
    lateinit var lifecycleHook: LifecycleEventHook

    @Inject
    lateinit var listenerRepository: ListenerRepository

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    private val cloudEventJsonFormat = JsonFormat()

    @Test
    fun `test listen foreach with outer try-catch stops on error and skips remaining events`() = runTest {
        lifecycleListener.clear()

        val namespace = WorkflowNamespace("test")
        val name = WorkflowName("listen-foreach-failfast-test")
        val version = WorkflowVersion("1.0.0")
        val workflowId = WorkflowId.random()

        // This workflow:
        // 1. Sets processedCount to 0
        // 2. Wraps listen/foreach in outer try-catch
        // 3. For each event:
        //    - If readingId == 2, raises an error (fails processing)
        //    - Otherwise, increments processedCount
        // 4. Outer catch captures error and returns processedCount
        //
        // Expected:
        // - Event 1: processed, count becomes 1
        // - Event 2: raises error, caught by outer try-catch
        // - Event 3: should NOT be processed (fail-fast behavior)
        // - Output: { failed: true, errorType: "...processing", processedBeforeError: 1 }
        val workflowYaml = $$"""
            document:
              dsl: '1.0.0'
              namespace: test
              name: listen-foreach-failfast-test
              version: '1.0.0'
            do:
              - setProcessedCount:
                  set:
                    processedCount: 0
                  export:
                    as: ${ . }
              - handleReadings:
                  try:
                    - collectReadings:
                        listen:
                          to:
                            any:
                              - with:
                                  type: sensor.reading
                            until: . | any(.value > 100)
                        foreach:
                          do:
                            - checkValue:
                                if: ${ .readingId == 2 }
                                raise:
                                  error:
                                    type: https://serverlessworkflow.io/errors/processing
                                    status: 400
                                    title: Processing failed
                            - incrementCount:
                                set:
                                  processedCount: ${ $context.processedCount + 1 }
                                export:
                                  as: ${ . }
                  catch:
                    as: caughtError
                    do:
                      - returnResult:
                          set:
                            failed: true
                            errorType: ${ $caughtError.type }
                            processedBeforeError: ${ $context.processedCount }
        """.trimIndent()

        val existing = definitionRepository.findByNameAndVersion(namespace, name, version)
        if (existing != null) {
            definitionRepository.delete(existing)
        }
        definitionRepository.insert(
            DefinitionModel(
                namespace = namespace,
                name = name,
                version = version,
                definition = workflowYaml
            )
        )

        val input = buildJsonObject { }

        val prepared = starter.prepareWorkflow(
            workflowId = workflowId,
            workflowNamespace = namespace,
            workflowName = name,
            optionalVersion = version,
            workflowInput = input,
            hasWaitingParent = false,
            zoneId = null,
            onError = { error -> throw IllegalStateException(error) }
        )

        val initialMessage = prepared.instanceMessage
            ?: throw IllegalStateException("Cannot test this workflow")

        prepared.onWorkflowCreated(lifecycleHook)

        val commandsIn = connector.source<String>(COMMANDS_IN_CHANNEL)
        val commandsOut = connector.sink<String>(COMMANDS_OUT_CHANNEL)
        val eventsIn = connector.source<String>(EVENTS_IN_CHANNEL)
        val eventsOut = connector.sink<String>(EVENTS_OUT_CHANNEL)
        val lifecycleEventsIn = connector.source<String>("lifecycleevents-in")
        val lifecycleEventsOut = connector.sink<String>("lifecycleevents-out")
        val cloudEventsIn = connector.source<String>(CLOUDEVENTS_IN_CHANNEL)

        commandsOut.clear()
        eventsOut.clear()
        lifecycleEventsOut.clear()

        commandsIn.send(initialMessage.toJsonString())

        val startTime = System.currentTimeMillis()
        val listenerWaitTimeout = 3000L
        var listenerFound = false

        while (System.currentTimeMillis() - startTime < listenerWaitTimeout) {
            routeMessages(commandsOut, commandsIn, eventsOut, eventsIn, lifecycleEventsOut, lifecycleEventsIn)

            val listener = listenerRepository.listAll().firstOrNull { it.workflowId == workflowId }
            if (listener != null) {
                println("Listener found for workflow $workflowId")
                listenerFound = true
                break
            }
            realDelay(PROCESSING_INTERVAL_MS)
        }

        assertTrue(listenerFound, "Listener should be registered")

        // Create the 3 sensor reading events
        val reading1Event = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("https://test.example.com"))
            .withType("sensor.reading")
            .withData(
                "application/json",
                buildJsonObject {
                    put("readingId", 1)
                    put("value", 10)
                }.toString().toByteArray()
            )
            .build()

        val reading2Event = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("https://test.example.com"))
            .withType("sensor.reading")
            .withData(
                "application/json",
                buildJsonObject {
                    put("readingId", 2)  // This will trigger the error!
                    put("value", 25)
                }.toString().toByteArray()
            )
            .build()

        val reading3ThresholdEvent = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("https://test.example.com"))
            .withType("sensor.reading")
            .withData(
                "application/json",
                buildJsonObject {
                    put("readingId", 3)
                    put("value", 150)  // This exceeds 100, but should NOT be processed due to fail-fast
                }.toString().toByteArray()
            )
            .build()

        println("Sending reading1Event (readingId=1, value=10) - should be processed successfully")
        cloudEventsIn.send(String(cloudEventJsonFormat.serialize(reading1Event), Charsets.UTF_8))
        realDelay(EVENT_INTERVAL_MS)

        println("Sending reading2Event (readingId=2, value=25) - should trigger error!")
        cloudEventsIn.send(String(cloudEventJsonFormat.serialize(reading2Event), Charsets.UTF_8))
        realDelay(EVENT_INTERVAL_MS)

        println("Sending reading3ThresholdEvent (readingId=3, value=150) - should NOT be processed")
        cloudEventsIn.send(String(cloudEventJsonFormat.serialize(reading3ThresholdEvent), Charsets.UTF_8))
        realDelay(OUTBOX_PROCESSING_DELAY_MS)

        // Debug: Print listener and event state
        val allListeners = listenerRepository.listAll()
        println("Listeners count: ${allListeners.size}")
        allListeners.forEach { listener ->
            println(
                "Listener ${listener.id}: workflowId=${listener.workflowId}, " +
                    "strategy=${listener.listenerStrategy}, hasForeach=${listener.hasForeach}, " +
                    "closedAt=${listener.closedAt}, outbox_delayed_until=${listener.outboxDelayedUntil}"
            )
        }

        val connection = databaseManager.datasource.connection
        val eventsStmt =
            connection.prepareStatement(
                "SELECT listener_id, event_id, filter_index, foreach_completed, " +
                    "outbox_scheduled_for, outbox_delayed_until, sort_key FROM lemline_listener_events"
            )
        val eventsRs = eventsStmt.executeQuery()
        var eventCount = 0
        while (eventsRs.next()) {
            eventCount++
            println(
                "Event: listener_id=${eventsRs.getString(1)}, event_id=${eventsRs.getString(2)}, " +
                    "filter_index=${eventsRs.getInt(3)}, foreach_completed=${eventsRs.getBoolean(4)}, " +
                    "outbox_scheduled_for=${eventsRs.getTimestamp(5)}, " +
                    "outbox_delayed_until=${eventsRs.getTimestamp(6)}, sort_key=${eventsRs.getLong(7)}"
            )
        }
        println("Total events in database: $eventCount")
        eventsRs.close()
        eventsStmt.close()
        connection.close()

        // Wait for workflow completion
        val deadline = System.currentTimeMillis() + 20_000L
        var result: WorkflowTestResult? = null

        while (System.currentTimeMillis() < deadline) {
            routeMessages(commandsOut, commandsIn, eventsOut, eventsIn, lifecycleEventsOut, lifecycleEventsIn)

            try {
                result = lifecycleListener.awaitWorkflowResult(workflowId, timeout = 0.seconds)
                break
            } catch (_: TimeoutException) {
            }

            realDelay(PROCESSING_INTERVAL_MS)
        }

        assertNotNull(result, "Workflow should complete")
        assertTrue(
            result is WorkflowTestResult.Failure,
            "Workflow should fail when error raised in foreach: $result"
        )

        val failure = result as WorkflowTestResult.Failure
        println("Failure: ${failure.error}")

        assertTrue(
            failure.error.contains("processing"),
            "Error should contain 'processing', got: ${failure.error}"
        )

        commandsOut.clear()
        eventsOut.clear()
        lifecycleEventsOut.clear()
    }

    private fun routeMessages(
        commandsOut: io.smallrye.reactive.messaging.memory.InMemorySink<String>,
        commandsIn: io.smallrye.reactive.messaging.memory.InMemorySource<String>,
        eventsOut: io.smallrye.reactive.messaging.memory.InMemorySink<String>,
        eventsIn: io.smallrye.reactive.messaging.memory.InMemorySource<String>,
        lifecycleEventsOut: io.smallrye.reactive.messaging.memory.InMemorySink<String>,
        lifecycleEventsIn: io.smallrye.reactive.messaging.memory.InMemorySource<String>
    ) {
        val commands = commandsOut.received().toList()
        if (commands.isNotEmpty()) {
            commandsOut.clear()
            for (cmd in commands) {
                commandsIn.send(cmd.payload)
            }
        }

        val events = eventsOut.received().toList()
        if (events.isNotEmpty()) {
            eventsOut.clear()
            for (event in events) {
                eventsIn.send(event.payload)
            }
        }

        val lifecycleEvents = lifecycleEventsOut.received().toList()
        if (lifecycleEvents.isNotEmpty()) {
            lifecycleEventsOut.clear()
            for (event in lifecycleEvents) {
                lifecycleEventsIn.send(event.payload)
            }
        }
    }

    private suspend fun realDelay(millis: Long) {
        withContext(Dispatchers.Default) {
            delay(millis)
        }
    }
}
