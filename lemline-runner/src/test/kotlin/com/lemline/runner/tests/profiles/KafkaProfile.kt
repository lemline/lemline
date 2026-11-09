// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.resources.KafkaTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Test profile for Kafka messaging testing.
 *
 * This profile configures:
 * - an H2 (in memory) database for persistence
 * - Kafka channels for messaging
 *
 * Configuration approach:
 * - Business config (followed namespaces, enabled flags) uses lemline.* properties
 *   → LemlineConfigSource transforms these to mp.messaging.* properties
 * - Test-only infrastructure tweaks (auto-create topics) use mp.messaging.* directly
 *   → These bypass LemlineConfigSource intentionally (not production config)
 *
 * Destination model (per-namespace destinations `<prefix>-<namespace>-<type>`):
 * - The runner follows namespace `test-in` only → it consumes
 *   `lemline-test-in-commands` / `lemline-test-in-events`.
 * - The base suite's workflows use namespace `test`, so the runner's emissions are
 *   routed to `lemline-test-commands` / `lemline-test-events`, which the runner does
 *   NOT consume — the test observes them with a raw Kafka consumer.
 */
class KafkaProfile : QuarkusTestProfile {

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
            com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to MessagingType.KAFKA.configValue,

            // Followed namespaces: consumption topics are derived from this list
            // (`lemline-test-in-commands` / `lemline-test-in-events`), while emissions
            // are routed per message by the workflow's namespace (`test`).
            com.lemline.runner.common.config.LEMLINE_NAMESPACES to "test-in",

            // Channel enabled flags
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",

            // =============================================================
            // Test-only infrastructure tweaks (bypass LemlineConfigSource)
            // =============================================================

            // Auto-create topics for testing (production uses pre-created topics)
            "kafka.allow.auto.create.topics" to "true",
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
        return listOf(TestResourceEntry(KafkaTestResource::class.java))
    }

    /**
     * Specifies tags for this profile (optional).
     */
    override fun tags(): Set<String> {
        return setOf(MessagingType.KAFKA.configValue)
    }
}
