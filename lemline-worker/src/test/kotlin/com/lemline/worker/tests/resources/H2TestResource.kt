// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.tests.resources

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Test resource for H2 in-memory database.
 * This configures Quarkus to use an H2 database for tests.
 */
class H2TestResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        // Set system properties for H2 datasource config - Quarkus will pick these up
        System.setProperty("quarkus.datasource.db-kind", "h2")
        // Use mem:testdb for an in-memory database named 'testdb'
        // DB_CLOSE_DELAY=-1 keeps the database alive until the JVM exits
        System.setProperty("quarkus.datasource.jdbc.url", "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1")
        System.setProperty("quarkus.datasource.username", "sa") // Default H2 user
        System.setProperty("quarkus.datasource.password", "")   // Default H2 password is empty

        // Return profile and custom property
        return mapOf(
            "quarkus.profile" to "h2",
            "lemline.database.type" to "h2"
            // No need to set flyway locations for H2 with drop-and-create
        )
    }

    override fun stop() {
        // Clean up system properties
        System.clearProperty("quarkus.datasource.db-kind")
        System.clearProperty("quarkus.datasource.jdbc.url")
        System.clearProperty("quarkus.datasource.username")
        System.clearProperty("quarkus.datasource.password")
    }
}
