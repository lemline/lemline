// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.kafka

import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.tests.profiles.KafkaProfile
import com.lemline.runner.tests.resources.KafkaTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.kafka.KafkaConnector
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import java.time.Duration
import java.util.function.Function
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// A separate application is required because this test shuts down its connector.
class KafkaProducerShutdownProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = KafkaProfile().getConfigOverrides() + mapOf(
        LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "false",
        LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "false",
        LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "true",
        LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
    )

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
        listOf(QuarkusTestProfile.TestResourceEntry(KafkaTestResource::class.java))
}

@QuarkusTest
@TestProfile(KafkaProducerShutdownProfile::class)
@RequiresDocker
class KafkaProducerShutdownTest {
    @Inject
    @Any
    lateinit var connector: KafkaConnector

    @Test
    fun `connector shutdown terminates every producer network thread`() {
        val channels = setOf("commands-out", "events-out", "cloudevents-out", "lifecycleevents-out")
        assertEquals(channels, connector.producerChannels)
        val producers = channels.map { connector.getProducer<String, String>(it) }
        val networkThreads = producers.map { producer ->
            producer.runOnSendingThread(Function { true }).await().atMost(Duration.ofSeconds(10))
            val name = "kafka-producer-network-thread | ${producer.configuration()["client.id"]}"
            Thread.getAllStackTraces().keys.single { it.name == name }
        }

        val survivingThreads: List<String>
        try {
            // Exercise the same path as CDI shutdown. closeQuietly() can swallow close errors,
            // so successful return alone does not prove that the native clients were closed.
            connector.terminate(null)
            networkThreads.forEach { it.join(5_000) }
            survivingThreads = networkThreads.filter { it.isAlive }.map { it.name }
        } finally {
            // Keep a failing regression from leaking clients into subsequent test profiles.
            producers.forEach { it.unwrap()?.close(Duration.ZERO) }
        }
        assertTrue(survivingThreads.isEmpty(), "Producer threads survived shutdown: $survivingThreads")
    }
}
