// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

/**
 * Supported database types for Lemline.
 *
 * Using an enum provides:
 * - Type safety: compiler catches invalid values
 * - Exhaustive `when` expressions: compiler warns if cases are missing
 * - Better IDE support: autocomplete and refactoring
 */
enum class DatabaseType(val configValue: String) {
    /** PostgreSQL database */
    POSTGRESQL("postgresql"),

    /** MySQL database */
    MYSQL("mysql"),

    /** H2 in-memory database (for testing and development) */
    H2("in-memory");

    companion object {
        /**
         * Converts a configuration string value to DatabaseType.
         * @throws IllegalArgumentException if the value doesn't match any known type
         */
        fun fromConfigValue(value: String): DatabaseType =
            entries.find { it.configValue == value }
                ?: throw IllegalArgumentException("Unknown database type: '$value'")
    }
}
