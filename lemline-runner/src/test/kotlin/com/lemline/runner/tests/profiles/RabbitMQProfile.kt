// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.resources.RabbitMQTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Test profile for RabbitMQ messaging testing.
 *
 * This profile configures:
 * - an H2 (in memory) database for persistence
 * - RabbitMQ channels for messaging
 *
 * Configuration approach:
 * - Business config (followed namespaces, enabled flags) uses lemline.* properties
 *   → LemlineConfigSource transforms these to mp.messaging.* properties
 *
 * Destination model (per-namespace routing on the channel topic exchanges):
 * - The runner follows namespace `test-in` only → it consumes the deterministic pool
 *   queues `lemline-commands.test-in` / `lemline-events.test-in`.
 * - Producers publish to the channel topic exchanges `lemline-commands` /
 *   `lemline-events` with per-message routing key = the workflow's namespace.
 * - The base suite's workflows use namespace `test`, so emissions carry routing key
 *   `test` and are NOT routed to the runner's pool queues — the test binds its own
 *   observation queues with routing key `test` to observe them.
 */
class RabbitMQProfile : QuarkusTestProfile {

    /**
     * Overrides configuration properties for this profile.
     * Sets the database type to H2.
     */
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // =============================================================
            // Business configuration (via lemline.* → LemlineConfigSource)
            // =============================================================

            // Database configuration
            com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,

            // Messaging type
            com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to MessagingType.RABBITMQ.configValue,

            // Followed namespaces: the pool queue names are derived from this list
            // (`lemline-commands.test-in` / `lemline-events.test-in`), while emissions
            // are routed per message by the workflow's namespace (`test`).
            com.lemline.runner.common.config.LEMLINE_NAMESPACES to "test-in",

            // Channel enabled flags
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",
        )
    }

    /**
     * Widens the foreign-namespace guard scope to accept the base suite's `test` workflows
     * while consumption stays on `test-in` — see [TestNamespaceScope].
     */
    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(TestNamespaceScope::class.java)

    /**
     * Defines which test resources are active for this profile (optional).
     * H2 is configured directly via properties, no external resource needed.
     */
    override fun testResources(): List<TestResourceEntry> {
        return listOf(TestResourceEntry(RabbitMQTestResource::class.java))
    }

    /**
     * Specifies tags for this profile (optional).
     */
    override fun tags(): Set<String> {
        return setOf(MessagingType.RABBITMQ.configValue)
    }
}
