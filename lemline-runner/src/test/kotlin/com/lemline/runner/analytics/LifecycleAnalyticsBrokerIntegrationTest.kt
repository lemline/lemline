// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.common.values.IDV7
import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.tests.profiles.KafkaAnalyticsProfile
import com.lemline.runner.tests.profiles.PgmqAnalyticsProfile
import com.lemline.runner.tests.profiles.PgmqPostgresAnalyticsProfile
import com.lemline.runner.tests.profiles.PgmqSQLiteAnalyticsProfile
import com.lemline.runner.tests.profiles.SqliteTransportAnalyticsProfile
import com.lemline.runner.tests.resources.SqliteMessagingTestClient
import com.lemline.runner.tests.profiles.RabbitMQAnalyticsProfile
import com.lemline.runner.gateway.analytics.AnalyticsDataSourceProvider
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

internal abstract class LifecycleAnalyticsBrokerIntegrationTestBase(
    private val broker: String,
    private val table: String = "public.lemline_lifecycle_events",
) {
    @Inject
    lateinit var lifecycleEventEmitter: LifecycleEventEmitter

    @Inject
    lateinit var analyticsDataSourceProvider: AnalyticsDataSourceProvider

    @BeforeEach
    fun cleanAnalyticsTable() {
        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM $table")
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

    protected fun buildEvent(eventId: String): CloudEvent {
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

    protected fun countRowsByEventId(eventId: String): Int {
        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM $table WHERE event_id = ?"
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

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqPostgresAnalyticsProfile::class)
@Tag("integration")
internal class PgmqPostgresLifecycleAnalyticsIntegrationTest :
    LifecycleAnalyticsBrokerIntegrationTestBase("pgmq", "lemline.lemline_lifecycle_events")

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqSQLiteAnalyticsProfile::class)
@Tag("integration")
internal class PgmqSQLiteLifecycleAnalyticsIntegrationTest :
    LifecycleAnalyticsBrokerIntegrationTestBase("pgmq", ANALYTICS_LIFECYCLE_EVENTS_TABLE)

@QuarkusTest
@TestProfile(SqliteTransportAnalyticsProfile::class)
@Tag("integration")
internal class SqliteTransportLifecycleAnalyticsIntegrationTest : LifecycleAnalyticsBrokerIntegrationTestBase("sqlite") {
    @Test
    fun `analytics deduplicates a logical message replayed after the broker ack`() = runBlocking {
        val event = buildEvent("sqlite-replayed-${IDV7.random()}")
        val id = IDV7.random().toString()
        val queue = "lemline-lifecycle-events"
        val payload = CloudEventService.serialize(event)
        SqliteMessagingTestClient().use { client ->
            repeat(2) {
                client.publish(queue, payload, id)
                withTimeout(10_000) {
                    while (client.isActive(queue, id)) delay(30)
                }
                assertFalse(client.isActive("$queue.dlq", id))
                assertEquals(1, countRowsByEventId(event.id))
            }
        }
    }
}
