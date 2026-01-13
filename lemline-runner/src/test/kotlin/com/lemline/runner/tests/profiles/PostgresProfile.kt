// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.MESSAGING_TYPE
import com.lemline.runner.tests.resources.PostgresTestResource
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
            DATABASE_TYPE to DatabaseType.POSTGRESQL.configValue,
            // Messaging configuration
            MESSAGING_TYPE to MessagingType.IN_MEMORY.configValue,
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
        return setOf(DatabaseType.POSTGRESQL.configValue)
    }
}
