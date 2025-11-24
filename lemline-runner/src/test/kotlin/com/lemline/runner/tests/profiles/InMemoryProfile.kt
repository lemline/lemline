// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.config.COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
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
            EVENTS_PRODUCER_ENABLED to "true"
        )
    }

    /**
     * Specifies tags for this profile (optional).
     */
    override fun tags(): Set<String> {
        return setOf(DB_TYPE_IN_MEMORY)
    }
}
