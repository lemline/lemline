// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test

import com.lemline.runner.common.config.DatabaseConfig

/**
 * Factory for creating test database configurations.
 * Supports H2 (always available), PostgreSQL and MySQL (when Docker is available).
 */
object TestDatabaseFactory {

    /**
     * Creates a DatabaseConfig for the given database type.
     *
     * @param type The database type to create
     * @return A DatabaseConfig instance (must be started for container-based databases)
     */
    fun create(type: DatabaseType): DatabaseConfig {
        return when (type) {
            DatabaseType.H2 -> H2TestDatabaseConfig(type.migrationPath)
            DatabaseType.POSTGRESQL -> PostgresTestDatabaseConfig(type.migrationPath)
            DatabaseType.MYSQL -> MySQLTestDatabaseConfig(type.migrationPath)
        }
    }

    /**
     * Creates and starts a DatabaseConfig, running migrations.
     * For container-based databases, this starts the container.
     *
     * @param type The database type to create
     * @return A ready-to-use DatabaseConfig instance
     */
    fun createAndStart(type: DatabaseType): DatabaseConfig {
        val config = create(type)
        startAndMigrate(config)
        return config
    }

    /**
     * Starts the database (if needed) and runs migrations.
     */
    fun startAndMigrate(config: DatabaseConfig) {
        when (config) {
            is H2TestDatabaseConfig -> config.migrate()
            is PostgresTestDatabaseConfig -> {
                config.start()
                config.migrate()
            }

            is MySQLTestDatabaseConfig -> {
                config.start()
                config.migrate()
            }
        }
    }

    /**
     * Closes the database config (stops containers if applicable).
     */
    fun close(config: DatabaseConfig) {
        when (config) {
            is AutoCloseable -> config.close()
        }
    }
}
