// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.pgmq

import com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_MIGRATE_AT_START
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
import com.lemline.runner.tests.resources.PgmqTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for PGMQ messaging with loopback configuration.
 *
 * Uses the SAME queue for commands-in and commands-out (and events-in/out)
 * to create a natural message loop for end-to-end workflow testing.
 *
 * This profile configures:
 * - PostgreSQL database for persistence (shared with PGMQ messaging)
 * - PGMQ channels with loopback queues (same queue name for in/out)
 * - Lifecycle events with loopback for test verification
 * - Outbox schedulers enabled with fast polling for Wait/Fork/Retry tests
 */
class PgmqTestCaseProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration is provided by PgmqTestResource via lemline.database.postgresql.*
            // Messaging configuration
            LEMLINE_MESSAGING_TYPE to MessagingType.PGMQ.configValue,
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

            // Use SAME queue for in/out to create loopback
            "mp.messaging.incoming.commands-in.queue" to "lemline-commands",
            "mp.messaging.incoming.events-in.queue" to "lemline-events",
            "mp.messaging.outgoing.commands-out.queue" to "lemline-commands",
            "mp.messaging.outgoing.events-out.queue" to "lemline-events",

            // Lifecycle events loopback - same queue for producer and test listener
            "mp.messaging.outgoing.lifecycleevents-out.connector" to "smallrye-pgmq",
            "mp.messaging.outgoing.lifecycleevents-out.queue" to "lemline-lifecycle",
            "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-pgmq",
            "mp.messaging.incoming.lifecycleevents-in.queue" to "lemline-lifecycle",

            // CloudEvents loopback - same queue for in/out
            "mp.messaging.incoming.cloudevents-in.queue" to "lemline-cloudevents",
            "mp.messaging.outgoing.cloudevents-out.queue" to "lemline-cloudevents",

            // PGMQ-specific settings for commands
            "mp.messaging.incoming.commands-in.visibility-timeout" to "30",
            "mp.messaging.incoming.commands-in.poll-interval" to "100",
            "mp.messaging.incoming.commands-in.batch-size" to "10",

            // PGMQ-specific settings for events
            "mp.messaging.incoming.events-in.visibility-timeout" to "30",
            "mp.messaging.incoming.events-in.poll-interval" to "100",
            "mp.messaging.incoming.events-in.batch-size" to "10",

            // PGMQ-specific settings for CloudEvents
            "mp.messaging.incoming.cloudevents-in.visibility-timeout" to "30",
            "mp.messaging.incoming.cloudevents-in.poll-interval" to "100",
            "mp.messaging.incoming.cloudevents-in.batch-size" to "10",

            // PGMQ-specific settings for lifecycle events (test listener)
            "mp.messaging.incoming.lifecycleevents-in.visibility-timeout" to "30",
            "mp.messaging.incoming.lifecycleevents-in.poll-interval" to "100",
            "mp.messaging.incoming.lifecycleevents-in.batch-size" to "10",
            "mp.messaging.incoming.lifecycleevents-in.auto-create-queue" to "true",
            "mp.messaging.outgoing.lifecycleevents-out.auto-create-queue" to "true",

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
        ) + AnalyticsH2TestProfileSupport.overrides("lemline_analytics_pgmq_testcase")
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(PgmqTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return setOf("pgmq-testcase")
    }
}
