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
import com.lemline.runner.common.config.LEMLINE_NAMESPACES
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
import com.lemline.runner.tests.resources.RabbitMQTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for RabbitMQ messaging with per-namespace destinations.
 *
 * The runner follows every namespace used by the testcase workflow fixtures
 * (`default`, `examples`, `samples`, `test`): producers publish to the channel
 * exchange `lemline-<type>` with routing key = namespace, and
 * RabbitTopologyBootstrap binds the pool queue `lemline-<type>.<sorted-ns>` to
 * the per-namespace exchanges — loopback (in = out per namespace) is inherent,
 * no raw mp.messaging destination overrides are needed.
 *
 * This profile configures:
 * - H2 (in-memory) database for persistence
 * - RabbitMQ channels with per-namespace topology (natural loopback)
 * - Lifecycle events with loopback for test verification (single shared exchange)
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

            // Follow every namespace used by the testcase workflow fixtures so
            // RabbitTopologyBootstrap binds the pool queues to all of them
            // (natural loopback)
            LEMLINE_NAMESPACES to "default,examples,samples,test",

            // Lifecycle events loopback - same exchange for producer and analytics consumer
            "mp.messaging.outgoing.lifecycleevents-out.exchange.name" to "lemline-lifecycle-exchange",
            "mp.messaging.outgoing.lifecycleevents-out.exchange.type" to "fanout",
            "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-rabbitmq",
            "mp.messaging.incoming.lifecycleevents-in.queue.name" to "lemline-lifecycle-test",
            "mp.messaging.incoming.lifecycleevents-in.exchange.name" to "lemline-lifecycle-exchange",
            "mp.messaging.incoming.lifecycleevents-in.exchange.type" to "fanout",

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
        ) + AnalyticsH2TestProfileSupport.overrides("lemline_analytics_rabbitmq_testcase")
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(RabbitMQTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return setOf("rabbitmq-testcase")
    }
}
