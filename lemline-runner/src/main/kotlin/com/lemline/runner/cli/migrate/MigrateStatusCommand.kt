// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.migrate

import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.repositories.DatabaseManager
import jakarta.inject.Inject
import org.flywaydb.core.api.MigrationInfo
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

@Command(
    name = "status",
    description = ["Show the status of database migrations"],
)
class MigrateStatusCommand : Runnable {

    @Inject
    lateinit var databaseManager: DatabaseManager

    @Mixin
    lateinit var mixin: GlobalMixin

    override fun run() {
        val info = databaseManager.flyway.info()
        val allMigrations = info.all()

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

    private fun printMigrationInfo(migration: MigrationInfo) {
        val version = migration.version?.version ?: "N/A"
        val description = migration.description
        val state = migration.state.displayName
        val installedOn = migration.installedOn?.toString() ?: "N/A"
        println(String.format("%-15s %-25s %-10s %-20s", version, description, state, installedOn))
    }
}
