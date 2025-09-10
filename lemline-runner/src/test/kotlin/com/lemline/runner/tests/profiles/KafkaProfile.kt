// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.config.DATABASE_CONSUMER_ENABLED
import com.lemline.runner.config.DATABASE_PRODUCER_ENABLED
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_KAFKA
import com.lemline.runner.config.MESSAGING_TYPE
import com.lemline.runner.config.WORKFLOWS_CONSUMER_ENABLED
import com.lemline.runner.config.WORKFLOWS_PRODUCER_ENABLED
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
            DATABASE_TYPE to DB_TYPE_IN_MEMORY,
            // Messaging configuration
            MESSAGING_TYPE to MSG_TYPE_KAFKA,
            WORKFLOWS_CONSUMER_ENABLED to "true",
            WORKFLOWS_PRODUCER_ENABLED to "true",
            DATABASE_CONSUMER_ENABLED to "true",
            DATABASE_PRODUCER_ENABLED to "true",

            "mp.messaging.incoming.workflows-in.topic" to "lemline-workflows-in",
            "mp.messaging.outgoing.workflows-out.topic" to "lemline-workflows-out",
            "mp.messaging.incoming.ingestion-in.topic" to "lemline-ingestion-in",
            "mp.messaging.outgoing.ingestion-out.topic" to "lemline-ingestion-out",

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
        return setOf(MSG_TYPE_KAFKA)
    }
}
