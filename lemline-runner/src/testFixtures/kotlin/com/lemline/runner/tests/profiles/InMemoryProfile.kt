// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
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
            "lemline.database.type" to DatabaseType.H2.configValue,
            // Messaging configuration
            "lemline.messaging.type" to MessagingType.IN_MEMORY.configValue,
            "lemline.messaging.commands.consumer.enabled" to "true",
            "lemline.messaging.commands.producer.enabled" to "true",
            "lemline.messaging.events.consumer.enabled" to "true",
            "lemline.messaging.events.producer.enabled" to "true",
            "lemline.messaging.cloudevents.consumer.enabled" to "true",
            "lemline.messaging.cloudevents.producer.enabled" to "true",

            // Enable lifecycle events producer so events flow through the broker
            "lemline.messaging.lifecycleevents.producer.enabled" to "true",
            // Configure lifecycleevents-in channel for test listener (loopback)
            "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-in-memory",

            // Enable outbox schedulers for tests that need them (Listen, Wait, Retry, etc.)
            "lemline.outbox.enabled" to "true",
            // Fast polling for tests (no jitter - start immediately for deterministic testing)
            "lemline.outbox.wait.outbox.every" to "1s",
            "lemline.outbox.wait.outbox.initial-jitter" to "0s",
            "lemline.outbox.retry.outbox.every" to "1s",
            "lemline.outbox.retry.outbox.initial-jitter" to "0s",
            "lemline.outbox.schedule.outbox.every" to "1s",
            "lemline.outbox.schedule.outbox.initial-jitter" to "0s",
            // Listener outbox config (for listen task tests, includes foreach processing)
            "lemline.outbox.listener.outbox.every" to "1s",
            "lemline.outbox.listener.outbox.initial-jitter" to "0s"
        )
    }

    /**
     * Specifies tags for this profile (optional).
     */
    override fun tags(): Set<String> {
        return setOf(DatabaseType.H2.configValue)
    }
}
