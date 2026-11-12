// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.pgmq

import com.lemline.common.coroutines.ApplicationClassLoaderContext
import com.lemline.common.values.IDV7
import com.lemline.core.testcases.impl.WorkflowTestResult
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionService
import com.lemline.runner.eventschedules.EventScheduleRepository
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.testcases.AnalyticsWorkflowResultAwaiter
import io.cloudevents.core.builder.CloudEventBuilder
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqEventScheduleExecutionTest {
    @Inject lateinit var definitions: DefinitionService
    @Inject lateinit var schedules: EventScheduleRepository
    @Inject lateinit var database: DatabaseManager
    @Inject lateinit var results: AnalyticsWorkflowResultAwaiter

    @Test
    fun `published CloudEvents start workflows through the durable outbox and complete`() =
        runBlocking(ApplicationClassLoaderContext()) {
            val name = "event-schedule-${IDV7.random()}"
            val definition = DefinitionModel.from("""
                document:
                  dsl: '1.0.0'
                  namespace: test
                  name: $name
                  version: '1.0.0'
                schedule:
                  on:
                    one:
                      with:
                        type: $name
                do:
                  - finish:
                      set:
                        received: ${'$'}{ ${'$'}workflow.input[0].data.value }
            """.trimIndent())
            assertEquals(DefinitionService.SaveResult.CREATED, definitions.save(definition))
            try {
                for (value in listOf("first", "second")) {
                    val event = CloudEventBuilder.v1().withId(value).withSource(URI("/event-schedule-test"))
                        .withType(name).withData("application/json", "{\"value\":\"$value\"}".toByteArray()).build()
                    // Publish via the real PGMQ queue; the production consumer, relay and runner execute it.
                    database.withConnection { conn ->
                        conn.prepareStatement("SELECT pgmq.send(?, ?::jsonb)").use {
                            it.setString(1, "lemline-test-cloudevents")
                            it.setString(2, CloudEventService.serialize(event))
                            it.execute()
                        }
                    }
                    val row = withTimeout(30.seconds) {
                        var found = schedules.listAll().firstOrNull {
                            it.workflowName == definition.name && it.workflowState.nodeStack.rootState.workflowInput.toString().contains("\"$value\"")
                        }
                        while (found == null) {
                            delay(100)
                            found = schedules.listAll().firstOrNull {
                                it.workflowName == definition.name && it.workflowState.nodeStack.rootState.workflowInput.toString().contains("\"$value\"")
                            }
                        }
                        found
                    }
                    val result = assertIs<WorkflowTestResult.Success>(results.awaitWorkflowResult(row.workflowId, 30.seconds))
                    assertEquals(buildJsonObject { put("received", JsonPrimitive(value)) }, result.output)
                }
                assertEquals(2, schedules.listAll().count { it.workflowName == definition.name })
            } finally {
                definitions.delete(definition.namespace, definition.name, definition.version)
                WorkflowCache.remove(definition.namespace, definition.name, definition.version)
            }
        }
}
