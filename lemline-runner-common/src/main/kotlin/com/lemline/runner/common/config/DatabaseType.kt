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
enum class DatabaseType {
    /** PostgreSQL database */
    POSTGRESQL,

    /** MySQL database */
    MYSQL,

    /** H2 in-memory database (for testing) */
    H2
}
