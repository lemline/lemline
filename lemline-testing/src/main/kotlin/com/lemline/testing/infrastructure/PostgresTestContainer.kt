// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing.infrastructure

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * PostgreSQL container wrapper for E2E testing.
 *
 * Provides a pre-configured PostgreSQL instance using Testcontainers.
 */
class PostgresTestContainer : AutoCloseable {

    private val container = PostgreSQLContainer(
        DockerImageName.parse("postgres:16-alpine")
    ).apply {
        withDatabaseName("lemline_test")
        withUsername("lemline")
        withPassword("lemline")
    }

    /**
     * Start the PostgreSQL container.
     */
    fun start() {
        container.start()
    }

    /**
     * Stop the PostgreSQL container.
     */
    override fun close() {
        container.stop()
    }

    /**
     * Get the JDBC URL.
     */
    fun getJdbcUrl(): String = container.jdbcUrl

    /**
     * Get the host.
     */
    fun getHost(): String = container.host

    /**
     * Get the port.
     */
    fun getPort(): Int = container.firstMappedPort

    /**
     * Get the database name.
     */
    fun getDatabaseName(): String = container.databaseName

    /**
     * Get the username.
     */
    fun getUsername(): String = container.username

    /**
     * Get the password.
     */
    fun getPassword(): String = container.password

    /**
     * Check if the container is running.
     */
    fun isRunning(): Boolean = container.isRunning
}
