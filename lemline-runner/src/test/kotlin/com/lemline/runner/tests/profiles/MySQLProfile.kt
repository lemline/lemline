// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.config.LEMLINE_DATABASE_TYPE
import com.lemline.runner.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import com.lemline.runner.tests.resources.MySQLTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for MySQL database testing.
 *
 * This profile configures:
 * - MySQL database for persistence
 * - In-memory channels for messaging
 *
 * All corresponding Quarkus properties are set by LemlineConfigSourceFactory.
 */
class MySQLProfile : QuarkusTestProfile {

    /**
     * Overrides configuration properties for this profile.
     * Sets the database type to PostgresSQL.
     */
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration
            LEMLINE_DATABASE_TYPE to DB_TYPE_MYSQL,

            // Messaging configuration
            LEMLINE_MESSAGING_TYPE to MSG_TYPE_IN_MEMORY
        )
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
        return setOf(DB_TYPE_MYSQL)
    }
}
