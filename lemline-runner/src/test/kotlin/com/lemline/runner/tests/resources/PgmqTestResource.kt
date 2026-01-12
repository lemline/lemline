// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.DockerAvailability
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Test resource that starts a PostgreSQL container with PGMQ extension for messaging tests.
 *
 * Uses the official tembo-io/pgmq image which has PGMQ pre-installed.
 * Falls back to standard PostgreSQL if PGMQ image is unavailable.
 */
class PgmqTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var postgres: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        if (!DockerAvailability.isAvailable) {
            return emptyMap()
        }

        // Use PostgreSQL with PGMQ extension
        // The tembo-io/pgmq image has PGMQ pre-installed
        // Fallback to standard postgres if needed (tests requiring PGMQ will be skipped)
        postgres = try {
            PostgreSQLContainer(DockerImageName.parse("quay.io/tembo/pgmq-pg:latest"))
        } catch (e: Exception) {
            // Fallback to standard PostgreSQL for basic testing
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        }

        postgres
            .withDatabaseName("lemline_pgmq_test")
            .withUsername("test")
            .withPassword("test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 1))

        postgres.start()

        val host = postgres.host
        val port = postgres.getMappedPort(5432).toString()
        val database = postgres.databaseName
        val username = postgres.username
        val password = postgres.password

        // Return PGMQ connection properties
        val properties = mapOf(
            // PGMQ messaging connection settings
            "lemline.messaging.pgmq.host" to host,
            "lemline.messaging.pgmq.port" to port,
            "lemline.messaging.pgmq.database" to database,
            "lemline.messaging.pgmq.username" to username,
            "lemline.messaging.pgmq.password" to password,
        )

        // Set as system properties so that LemlineConfigSource can see them
        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        if (::postgres.isInitialized) {
            postgres.stop()
            postgres.close()
        }
    }
}
