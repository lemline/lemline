// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.resources.PgmqTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Test profile for PGMQ (PostgreSQL Message Queue) messaging testing.
 *
 * This profile configures:
 * - PostgreSQL database for persistence (shared with PGMQ messaging)
 * - PGMQ channels for messaging (using same PostgreSQL as message broker)
 *
 * By using PostgreSQL for both the main database and PGMQ messaging,
 * Flyway automatically runs all migrations including the PGMQ schema (V800, V801, V802).
 *
 * Configuration approach:
 * - Business config (queue names, enabled flags) uses lemline.* properties
 *   → LemlineConfigSource transforms these to mp.messaging.* properties
 * - Test-only infrastructure tweaks (auto-create-queue) use mp.messaging.* directly
 *   → These bypass LemlineConfigSource intentionally (not production config)
 */
class PgmqProfile : QuarkusTestProfile {

    /**
     * Overrides configuration properties for this profile.
     * Uses PostgreSQL for both database and messaging (shared container).
     */
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // =============================================================
            // Business configuration (via lemline.* → LemlineConfigSource)
            // =============================================================

            // Database configuration is provided by PgmqTestResource via lemline.database.postgresql.*

            // Messaging type
            com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to MessagingType.PGMQ.configValue,

            // Channel enabled flags
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",

            // Queue names for test isolation (transformed by LemlineConfigSource)
            // Note: Consumer and producer use different queues for testing
            // (production would typically use the same queue)
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_COMMANDS_QUEUE to "lemline-commands-in",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_COMMANDS_PRODUCER_QUEUE_OUT to "lemline-commands-out",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_EVENTS_QUEUE to "lemline-events-in",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_EVENTS_PRODUCER_QUEUE_OUT to "lemline-events-out",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_QUEUE to "lemline-cloudevents-in",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_PRODUCER_QUEUE_OUT to "lemline-cloudevents-out",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_QUEUE to "lemline-lifecycleevents",

            // =============================================================
            // Test-only infrastructure tweaks (bypass LemlineConfigSource)
            // =============================================================

            // Lifecycle events test listener (not a production channel)
            "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-pgmq",
            "mp.messaging.incoming.lifecycleevents-in.queue" to "lemline-lifecycleevents",
            "mp.messaging.incoming.lifecycleevents-in.visibility-timeout" to "30",
            "mp.messaging.incoming.lifecycleevents-in.poll-interval" to "100",
            "mp.messaging.incoming.lifecycleevents-in.batch-size" to "10",
            "mp.messaging.incoming.lifecycleevents-in.auto-create-queue" to "true",

            // Auto-create queues for testing (production uses pre-created queues)
            "mp.messaging.incoming.commands-in.auto-create-queue" to "true",
            "mp.messaging.outgoing.commands-out.auto-create-queue" to "true",
            "mp.messaging.incoming.events-in.auto-create-queue" to "true",
            "mp.messaging.outgoing.events-out.auto-create-queue" to "true",
            "mp.messaging.incoming.cloudevents-in.auto-create-queue" to "true",
            "mp.messaging.outgoing.cloudevents-out.auto-create-queue" to "true",
            "mp.messaging.outgoing.lifecycleevents-out.auto-create-queue" to "true",
        )
    }

    /**
     * Defines which test resources are active for this profile.
     * Starts a PostgreSQL container used for both database and PGMQ messaging.
     */
    override fun testResources(): List<TestResourceEntry> {
        return listOf(TestResourceEntry(PgmqTestResource::class.java))
    }

    /**
     * Specifies tags for this profile.
     */
    override fun tags(): Set<String> {
        return setOf(MessagingType.PGMQ.configValue)
    }
}
