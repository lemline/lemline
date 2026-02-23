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
            com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,

            // Messaging type
            com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to MessagingType.RABBITMQ.configValue,

            // Channel enabled flags
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",

            // Queue names for test isolation (transformed by LemlineConfigSource)
            // Note: Consumer and producer use different queues for testing
            // (production would typically use the same queue)
            com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_COMMANDS_QUEUE to "lemline-commands-in",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_COMMANDS_PRODUCER_QUEUE_OUT to "lemline-commands-out",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_EVENTS_QUEUE to "lemline-events-in",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_EVENTS_PRODUCER_QUEUE_OUT to "lemline-events-out",
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
