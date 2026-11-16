// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.CloudEventHook
import com.lemline.core.lifecycleevents.CloudEventLifecycleHook
import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.listeners.CloudEventService
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LifecycleAnalyticsServiceTest {

    @Test
    fun `toRow maps standard and extension attributes`() {
        val workflowId = UUID.fromString("018f4958-d7d8-7a5c-bf54-3e504c0e7df2")
        val eventTime = OffsetDateTime.parse("2026-02-20T10:15:30Z")

        val event = CloudEventBuilder.v1()
            .withId("evt-1")
            .withSource(URI.create("urn:lemline:test"))
            .withType("workflow.started")
            .withTime(eventTime)
            .withSubject("subject-1")
            .withDataContentType("application/json")
            .withDataSchema(URI.create("https://example.com/schema"))
            .withExtension("lemlineworkflowid", workflowId.toString())
            .withExtension("lemlineworkflownamespace", "demo")
            .withExtension("lemlineworkflowname", "order-workflow")
            .withExtension("lemlineworkflowversion", "1.2.3")
            .withData("application/json", "{\"status\":\"ok\"}".toByteArray())
            .build()

        val service = LifecycleAnalyticsService(
            repository = LifecycleAnalyticsRepository("public")
        )

        val row = service.toRow(event)

        assertEquals("evt-1", row.eventId)
        assertEquals("urn:lemline:test", row.source)
        assertEquals("workflow.started", row.type)
        assertEquals("1.0", row.specVersion)
        assertEquals("subject-1", row.subject)
        assertEquals(eventTime, row.eventTime)
        assertEquals("application/json", row.dataContentType)
        assertEquals("https://example.com/schema", row.dataSchema)
        assertEquals(workflowId, row.workflowId)
        assertEquals("demo", row.workflowNamespace)
        assertEquals("order-workflow", row.workflowName)
        assertEquals("1.2.3", row.workflowVersion)

        val payloadEvent = CloudEventService.deserialize(row.payload)
        assertEquals(event.id, payloadEvent.id)
        assertEquals(event.type, payloadEvent.type)
        assertEquals(event.source, payloadEvent.source)
    }

    @Test
    fun `toRow ignores malformed workflow id extension`() {
        val event = CloudEventBuilder.v1()
            .withId("evt-2")
            .withSource(URI.create("urn:lemline:test"))
            .withType("workflow.started")
            .withExtension("lemlineworkflowid", "not-a-uuid")
            .build()

        val service = LifecycleAnalyticsService(
            repository = LifecycleAnalyticsRepository("public")
        )

        val row = service.toRow(event)

        assertNull(row.workflowId)
        assertNotNull(row.payload)
    }
    @Test
    fun `extension lifecycle payload keeps task and fault origin through analytics serialization`() = kotlinx.coroutines.runBlocking<Unit> {
        val workflow = WorkflowCache.parseYamlAndPut("""
            document: { dsl: '1.0.0', namespace: analytics, name: extensions, version: '1.0.0' }
            use:
              extensions:
                - audit:
                    extend: set
                    after:
                      - failedAudit:
                          raise: { error: { type: 'https://example.com/audit', status: 503 } }
            do:
              - owner: { set: { paymentId: p1 } }
        """.trimIndent())
        val events = mutableListOf<CloudEvent>()
        val emitter = object : LifecycleEventEmitter {
            override suspend fun emit(cloudEvent: CloudEvent) { events.add(cloudEvent) }
        }
        kotlin.test.assertFailsWith<com.lemline.core.errors.InternalException> {
            FullOrchestrator.start(workflow, serde = true,
                activityExecutor = MockActivityExecutor.empty(),
                functionResolver = MockFunctionResolver(),
                cloudEventHook = object : CloudEventHook {
                    override suspend fun emit(event: CloudEvent) = Unit
                    override fun receive() = kotlinx.coroutines.flow.emptyFlow<CloudEvent>()
                }, lifecycleHook = CloudEventLifecycleHook(emitter))
        }
        val service = LifecycleAnalyticsService(LifecycleAnalyticsRepository("public"))
        val owner = "/do/0/owner"
        val origin = "$owner/_ext/0/after/do/0/failedAudit"
        val faults = events.filter { it.type.contains("task.faulted") }
        kotlin.test.assertTrue(faults.size >= 2)
        for (event in faults) {
            val row = service.toRow(event)
            val decoded = CloudEventService.deserialize(row.payload)
            assertEquals(event.id, decoded.id)
            assertEquals(event.data!!.toBytes().decodeToString(), decoded.data!!.toBytes().decodeToString())
            assertEquals("analytics", row.workflowNamespace)
            kotlin.test.assertTrue(row.payload.contains(origin))
        }
        kotlin.test.assertFalse(events.any { it.type.contains("task.completed") && it.data!!.toBytes().decodeToString().contains("\"task\":\"$owner\"") })
    }

}
