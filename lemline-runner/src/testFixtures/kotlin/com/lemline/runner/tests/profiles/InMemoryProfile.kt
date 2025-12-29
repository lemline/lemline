// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.tests.profiles

import com.lemline.runner.config.CLOUDEVENTS_CONSUMER_ENABLED
import kotlin.time.ExperimentalTime
import com.lemline.runner.config.CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.config.COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import com.lemline.runner.config.MESSAGING_TYPE
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for in memory testing.
 *
 * This profile configures:
 * - an H2 (in memory) database for persistence
 * - In-memory channels for messaging
 * - Lifecycle events with loopback for test verification
 *
 * All corresponding Quarkus properties are set by LemlineConfigSourceFactory.
 */
class InMemoryProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration
            DATABASE_TYPE to DB_TYPE_IN_MEMORY,
            // Messaging configuration
            MESSAGING_TYPE to MSG_TYPE_IN_MEMORY,
            COMMANDS_CONSUMER_ENABLED to "true",
            COMMANDS_PRODUCER_ENABLED to "true",
            EVENTS_CONSUMER_ENABLED to "true",
            EVENTS_PRODUCER_ENABLED to "true",
            CLOUDEVENTS_CONSUMER_ENABLED to "true",
            CLOUDEVENTS_PRODUCER_ENABLED to "true",

            // Enable lifecycle events producer so events flow through the broker
            LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
            // Configure lifecycleevents-in channel for test listener (loopback)
            "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-in-memory",

            // Enable outbox schedulers for tests that need them (Listen, Wait, Retry, etc.)
            "lemline.outbox.enabled" to "true",
            // Fast polling for tests (10s initial delay to allow migrations to complete)
            "lemline.outbox.wait.outbox.every" to "1s",
            "lemline.outbox.wait.outbox.initial-delay" to "10s",
            "lemline.outbox.retry.outbox.every" to "1s",
            "lemline.outbox.retry.outbox.initial-delay" to "10s",
            "lemline.outbox.schedule.outbox.every" to "1s",
            "lemline.outbox.schedule.outbox.initial-delay" to "10s",
            // Listener outbox config (for listen task tests, includes foreach processing)
            "lemline.outbox.listener.outbox.every" to "1s",
            "lemline.outbox.listener.outbox.initial-delay" to "10s"
        )
    }

    /**
     * Specifies tags for this profile (optional).
     */
    override fun tags(): Set<String> {
        return setOf(DB_TYPE_IN_MEMORY)
    }
}
