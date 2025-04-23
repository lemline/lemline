// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.tests.profiles

import com.lemline.worker.tests.resources.PostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Quarkus Test Profile to configure the application for PostgreSQL database tests.
 */
class PostgresProfile : QuarkusTestProfile {

    /**
     * Overrides configuration properties for this profile.
     * Sets the database type to PostgreSQL.
     */
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf("lemline.database.type" to "postgresql")
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
