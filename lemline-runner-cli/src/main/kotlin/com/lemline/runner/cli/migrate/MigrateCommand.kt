// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.migrate

import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.common.config.MigrationManager
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

@Command(
    name = "migrate",
    description = ["Run database migrations"],
    subcommands = [MigrateStatusCommand::class]
)
class MigrateCommand : Runnable {

    @Inject
    lateinit var migrationManager: MigrationManager

    @Mixin
    lateinit var mixin: GlobalMixin

    @Option(
        names = ["--pretend"],
        description = ["Show what migrations would be applied without actually applying them."]
    )
    var pretend: Boolean = false

    @Option(names = ["--force"], description = ["Force migration without confirmation."])
    var force: Boolean = false

    override fun run() {
        val pendingMigrations = migrationManager.getPendingMigrations()

        if (pendingMigrations.isEmpty()) {
            println("No pending migrations.")
            return
        }

        println("Pending migrations:")
        pendingMigrations.forEach {
            println("  - ${it.version}: ${it.description}")
        }

        if (pretend) {
            return // Just show the migrations and exit
        }

        if (!force) {
            print("Are you sure you want to apply the pending migrations? [y/N]: ")
            val input = readlnOrNull()?.trim()?.lowercase()
            if (input != "y" && input != "yes") {
                println("Migration cancelled.")
                return
            }
        }

        migrationManager.migrate()
        println("Database migration completed successfully.")
    }
}
