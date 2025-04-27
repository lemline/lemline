// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

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

        // Properties passed here are picked up by LemlineConfigSourceFactory
        return mapOf(
            "lemline.database.postgresql.host" to postgres.host,
            "lemline.database.postgresql.port" to postgres.firstMappedPort.toString(),
            "lemline.database.postgresql.name" to postgres.databaseName,
            "lemline.database.postgresql.username" to postgres.username,
            "lemline.database.postgresql.password" to postgres.password
        )
    }

    override fun stop() {
        if (::postgres.isInitialized) {
            postgres.stop()
        }
    }
}
