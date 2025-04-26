// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.tests.profiles

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for in-memory messaging testing.
 *
 * This profile configures:
 * 1. H2 in-memory database for persistence (handled by LemlineConfigSourceFactory)
 * 2. In-memory channels for messaging
 * 3. Disables Kafka and RabbitMQ connectors
 */
class H2InMemoryProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration (handled by LemlineConfigSourceFactory)
            "lemline.database.type" to "h2",

            // Messaging configuration
            "lemline.messaging.type" to "in-memory"
        )
    }
} 
