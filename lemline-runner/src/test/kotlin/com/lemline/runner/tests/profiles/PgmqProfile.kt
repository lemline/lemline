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
 * - Business config (followed namespaces, enabled flags) uses lemline.* properties
 *   → LemlineConfigSource transforms these to mp.messaging.* properties
 * - Test-only infrastructure tweaks (lifecycle events test listener) use
 *   mp.messaging.* directly → these bypass LemlineConfigSource intentionally
 *
 * Destination model (per-namespace destinations `<prefix>-<namespace>-<type>`):
 * - The runner follows namespace `test-in` only → it consumes the pgmq queues
 *   `lemline-test-in-commands` / `lemline-test-in-events` (auto-created).
 * - The base suite's workflows use namespace `test`, so the runner's emissions are
 *   routed to the pgmq queues `lemline-test-commands` / `lemline-test-events`,
 *   which the runner does NOT consume — the test reads them directly.
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

            // Followed namespaces: consumption queues are derived from this list
            // (`lemline-test-in-commands` / `lemline-test-in-events`), while emissions
            // are routed per message by the workflow's namespace (`test`).
            com.lemline.runner.common.config.LEMLINE_NAMESPACES to "test-in",

            // Channel enabled flags
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",

            // Lifecycle events use a central shared queue (not per-namespace)
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
        )
    }

    /**
     * Widens the foreign-namespace guard scope to accept the base suite's `test` workflows
     * while consumption stays on `test-in` — see [TestNamespaceScope].
     */
    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(TestNamespaceScope::class.java)

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
