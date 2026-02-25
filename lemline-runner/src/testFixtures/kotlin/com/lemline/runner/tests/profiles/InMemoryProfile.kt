// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.common.config.LEMLINE_OUTBOX_ENABLED
import com.lemline.runner.common.config.LEMLINE_OUTBOX_LISTENER_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_LISTENER_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.LEMLINE_OUTBOX_RETRY_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_RETRY_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.LEMLINE_OUTBOX_SCHEDULE_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_SCHEDULE_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.LEMLINE_OUTBOX_WAIT_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_WAIT_OUTBOX_INITIAL_JITTER
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
            LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,
            // Messaging configuration
            LEMLINE_MESSAGING_TYPE to MessagingType.IN_MEMORY.configValue,
            LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
            // Keep lifecycleevents-in bound for loopback lifecycle analytics ingestion in tests.
            "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-in-memory",

            // Enable outbox schedulers for tests that need them (Listen, Wait, Retry, etc.)
            LEMLINE_OUTBOX_ENABLED to "true",
            // Fast polling for tests (no jitter - start immediately for deterministic testing)
            LEMLINE_OUTBOX_WAIT_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_WAIT_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_RETRY_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_RETRY_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_SCHEDULE_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_SCHEDULE_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_LISTENER_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_LISTENER_OUTBOX_INITIAL_JITTER to "0s"
        )
    }

    /**
     * Specifies tags for this profile (optional).
     */
    override fun tags(): Set<String> {
        return setOf(DatabaseType.H2.configValue)
    }
}
