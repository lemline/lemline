// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.rabbitmq

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
import com.lemline.runner.tests.resources.AnalyticsPostgresTestResource
import com.lemline.runner.tests.resources.RabbitMQTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for RabbitMQ messaging with loopback configuration.
 *
 * Uses a shared exchange to create a loopback where messages produced to
 * commands-out are routed back to commands-in (and similarly for events).
 *
 * This profile configures:
 * - H2 (in-memory) database for persistence
 * - RabbitMQ channels with loopback via shared exchanges
 * - Lifecycle events with loopback for test verification
 * - Outbox schedulers enabled with fast polling for Wait/Fork/Retry tests
 */
class RabbitMQTestCaseProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration
            LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,
            // Messaging configuration
            LEMLINE_MESSAGING_TYPE to MessagingType.RABBITMQ.configValue,
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

            // Loopback configuration using shared exchanges
            // Commands: input queue binds to the same exchange that output publishes to
            "mp.messaging.incoming.commands-in.queue.name" to "lemline-commands",
            "mp.messaging.incoming.commands-in.exchange.name" to "lemline-commands-exchange",
            "mp.messaging.incoming.commands-in.exchange.type" to "fanout",
            "mp.messaging.outgoing.commands-out.exchange.name" to "lemline-commands-exchange",
            "mp.messaging.outgoing.commands-out.exchange.type" to "fanout",

            // Events: input queue binds to the same exchange that output publishes to
            "mp.messaging.incoming.events-in.queue.name" to "lemline-events",
            "mp.messaging.incoming.events-in.exchange.name" to "lemline-events-exchange",
            "mp.messaging.incoming.events-in.exchange.type" to "fanout",
            "mp.messaging.outgoing.events-out.exchange.name" to "lemline-events-exchange",
            "mp.messaging.outgoing.events-out.exchange.type" to "fanout",

            // Lifecycle events loopback - same exchange for producer and analytics consumer
            "mp.messaging.outgoing.lifecycleevents-out.exchange.name" to "lemline-lifecycle-exchange",
            "mp.messaging.outgoing.lifecycleevents-out.exchange.type" to "fanout",
            "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-rabbitmq",
            "mp.messaging.incoming.lifecycleevents-in.queue.name" to "lemline-lifecycle-test",
            "mp.messaging.incoming.lifecycleevents-in.exchange.name" to "lemline-lifecycle-exchange",
            "mp.messaging.incoming.lifecycleevents-in.exchange.type" to "fanout",

            // CloudEvents loopback - same exchange for in/out
            "mp.messaging.incoming.cloudevents-in.exchange.name" to "lemline-cloudevents-exchange",
            "mp.messaging.incoming.cloudevents-in.exchange.type" to "fanout",
            "mp.messaging.outgoing.cloudevents-out.exchange.name" to "lemline-cloudevents-exchange",
            "mp.messaging.outgoing.cloudevents-out.exchange.type" to "fanout",

            // Enable outbox schedulers for Wait/Fork/Retry tests
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
        )
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(RabbitMQTestResource::class.java),
            QuarkusTestProfile.TestResourceEntry(AnalyticsPostgresTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return setOf("rabbitmq-testcase")
    }
}
