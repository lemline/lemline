// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.config.COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.MESSAGING_TYPE
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
 *  All corresponding Quarkus properties are set by LemlineConfigSourceFactory.
 */
class KafkaProfile : QuarkusTestProfile {

    /**
     * Overrides configuration properties for this profile.
     * Sets the database type to H2.
     */
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration
            DATABASE_TYPE to DatabaseType.H2.configValue,
            // Messaging configuration
            MESSAGING_TYPE to MessagingType.KAFKA.configValue,
            COMMANDS_CONSUMER_ENABLED to "true",
            COMMANDS_PRODUCER_ENABLED to "true",
            EVENTS_CONSUMER_ENABLED to "true",
            EVENTS_PRODUCER_ENABLED to "true",

            "mp.messaging.incoming.commands-in.topic" to "lemline-commands-in",
            "mp.messaging.outgoing.commands-out.topic" to "lemline-commands-out",
            "mp.messaging.incoming.events-in.topic" to "lemline-events-in",
            "mp.messaging.outgoing.events-out.topic" to "lemline-events-out",

            "smallrye.messaging.kafka.topic.creation.enable" to "true"
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
