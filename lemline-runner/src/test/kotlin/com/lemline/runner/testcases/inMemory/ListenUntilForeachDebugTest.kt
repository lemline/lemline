// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.inMemory

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.testcases.WorkflowTestResult
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
import kotlin.test.assertEquals
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
private class ListenUntilForeachDebugTest {

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
    fun `test listen with until expression and foreach`() = runTest {
        lifecycleListener.clear()

        val namespace = WorkflowNamespace("test")
        val name = WorkflowName("listen-until-foreach-test")
        val version = WorkflowVersion("1.0.0")
        val workflowId = WorkflowId.random()

        val workflowYaml = $$"""
            document:
              dsl: '1.0.0'
              namespace: test
              name: listen-until-foreach-test
              version: '1.0.0'
            do:
              - collectReadings:
                  listen:
                    to:
                      any:
                        - with:
                            type: sensor.reading
                      until: . | any(.value > 100)
                  foreach:
                    do:
                      - processReading:
                          set:
                            processed: true
                            readingId: ${ .readingId }
                            value: ${ .value }
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
                    put("readingId", 2)
                    put("value", 25)
                }.toString().toByteArray()
            )
            .build()

        val reading3Event = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("https://test.example.com"))
            .withType("sensor.reading")
            .withData(
                "application/json",
                buildJsonObject {
                    put("readingId", 3)
                    put("value", 150)
                }.toString().toByteArray()
            )
            .build()

        cloudEventsIn.send(String(cloudEventJsonFormat.serialize(reading1Event), Charsets.UTF_8))
        realDelay(EVENT_INTERVAL_MS)
        cloudEventsIn.send(String(cloudEventJsonFormat.serialize(reading2Event), Charsets.UTF_8))
        realDelay(EVENT_INTERVAL_MS)
        cloudEventsIn.send(String(cloudEventJsonFormat.serialize(reading3Event), Charsets.UTF_8))
        realDelay(OUTBOX_PROCESSING_DELAY_MS)

        val allListeners = listenerRepository.listAll()
        println("Listeners count: ${allListeners.size}")
        allListeners.forEach { listener ->
            println("Listener ${listener.id}: workflowId=${listener.workflowId}, strategy=${listener.listenerStrategy}, hasForeach=${listener.hasForeach}, closedAt=${listener.closedAt}, outbox_delayed_until=${listener.outboxDelayedUntil}")
        }

        val connection = databaseManager.datasource.connection
        val eventsStmt =
            connection.prepareStatement("SELECT listener_id, event_id, filter_index, foreach_completed, outbox_scheduled_for, outbox_delayed_until, sort_key FROM lemline_listener_events")
        val eventsRs = eventsStmt.executeQuery()
        var eventCount = 0
        while (eventsRs.next()) {
            eventCount++
            println(
                "Event: listener_id=${eventsRs.getString(1)}, event_id=${eventsRs.getString(2)}, filter_index=${
                    eventsRs.getInt(
                        3
                    )
                }, foreach_completed=${eventsRs.getBoolean(4)}, outbox_scheduled_for=${eventsRs.getTimestamp(5)}, outbox_delayed_until=${
                    eventsRs.getTimestamp(
                        6
                    )
                }, sort_key=${eventsRs.getLong(7)}"
            )
        }
        println("Total events: $eventCount")
        eventsRs.close()
        eventsStmt.close()
        connection.close()

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
        assertTrue(result is WorkflowTestResult.Success, "Workflow should succeed")

        val output = result.output

        println("Output: $output")

        val arr = output.jsonArray
        assertEquals(3, arr.size, "Should have 3 processed readings")

        assertEquals(true, arr[0].jsonObject["processed"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(1, arr[0].jsonObject["readingId"]?.jsonPrimitive?.int)

        assertEquals(true, arr[1].jsonObject["processed"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(2, arr[1].jsonObject["readingId"]?.jsonPrimitive?.int)

        assertEquals(true, arr[2].jsonObject["processed"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(3, arr[2].jsonObject["readingId"]?.jsonPrimitive?.int)

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
