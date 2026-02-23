// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.kafka

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.resources.KafkaTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for Kafka messaging with loopback configuration.
 *
 * Uses the SAME topic for commands-in and commands-out (and events-in/out)
 * to create a natural message loop for end-to-end workflow testing.
 *
 * This profile configures:
 * - H2 (in-memory) database for persistence
 * - Kafka channels with loopback topics
 * - Lifecycle events with loopback for test verification
 * - Outbox schedulers enabled with fast polling for Wait/Fork/Retry tests
 */
class KafkaTestCaseProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration
            "lemline.database.type" to DatabaseType.H2.configValue,
            // Messaging configuration
            "lemline.messaging.type" to MessagingType.KAFKA.configValue,
            "lemline.messaging.commands.consumer.enabled" to "true",
            "lemline.messaging.commands.producer.enabled" to "true",
            "lemline.messaging.events.consumer.enabled" to "true",
            "lemline.messaging.events.producer.enabled" to "true",
            "lemline.messaging.cloudevents.consumer.enabled" to "true",
            "lemline.messaging.cloudevents.producer.enabled" to "true",

            // Enable lifecycle events producer so events flow through the broker
            "lemline.messaging.lifecycleevents.producer.enabled" to "true",

            // Orchestrator mode: ALL generates more messages for thorough end-to-end testing
            "lemline.orchestrator.mode" to "all",

            // Use SAME topic for in/out to create loopback
            "mp.messaging.incoming.commands-in.topic" to "lemline-commands",
            "mp.messaging.outgoing.commands-out.topic" to "lemline-commands",
            "mp.messaging.incoming.events-in.topic" to "lemline-events",
            "mp.messaging.outgoing.events-out.topic" to "lemline-events",

            // Lifecycle events loopback - same topic for producer and test listener
            "mp.messaging.outgoing.lifecycleevents-out.topic" to "lemline-lifecycle",
            "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-kafka",
            "mp.messaging.incoming.lifecycleevents-in.topic" to "lemline-lifecycle",
            "mp.messaging.incoming.lifecycleevents-in.auto.offset.reset" to "earliest",
            "mp.messaging.incoming.lifecycleevents-in.group.id" to "lemline-test-lifecycle-listener",

            // CloudEvents loopback - same topic for in/out
            "mp.messaging.incoming.cloudevents-in.topic" to "lemline-cloudevents",
            "mp.messaging.outgoing.cloudevents-out.topic" to "lemline-cloudevents",
            "mp.messaging.incoming.cloudevents-in.auto.offset.reset" to "earliest",

            "smallrye.messaging.kafka.topic.creation.enable" to "true",

            // Enable outbox schedulers for Wait/Fork/Retry tests
            "lemline.outbox.enabled" to "true",
            // Fast polling for tests (no jitter - start immediately for deterministic testing)
            "lemline.outbox.wait.outbox.every" to "1s",
            "lemline.outbox.wait.outbox.initial-jitter" to "0s",
            "lemline.outbox.retry.outbox.every" to "1s",
            "lemline.outbox.retry.outbox.initial-jitter" to "0s",
            "lemline.outbox.schedule.outbox.every" to "1s",
            "lemline.outbox.schedule.outbox.initial-jitter" to "0s",
            // Listener outbox config (for listen task tests)
            "lemline.outbox.listener.outbox.every" to "1s",
            "lemline.outbox.listener.outbox.initial-jitter" to "0s"
        )
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(QuarkusTestProfile.TestResourceEntry(KafkaTestResource::class.java))
    }

    override fun tags(): Set<String> {
        return setOf("kafka-testcase")
    }
}
