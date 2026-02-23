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
 * - Business config (topic names, enabled flags) uses lemline.* properties
 *   → LemlineConfigSource transforms these to mp.messaging.* properties
 * - Test-only infrastructure tweaks (auto-create topics) use mp.messaging.* directly
 *   → These bypass LemlineConfigSource intentionally (not production config)
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

            // Channel enabled flags
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",

            // Topic names for test isolation (transformed by LemlineConfigSource)
            // Note: Consumer and producer use different topics for testing
            // (production would typically use the same topic)
            com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_COMMANDS_TOPIC to "lemline-commands-in",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_COMMANDS_PRODUCER_TOPIC_OUT to "lemline-commands-out",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_EVENTS_TOPIC to "lemline-events-in",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_EVENTS_PRODUCER_TOPIC_OUT to "lemline-events-out",

            // =============================================================
            // Test-only infrastructure tweaks (bypass LemlineConfigSource)
            // =============================================================

            // Auto-create topics for testing (production uses pre-created topics)
            "kafka.allow.auto.create.topics" to "true",
        )
    }

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
