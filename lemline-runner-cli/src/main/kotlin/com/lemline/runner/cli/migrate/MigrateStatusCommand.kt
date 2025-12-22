// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.migrate

import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.common.config.MigrationManager
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

@Command(
    name = "status",
    description = ["Show the status of database migrations"],
)
class MigrateStatusCommand : Runnable {

    @Inject
    lateinit var migrationManager: MigrationManager

    @Mixin
    lateinit var mixin: GlobalMixin

    override fun run() {
        val allMigrations = migrationManager.getAllMigrations()

        if (allMigrations.isEmpty()) {
            println("No migrations found.")
            return
        }

        println("Migration Status:")
        println("========================")
        println(String.format("%-15s %-25s %-10s %-20s", "Version", "Description", "State", "Installed On"))
        println("--------------------------------------------------------------------------")

        allMigrations.forEach { printMigrationInfo(it) }
    }

    private fun printMigrationInfo(migration: MigrationManager.MigrationInfo) {
        val version = migration.version ?: "N/A"
        val description = migration.description
        val state = migration.state
        val installedOn = migration.installedOn ?: "N/A"
        println(String.format("%-15s %-25s %-10s %-20s", version, description, state, installedOn))
    }
}
