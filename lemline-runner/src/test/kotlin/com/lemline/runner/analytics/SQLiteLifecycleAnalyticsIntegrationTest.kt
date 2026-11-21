// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.runner.common.config.SQLiteAnalyticsTime
import com.lemline.runner.common.messaging.LIFECYCLEEVENTS_IN_CHANNEL
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventSource
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.resources.definitionstats.DefinitionStatsFilter
import com.lemline.runner.gateway.resources.definitionstats.DefinitionStatsQueryService
import com.lemline.runner.gateway.resources.definitionstats.DefinitionStatsWatchService
import com.lemline.runner.gateway.resources.instanceevents.InstanceEventFilter
import com.lemline.runner.gateway.resources.instanceevents.InstanceEventQueryService
import com.lemline.runner.gateway.resources.instanceevents.InstanceEventWatchService
import com.lemline.runner.gateway.resources.instances.InstanceListFilter
import com.lemline.runner.gateway.resources.instances.InstanceQueryService
import com.lemline.runner.gateway.resources.instances.InstanceStatus
import com.lemline.runner.gateway.resources.instances.InstanceWatchService
import com.lemline.runner.gateway.resources.instances.NamespaceActivityMonitor
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.tests.profiles.SQLiteAnalyticsProfile
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.net.URI
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(SQLiteAnalyticsProfile::class)
@Tag("integration")
internal class SQLiteLifecycleAnalyticsIntegrationTest :
    LifecycleAnalyticsInMemoryIntegrationTestBase(ANALYTICS_LIFECYCLE_EVENTS_TABLE) {

    @Inject
    lateinit var repository: LifecycleAnalyticsRepository

    @Inject
    lateinit var instances: InstanceQueryService

    @Inject
    lateinit var events: InstanceEventQueryService

    @Inject
    lateinit var eventWatch: InstanceEventWatchService

    @Inject
    lateinit var instanceWatch: InstanceWatchService

    @Inject
    lateinit var workflowEvents: WorkflowAnalyticsEventSource

    @Inject
    lateinit var stats: DefinitionStatsQueryService

    @Inject
    lateinit var statsWatch: DefinitionStatsWatchService

    @Inject
    lateinit var activityMonitor: NamespaceActivityMonitor

    @Inject
    lateinit var metrics: MeterRegistry

    private val workflowId = UUID.fromString("019abcde-3456-789a-bcde-0123456789ab")
    private val eventTime = OffsetDateTime.parse("1970-01-01T02:59:59.999999987+03:00")
    private val projectedTime = Instant.parse("1969-12-31T23:59:59.999999Z")
    private val namespace = "sqlite-test"

    @Test
    fun `only the exact event identity conflicts and concurrent redelivery preserves the first payload`() = runBlocking {
        val original = event("Opaque-Id", LifecycleEventType.WORKFLOW_CREATED)
        val before = Instant.now()
        sendAndAwait(original)

        val replacement = event(original.id, LifecycleEventType.WORKFLOW_FAULTED, subject = "changed")
        val redeliveries = List(24) { async(Dispatchers.IO) { analyticsService.ingest(replacement) } }.awaitAll()
        assertEquals(List(24) { IngestResult.DUPLICATE }, redeliveries)

        val otherSource = event(original.id, LifecycleEventType.WORKFLOW_STARTED, source = "urn:SQLite:source")
        val otherId = event("opaque-id", LifecycleEventType.WORKFLOW_COMPLETED)
        assertEquals(IngestResult.INSERTED, analyticsService.ingest(otherSource))
        assertEquals(IngestResult.INSERTED, analyticsService.ingest(otherId))

        val history = events.listEvents(InstanceEventFilter(workflowId)).events
        assertEquals(listOf(original, otherSource, otherId).map(CloudEventService::serialize), history.map { it.cloudEventJson })

        analyticsDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM $table ORDER BY id LIMIT 1").use { row ->
                    assertTrue(row.next())
                    assertEquals(workflowId.toString(), row.getString("lemline_workflow_id"))
                    assertEquals(-1L, row.getLong("event_time"))
                    val ingestedAt = SQLiteAnalyticsTime.decode(row.getLong("ingested_at"))
                    assertTrue(ingestedAt in before.minusNanos(999)..Instant.now())
                }
            }
        }

        val invalid = LifecycleAnalyticsModel(
            eventId = "invalid", source = original.source.toString(), type = original.type,
            specVersion = "1.0", subject = null, eventTime = null, dataContentType = null, dataSchema = null,
            workflowId = null, workflowNamespace = null, workflowName = null, workflowVersion = null, payload = "{",
        )
        assertFailsWith<SQLException> { repository.insert(invalid) }
        assertFailsWith<SQLException> { repository.insert(invalid.copy(eventId = original.id)) }

        val malformed = event("missing-projections", LifecycleEventType.TASK_STARTED, rawWorkflowId = "1-2-3-4-5", time = null)
        sendAndAwait(malformed)
        analyticsDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM $table WHERE event_id = 'missing-projections'").use { row ->
                    assertTrue(row.next())
                    assertNull(row.getObject("lemline_workflow_id"))
                    assertNull(row.getObject("event_time"))
                    assertEquals(CloudEventService.serialize(malformed), row.getString("payload"))
                }
            }
        }
    }

    @Test
    fun `gateway pages same-time events and instances then tails live changes`() = runBlocking {
        val created = event("created", LifecycleEventType.WORKFLOW_CREATED)
        val started = event("started", LifecycleEventType.WORKFLOW_STARTED)
        val task = event("task", LifecycleEventType.TASK_STARTED, time = null)
        listOf(created, started, task).forEach(::sendAndAwait)
        val otherId = UUID.fromString("019abcde-3456-789a-bcde-0123456789ac")
        sendAndAwait(event("other-created", LifecycleEventType.WORKFLOW_CREATED, id = otherId))
        val filter = InstanceListFilter(
            namespace = namespace, createdFrom = eventTime.toInstant(), instanceIdPrefix = "019abcde", pageSize = 1,
        )
        val snapshot = instances.latestLifecycleSequence()
        val first = instances.listInstances(filter, snapshot)
        val second = instances.listInstances(filter, snapshot, assertNotNull(first.nextPage))
        assertEquals(2L, first.totalCount)
        assertEquals(listOf(otherId, workflowId), (first.instances + second.instances).map { it.instanceId })
        assertNull(second.nextPage)
        assertTrue((first.instances + second.instances).all { it.createdAt == projectedTime })
        assertEquals(InstanceStatus.RUNNING, second.instances.single().status)
        assertTrue(instances.matchesFilter(second.instances.single(), filter))
        val uppercasePrefix = filter.copy(instanceIdPrefix = "019ABCDE")
        assertTrue(instances.listInstances(uppercasePrefix, snapshot).instances.isEmpty())
        assertTrue(instances.listChangedInstanceSequencesAfter(0, 10, uppercasePrefix).isEmpty())

        val historyFilter = InstanceEventFilter(workflowId, pageSize = 1)
        val one = events.listEvents(historyFilter)
        val two = events.listEvents(historyFilter.copy(afterSequenceExclusive = assertNotNull(one.nextCursor)))
        val three = events.listEvents(historyFilter.copy(afterSequenceExclusive = assertNotNull(two.nextCursor)))
        val history = one.events + two.events + three.events
        assertEquals(listOf(created, started, task).map(CloudEventService::serialize), history.map { it.cloudEventJson })
        assertEquals(listOf(projectedTime, projectedTime, null), history.map { it.eventTime })
        assertEquals(3, history.map { it.cursor }.distinct().size)
        assertNull(three.nextCursor)
        val fromEventSource = workflowEvents.listByWorkflowIdAfter(WorkflowId(IDV7(workflowId)), history.first().cursor, 2)
        assertEquals(history.drop(1).map { it.cursor }, fromEventSource.map { it.cursor })
        assertEquals(history.drop(1).map { it.cloudEventJson }, fromEventSource.map { it.cloudEventJson })

        val statsFilter = DefinitionStatsFilter(namespace = namespace, timeFrom = eventTime.toInstant())
        val completed = event("complete", LifecycleEventType.WORKFLOW_COMPLETED)
        withTimeout(10_000) {
            val liveEvent = async(start = CoroutineStart.UNDISPATCHED) {
                val seen = CompletableDeferred<String>()
                eventWatch.watch(workflowId, GatewayPrincipal.anonymous, three.latestCursor) {
                    seen.complete(it.cloudEventJson)
                    false
                }
                seen.await()
            }
            val liveInstance = async(start = CoroutineStart.UNDISPATCHED) {
                val seen = CompletableDeferred<InstanceStatus>()
                instanceWatch.watch(filter, snapshot) {
                    assertEquals(workflowId, it.instance.instanceId)
                    seen.complete(it.instance.status)
                    false
                }
                seen.await()
            }
            val liveStats = async(start = CoroutineStart.UNDISPATCHED) {
                val seen = CompletableDeferred<Long>()
                statsWatch.watch(statsFilter, snapshot) {
                    seen.complete(it.stats.completedCount)
                    false
                }
                seen.await()
            }
            connector.source<String>(LIFECYCLEEVENTS_IN_CHANNEL).send(CloudEventService.serialize(completed))
            assertEquals(CloudEventService.serialize(completed), liveEvent.await())
            assertEquals(InstanceStatus.COMPLETED, liveInstance.await())
            assertEquals(1L, liveStats.await())
        }

        val latest = instances.latestLifecycleSequence()
        val row = stats.listDefinitionStats(statsFilter, latest).stats.single()
        assertEquals(listOf(1L, 1L, 0L), listOf(row.startedCount, row.completedCount, row.runningCountAtTimeTo))
        assertTrue(stats.listDefinitionStats(statsFilter.copy(timeTo = eventTime.toInstant()), latest).stats.isEmpty())

        val noActivity = "sqlite-inactive"
        sendAndAwait(event("pending", LifecycleEventType.WORKFLOW_CREATED, namespace = noActivity))
        activityMonitor.onInstanceAccepted(noActivity)
        activityMonitor.onInstanceAccepted(namespace)
        assertEquals(1.0, inactiveStarts(noActivity))
        assertEquals(0.0, inactiveStarts(namespace))
    }

    @Test
    fun `gateway reads committed history while a separate writer ingests`() = runBlocking {
        val first = event("first", LifecycleEventType.WORKFLOW_CREATED)
        sendAndAwait(first)
        val attempted = CompletableDeferred<Unit>()

        analyticsDataSource.connection.use { writer ->
            analyticsDataSource.connection.use { reader ->
                // Both are checked out simultaneously, so these are separate physical connections.
                for (connection in listOf(writer, reader)) {
                    connection.createStatement().use { stmt ->
                        val pragmas = mapOf("journal_mode" to "wal", "foreign_keys" to "1", "synchronous" to "2", "busy_timeout" to "5000")
                        for ((pragma, expected) in pragmas) {
                            stmt.executeQuery("PRAGMA $pragma").use { rows ->
                                assertTrue(rows.next())
                                assertEquals(expected, rows.getString(1), pragma)
                            }
                        }
                    }
                }
            }

            writer.autoCommit = false
            val writing = try {
                writer.createStatement().use { it.executeUpdate("UPDATE $table SET subject = 'uncommitted'") }
                val pending = async(Dispatchers.IO) {
                    attempted.complete(Unit)
                    analyticsService.ingest(event("second", LifecycleEventType.WORKFLOW_STARTED))
                }
                attempted.await()
                withTimeout(2_000) {
                    val history = events.listEvents(InstanceEventFilter(workflowId)).events
                    assertEquals(CloudEventService.serialize(first), history.single().cloudEventJson)
                    assertFalse(pending.isCompleted)
                }
                pending
            } finally {
                writer.rollback()
            }
            assertEquals(IngestResult.INSERTED, writing.await())
        }
        assertEquals(2, events.listEvents(InstanceEventFilter(workflowId)).events.size)
    }

    private fun inactiveStarts(namespace: String): Double =
        metrics.find(NamespaceActivityMonitor.METRIC_INACTIVE_NAMESPACE_STARTS)
            .tag("namespace", namespace).counter()?.count() ?: 0.0

    private fun sendAndAwait(event: CloudEvent) {
        connector.source<String>(LIFECYCLEEVENTS_IN_CHANNEL).send(CloudEventService.serialize(event))
        awaitEventCount(event.id, expected = 1)
    }

    private fun event(
        eventId: String,
        type: LifecycleEventType,
        id: UUID = workflowId,
        rawWorkflowId: String = id.toString().uppercase(),
        time: OffsetDateTime? = eventTime,
        source: String = "urn:sqlite:source",
        namespace: String = this.namespace,
        subject: String = "original",
    ): CloudEvent = CloudEventBuilder.v1()
        .withId(eventId)
        .withSource(URI.create(source))
        .withType(type.type)
        .withTime(time)
        .withSubject(subject)
        .withDataSchema(URI.create("urn:sqlite:schema"))
        .withExtension("lemlineworkflowid", rawWorkflowId)
        .withExtension("lemlineworkflownamespace", namespace)
        .withExtension("lemlineworkflowname", "orders")
        .withExtension("lemlineworkflowversion", "1.0.0")
        .withExtension("extra", "retained")
        .withData("application/json", """{"subject":"$subject","items":[1,2]}""".toByteArray())
        .build()
}
