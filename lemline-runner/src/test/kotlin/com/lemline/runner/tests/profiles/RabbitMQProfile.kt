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
 * - Business config (queue names, enabled flags) uses lemline.* properties
 *   → LemlineConfigSource transforms these to mp.messaging.* properties
 * - Test-only infrastructure tweaks use mp.messaging.* directly
 *   → These bypass LemlineConfigSource intentionally (not production config)
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
            "lemline.database.type" to DatabaseType.H2.configValue,

            // Messaging type
            "lemline.messaging.type" to MessagingType.RABBITMQ.configValue,

            // Channel enabled flags
            "lemline.messaging.commands.consumer.enabled" to "true",
            "lemline.messaging.commands.producer.enabled" to "true",
            "lemline.messaging.events.consumer.enabled" to "true",
            "lemline.messaging.events.producer.enabled" to "true",

            // Queue names for test isolation (transformed by LemlineConfigSource)
            // Note: Consumer and producer use different queues for testing
            // (production would typically use the same queue)
            "lemline.messaging.rabbitmq.commands.queue" to "lemline-commands-in",
            "lemline.messaging.rabbitmq.commands.producer.queue-out" to "lemline-commands-out",
            "lemline.messaging.rabbitmq.events.queue" to "lemline-events-in",
            "lemline.messaging.rabbitmq.events.producer.queue-out" to "lemline-events-out",
        )
    }

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
