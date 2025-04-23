// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.tests.profiles

import com.lemline.worker.tests.resources.MySQLTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Quarkus Test Profile to configure the application for PostgreSQL database tests.
 */
class MySQLProfile : QuarkusTestProfile {

    /**
     * Overrides configuration properties for this profile.
     * Sets the database type to PostgresSQL.
     */
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf("lemline.database.type" to "mysql")
    }

    /**
     * Defines which test resources are active for this profile.
     * We need the PostgresTestResource to start the container.
     */
    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(QuarkusTestProfile.TestResourceEntry(MySQLTestResource::class.java))
    }

    /**
     * Specifies tags for this profile (optional).
     */
    override fun tags(): Set<String> {
        return setOf("mysql", "integration")
    }
}
