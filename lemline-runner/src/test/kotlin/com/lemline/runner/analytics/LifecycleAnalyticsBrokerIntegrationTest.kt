// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.tests.profiles.KafkaAnalyticsProfile
import com.lemline.runner.tests.profiles.PgmqAnalyticsProfile
import com.lemline.runner.tests.profiles.RabbitMQAnalyticsProfile
import io.agroal.api.AgroalDataSource
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.quarkus.agroal.DataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

internal abstract class LifecycleAnalyticsBrokerIntegrationTestBase(
    private val broker: String,
) {
    @Inject
    lateinit var lifecycleEventEmitter: LifecycleEventEmitter

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
    fun `ingests one lifecycle event through broker`() = runBlocking {
        val event = buildEvent("$broker-single")

        lifecycleEventEmitter.emit(event)

        awaitEventCount(event.id, expected = 1)
        assertEquals(1, countRowsByEventId(event.id))
    }

    @Test
    fun `deduplicates duplicate lifecycle events through broker`() = runBlocking {
        val event = buildEvent("$broker-duplicate")

        repeat(4) {
            lifecycleEventEmitter.emit(event)
        }

        awaitEventCount(event.id, expected = 1)
        assertEquals(1, countRowsByEventId(event.id))
    }

    private fun buildEvent(eventId: String): CloudEvent {
        return CloudEventBuilder.v1()
            .withId(eventId)
            .withSource(URI.create("urn:lemline:test:$broker"))
            .withType("io.lemline.lifecycle.workflow.started")
            .withTime(OffsetDateTime.now())
            .withDataContentType("application/json")
            .withExtension("lemlineworkflowid", UUID.randomUUID().toString())
            .withExtension("lemlineworkflownamespace", "test")
            .withExtension("lemlineworkflowname", "analytics-$broker")
            .withExtension("lemlineworkflowversion", "1.0.0")
            .withData("""{"status":"started","broker":"$broker"}""".toByteArray(Charsets.UTF_8))
            .build()
    }

    private fun awaitEventCount(eventId: String, expected: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (countRowsByEventId(eventId) == expected) {
                return
            }
            Thread.sleep(100)
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

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaAnalyticsProfile::class)
@Tag("integration")
internal class KafkaLifecycleAnalyticsIntegrationTest : LifecycleAnalyticsBrokerIntegrationTestBase("kafka")

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQAnalyticsProfile::class)
@Tag("integration")
internal class RabbitLifecycleAnalyticsIntegrationTest : LifecycleAnalyticsBrokerIntegrationTestBase("rabbitmq")

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqAnalyticsProfile::class)
@Tag("integration")
internal class PgmqLifecycleAnalyticsIntegrationTest : LifecycleAnalyticsBrokerIntegrationTestBase("pgmq")
