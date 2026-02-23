// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.DockerAvailability
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Test resource that starts a PostgreSQL container for PGMQ testing.
 *
 * Uses standard PostgreSQL since we use SQL-only PGMQ installation (no extension required).
 * The same PostgreSQL container is used for both:
 * - Main database (workflow state persistence) - Flyway runs all migrations
 * - PGMQ messaging - uses the pgmq schema created by Flyway migrations (V800, V801, V802)
 *
 * This approach lets Flyway automatically initialize the PGMQ schema as part of normal
 * database migration, just like it would in production.
 */
class PgmqTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var postgres: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        if (!DockerAvailability.isAvailable) {
            return emptyMap()
        }

        // Use standard PostgreSQL - we use SQL-only PGMQ installation (no extension required)
        postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))

        postgres
            .withDatabaseName("lemline_pgmq_test")
            .withUsername("test")
            .withPassword("test")
            .waitingFor(Wait.forListeningPort())

        postgres.start()

        val host = postgres.host
        val port = postgres.getMappedPort(5432).toString()
        val database = postgres.databaseName
        val username = postgres.username
        val password = postgres.password

        // Return both database and PGMQ connection properties
        // Using the same PostgreSQL container for both lets Flyway run PGMQ migrations
        val properties = mapOf(
            // PostgreSQL main database connection (Flyway runs migrations here)
            com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_HOST to host,
            com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PORT to port,
            com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_DATABASE to database,
            com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_USERNAME to username,
            com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PASSWORD to password,
            // PGMQ messaging connection (uses same PostgreSQL with pgmq schema)
            // LemlineConfigSource will translate these to pgmq.* global connector defaults
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_HOST to host,
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_PORT to port,
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_DATABASE to database,
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_USERNAME to username,
            com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_PASSWORD to password,
        )

        // Set as system properties so that LemlineConfigSource can see them
        properties.forEach { (k, v) -> System.setProperty(k, v) }

        // PGMQ is database-based and slower than dedicated brokers - increase test timeout
        System.setProperty("test.workflow.timeout.seconds", "10")

        // PGMQ with concurrent consumers can process CloudEvents out of order.
        // Increase delay between events to ensure each event completes processing
        // (including database lock acquisition) before the next one arrives.
        System.setProperty("test.workflow.cloudevent-send-interval-ms", "500")

        return properties
    }

    override fun stop() {
        // Clear system properties to prevent conflicts with other test profiles
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_HOST)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PORT)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_DATABASE)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_USERNAME)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PASSWORD)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_HOST)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_PORT)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_DATABASE)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_USERNAME)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_PASSWORD)
        System.clearProperty("test.workflow.timeout.seconds")
        System.clearProperty("test.workflow.cloudevent-send-interval-ms")

        if (::postgres.isInitialized) {
            postgres.stop()
            postgres.close()
        }
    }
}
