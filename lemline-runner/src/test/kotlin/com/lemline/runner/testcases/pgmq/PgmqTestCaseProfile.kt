// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.pgmq

import com.lemline.runner.common.config.MessagingType
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
            "lemline.messaging.type" to MessagingType.PGMQ.configValue,
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

            // Use SAME queue for in/out to create loopback
            "mp.messaging.incoming.commands-in.queue" to "lemline-commands",
            "mp.messaging.outgoing.commands-out.queue" to "lemline-commands",
            "mp.messaging.incoming.events-in.queue" to "lemline-events",
            "mp.messaging.outgoing.events-out.queue" to "lemline-events",

            // Lifecycle events loopback - same queue for producer and test listener
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
        return listOf(QuarkusTestProfile.TestResourceEntry(PgmqTestResource::class.java))
    }

    override fun tags(): Set<String> {
        return setOf("pgmq-testcase")
    }
}
