// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.config.CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.config.COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_PGMQ
import com.lemline.runner.config.MESSAGING_TYPE
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
 * All corresponding Quarkus properties are set by LemlineConfigSourceFactory.
 */
class PgmqProfile : QuarkusTestProfile {

    /**
     * Overrides configuration properties for this profile.
     * Uses PostgreSQL for both database and messaging (shared container).
     */
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration is provided by PgmqTestResource via lemline.database.postgresql.*
            // Messaging configuration (use PGMQ for message broker)
            MESSAGING_TYPE to MSG_TYPE_PGMQ,
            // Commands and events channels
            COMMANDS_CONSUMER_ENABLED to "true",
            COMMANDS_PRODUCER_ENABLED to "true",
            EVENTS_CONSUMER_ENABLED to "true",
            EVENTS_PRODUCER_ENABLED to "true",
            // CloudEvents channels (for emit/listen tasks)
            CLOUDEVENTS_CONSUMER_ENABLED to "true",
            CLOUDEVENTS_PRODUCER_ENABLED to "true",
            // Lifecycle events channel (for observability)
            LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",

            // PGMQ queue names for test isolation - commands and events
            "mp.messaging.incoming.commands-in.queue" to "lemline-commands-in",
            "mp.messaging.outgoing.commands-out.queue" to "lemline-commands-out",
            "mp.messaging.incoming.events-in.queue" to "lemline-events-in",
            "mp.messaging.outgoing.events-out.queue" to "lemline-events-out",

            // PGMQ queue names - CloudEvents
            "mp.messaging.incoming.cloudevents-in.queue" to "lemline-cloudevents-in",
            "mp.messaging.outgoing.cloudevents-out.queue" to "lemline-cloudevents-out",

            // PGMQ queue names - Lifecycle events (producer sends to queue, test listener consumes from same queue)
            "mp.messaging.outgoing.lifecycleevents-out.queue" to "lemline-lifecycleevents",
            "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-pgmq",
            "mp.messaging.incoming.lifecycleevents-in.queue" to "lemline-lifecycleevents",

            // PGMQ-specific settings for testing - commands
            "mp.messaging.incoming.commands-in.visibility-timeout" to "30",
            "mp.messaging.incoming.commands-in.poll-interval" to "100",
            "mp.messaging.incoming.commands-in.batch-size" to "10",
            // PGMQ-specific settings for testing - events
            "mp.messaging.incoming.events-in.visibility-timeout" to "30",
            "mp.messaging.incoming.events-in.poll-interval" to "100",
            "mp.messaging.incoming.events-in.batch-size" to "10",
            // PGMQ-specific settings for testing - CloudEvents
            "mp.messaging.incoming.cloudevents-in.visibility-timeout" to "30",
            "mp.messaging.incoming.cloudevents-in.poll-interval" to "100",
            "mp.messaging.incoming.cloudevents-in.batch-size" to "10",
            // PGMQ-specific settings for testing - Lifecycle events (test listener)
            "mp.messaging.incoming.lifecycleevents-in.visibility-timeout" to "30",
            "mp.messaging.incoming.lifecycleevents-in.poll-interval" to "100",
            "mp.messaging.incoming.lifecycleevents-in.batch-size" to "10",
        )
    }

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
        return setOf(MSG_TYPE_PGMQ)
    }
}
