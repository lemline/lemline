// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.kafka

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_MIGRATE_AT_START
import com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.common.config.LEMLINE_ORCHESTRATOR_MODE
import com.lemline.runner.common.config.LEMLINE_OUTBOX_ENABLED
import com.lemline.runner.common.config.LEMLINE_OUTBOX_LISTENER_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_LISTENER_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.LEMLINE_OUTBOX_RETRY_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_RETRY_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.LEMLINE_OUTBOX_SCHEDULE_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_SCHEDULE_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.LEMLINE_OUTBOX_WAIT_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_WAIT_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.profiles.AnalyticsH2TestProfileSupport
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
            LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,
            // Messaging configuration
            LEMLINE_MESSAGING_TYPE to MessagingType.KAFKA.configValue,
            LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_ANALYTICS_MIGRATE_AT_START to "true",
            LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "false",

            // Orchestrator mode: ALL generates more messages for thorough end-to-end testing
            LEMLINE_ORCHESTRATOR_MODE to "all",

            // Use SAME topic for in/out to create loopback
            "mp.messaging.incoming.commands-in.topic" to "lemline-commands",
            "mp.messaging.incoming.events-in.topic" to "lemline-events",
            "mp.messaging.outgoing.commands-out.topic" to "lemline-commands",
            "mp.messaging.outgoing.events-out.topic" to "lemline-events",

            // Lifecycle events loopback - same topic for producer and test listener
            "mp.messaging.incoming.lifecycleevents-in.topic" to "lemline-lifecycle",
            "mp.messaging.incoming.lifecycleevents-in.auto.offset.reset" to "earliest",
            "mp.messaging.outgoing.lifecycleevents-out.topic" to "lemline-lifecycle",
            "mp.messaging.outgoing.lifecycleevents-out.auto.offset.reset" to "earliest",

            // CloudEvents loopback - same topic for in/out
            "mp.messaging.incoming.cloudevents-in.topic" to "lemline-cloudevents",
            "mp.messaging.incoming.cloudevents-in.auto.offset.reset" to "earliest",
            "mp.messaging.outgoing.cloudevents-out.topic" to "lemline-cloudevents",
            "mp.messaging.outgoing.cloudevents-out.auto.offset.reset" to "earliest",

            "smallrye.messaging.kafka.topic.creation.enable" to "true",

            // Enable outbox schedulers for Wait/Retry/Schedule tests
            LEMLINE_OUTBOX_ENABLED to "true",
            // Fast polling for tests (no jitter - start immediately for deterministic testing)
            LEMLINE_OUTBOX_WAIT_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_WAIT_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_RETRY_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_RETRY_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_SCHEDULE_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_SCHEDULE_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_LISTENER_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_LISTENER_OUTBOX_INITIAL_JITTER to "0s"
        ) + AnalyticsH2TestProfileSupport.overrides("lemline_analytics_kafka_testcase")
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(KafkaTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return setOf("kafka-testcase")
    }
}
