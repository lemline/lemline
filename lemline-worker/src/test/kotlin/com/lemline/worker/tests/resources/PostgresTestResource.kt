// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.tests.resources

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Test resource for PostgreSQL database.
 * This spins up a PostgreSQL container for tests.
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var postgres: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer(DockerImageName.parse("postgres:14-alpine"))
            .withDatabaseName("swruntime_test")
            .withUsername("test")
            .withPassword("test")

        postgres.start()

        // Set system properties for datasource config - Quarkus will pick these up
        System.setProperty("quarkus.datasource.db-kind", "postgresql")
        System.setProperty("quarkus.datasource.jdbc.url", postgres.jdbcUrl)
        System.setProperty("quarkus.datasource.username", postgres.username)
        System.setProperty("quarkus.datasource.password", postgres.password)
        // Do NOT set flyway locations dynamically

        // Only return the profile setting
        return mapOf(
            "quarkus.profile" to "postgresql",
            "lemline.database.type" to "postgresql"
        )
    }

    override fun stop() {
        if (::postgres.isInitialized) {
            postgres.stop()
            // Clean up system properties
            System.clearProperty("quarkus.datasource.db-kind")
            System.clearProperty("quarkus.datasource.jdbc.url")
            System.clearProperty("quarkus.datasource.username")
            System.clearProperty("quarkus.datasource.password")
        }
    }
}
