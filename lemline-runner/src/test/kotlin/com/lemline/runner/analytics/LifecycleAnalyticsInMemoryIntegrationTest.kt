// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.messaging.lifecycle.LIFECYCLEEVENTS_IN_CHANNEL
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.tests.profiles.InMemoryAnalyticsProfile
import io.agroal.api.AgroalDataSource
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.quarkus.agroal.DataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

@RequiresDocker
@QuarkusTest
@TestProfile(InMemoryAnalyticsProfile::class)
@Tag("integration")
internal class LifecycleAnalyticsInMemoryIntegrationTest {

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var analyticsService: LifecycleAnalyticsService

    @Inject
    @DataSource("analytics")
    lateinit var analyticsDataSource: AgroalDataSource

    @BeforeEach
    fun cleanAnalyticsTable() {
        analyticsDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE public.lemline_lifecycle_events")
            }
        }
    }

    @Test
    fun `ingests one lifecycle event from in-memory channel`() {
        val event = buildEvent("evt-single")
        connector.source<String>(LIFECYCLEEVENTS_IN_CHANNEL).send(CloudEventService.serialize(event))

        awaitEventCount(event.id, expected = 1)
        assertEquals(1, countRowsByEventId(event.id))
    }

    @Test
    fun `deduplicates duplicate lifecycle events from in-memory channel`() {
        val event = buildEvent("evt-duplicate")
        val payload = CloudEventService.serialize(event)
        val source = connector.source<String>(LIFECYCLEEVENTS_IN_CHANNEL)

        repeat(4) { source.send(payload) }

        awaitEventCount(event.id, expected = 1)
        assertEquals(1, countRowsByEventId(event.id))
    }

    @Test
    fun `concurrent duplicate ingests remain single-row`() = runBlocking {
        val event = buildEvent("evt-concurrent")
        val inserted = AtomicInteger(0)
        val duplicates = AtomicInteger(0)

        val jobs = (1..32).map {
            async(Dispatchers.IO) {
                when (analyticsService.ingest(event)) {
                    IngestResult.INSERTED -> inserted.incrementAndGet()
                    IngestResult.DUPLICATE -> duplicates.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        awaitEventCount(event.id, expected = 1)
        assertEquals(1, inserted.get())
        assertEquals(31, duplicates.get())
        assertEquals(1, countRowsByEventId(event.id))
    }

    private fun buildEvent(eventId: String): CloudEvent {
        val workflowId = UUID.randomUUID()
        return CloudEventBuilder.v1()
            .withId(eventId)
            .withSource(URI.create("urn:lemline:test:workflow"))
            .withType("io.lemline.lifecycle.workflow.started")
            .withTime(OffsetDateTime.now())
            .withDataContentType("application/json")
            .withExtension("lemlineworkflowid", workflowId.toString())
            .withExtension("lemlineworkflownamespace", "test")
            .withExtension("lemlineworkflowname", "workflow-a")
            .withExtension("lemlineworkflowversion", "1.0.0")
            .withData("""{"status":"started"}""".toByteArray(Charsets.UTF_8))
            .build()
    }

    private fun awaitEventCount(eventId: String, expected: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (countRowsByEventId(eventId) == expected) {
                return
            }
            Thread.sleep(50)
        }

        fail("Expected $expected row(s) for event_id='$eventId', but found ${countRowsByEventId(eventId)}")
    }

    private fun countRowsByEventId(eventId: String): Int {
        analyticsDataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM public.lemline_lifecycle_events WHERE event_id = ?"
            ).use { stmt ->
                stmt.setString(1, eventId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

}
