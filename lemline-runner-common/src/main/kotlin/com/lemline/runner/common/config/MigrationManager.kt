// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

/**
 * Interface for database migration operations.
 * Provides methods for checking and running database migrations.
 */
interface MigrationManager {
    /**
     * Represents information about a database migration.
     */
    data class MigrationInfo(
        val version: String?,
        val description: String,
        val state: String,
        val installedOn: String?
    )

    /**
     * Returns a list of pending migrations that have not been applied yet.
     */
    fun getPendingMigrations(): List<MigrationInfo>

    /**
     * Returns a list of all migrations (applied and pending).
     */
    fun getAllMigrations(): List<MigrationInfo>

    /**
     * Runs all pending migrations.
     */
    fun migrate()
}
