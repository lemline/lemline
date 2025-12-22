// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test

/**
 * Enum representing the supported database types for testing.
 */
enum class DatabaseType(
    val displayName: String,
    val migrationPath: String
) {
    H2("H2", "classpath:db/migration/h2"),
    POSTGRESQL("PostgreSQL", "classpath:db/migration/postgresql"),
    MYSQL("MySQL", "classpath:db/migration/mysql");

    companion object {
        /**
         * Returns all database types available for testing.
         * PostgreSQL and MySQL require Docker to be available.
         */
        fun availableTypes(): List<DatabaseType> {
            return if (DockerAvailability.isAvailable) {
                entries
            } else {
                listOf(H2)
            }
        }
    }
}
