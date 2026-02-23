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
            "lemline.database.type" to DatabaseType.H2.configValue,

            // Messaging type
            "lemline.messaging.type" to MessagingType.KAFKA.configValue,

            // Channel enabled flags
            "lemline.messaging.commands.consumer.enabled" to "true",
            "lemline.messaging.commands.producer.enabled" to "true",
            "lemline.messaging.events.consumer.enabled" to "true",
            "lemline.messaging.events.producer.enabled" to "true",

            // Topic names for test isolation (transformed by LemlineConfigSource)
            // Note: Consumer and producer use different topics for testing
            // (production would typically use the same topic)
            "lemline.messaging.kafka.commands.topic" to "lemline-commands-in",
            "lemline.messaging.kafka.commands.producer.topic-out" to "lemline-commands-out",
            "lemline.messaging.kafka.events.topic" to "lemline-events-in",
            "lemline.messaging.kafka.events.producer.topic-out" to "lemline-events-out",

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
