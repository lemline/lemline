// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.tests.profiles

import com.lemline.worker.tests.resources.PostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for PostgresSQL database testing.
 *
 * This profile configures:
 * - PostgresSQL database for persistence
 * - In-memory channels for messaging
 *
 * All corresponding Quarkus properties are set by LemlineConfigSourceFactory.
 */
class PostgresProfile : QuarkusTestProfile {

    /**
     * Overrides configuration properties for this profile.
     * Sets the database type to PostgreSQL.
     */
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration
            "lemline.database.type" to "postgresql",

            // Messaging configuration
            "lemline.messaging.type" to "in-memory"
        )
    }

    /**
     * Defines which test resources are active for this profile.
     * We need the PostgresTestResource to start the container.
     */
    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(QuarkusTestProfile.TestResourceEntry(PostgresTestResource::class.java))
    }

    /**
     * Specifies tags for this profile (optional).
     */
    override fun tags(): Set<String> {
        return setOf("postgres", "integration")
    }
}
