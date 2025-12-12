// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing.infrastructure

import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * MySQL container wrapper for E2E testing.
 *
 * Provides a pre-configured MySQL instance using Testcontainers.
 */
class MySQLTestContainer : AutoCloseable {

    private val container = MySQLContainer(
        DockerImageName.parse("mysql:8.0")
    ).apply {
        withDatabaseName("lemline_test")
        withUsername("lemline")
        withPassword("lemline")
    }

    /**
     * Start the MySQL container.
     */
    fun start() {
        container.start()
    }

    /**
     * Stop the MySQL container.
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
