// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.config.COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_PGMQ
import com.lemline.runner.config.MESSAGING_TYPE
import com.lemline.runner.tests.resources.PgmqTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Test profile for PGMQ (PostgreSQL Message Queue) messaging testing.
 *
 * This profile configures:
 * - an H2 (in memory) database for persistence
 * - PGMQ channels for messaging (using PostgreSQL as message broker)
 *
 * All corresponding Quarkus properties are set by LemlineConfigSourceFactory.
 */
class PgmqProfile : QuarkusTestProfile {

    /**
     * Overrides configuration properties for this profile.
     * Sets the database type to H2 and messaging type to PGMQ.
     */
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration (use H2 for workflow state persistence)
            DATABASE_TYPE to DB_TYPE_IN_MEMORY,
            // Messaging configuration (use PGMQ for message broker)
            MESSAGING_TYPE to MSG_TYPE_PGMQ,
            COMMANDS_CONSUMER_ENABLED to "true",
            COMMANDS_PRODUCER_ENABLED to "true",
            EVENTS_CONSUMER_ENABLED to "true",
            EVENTS_PRODUCER_ENABLED to "true",

            // PGMQ queue names for test isolation
            "mp.messaging.incoming.commands-in.queue" to "lemline-commands-in",
            "mp.messaging.outgoing.commands-out.queue" to "lemline-commands-out",
            "mp.messaging.incoming.events-in.queue" to "lemline-events-in",
            "mp.messaging.outgoing.events-out.queue" to "lemline-events-out",

            // PGMQ-specific settings for testing
            "mp.messaging.incoming.commands-in.visibility-timeout" to "30",
            "mp.messaging.incoming.commands-in.poll-interval" to "100",
            "mp.messaging.incoming.commands-in.batch-size" to "10",
            "mp.messaging.incoming.events-in.visibility-timeout" to "30",
            "mp.messaging.incoming.events-in.poll-interval" to "100",
            "mp.messaging.incoming.events-in.batch-size" to "10",
        )
    }

    /**
     * Defines which test resources are active for this profile.
     * Starts a PostgreSQL container with PGMQ extension.
     */
    override fun testResources(): List<TestResourceEntry> {
        return listOf(TestResourceEntry(PgmqTestResource::class.java))
    }

    /**
     * Specifies tags for this profile.
     */
    override fun tags(): Set<String> {
        return setOf(MSG_TYPE_PGMQ)
    }
}
