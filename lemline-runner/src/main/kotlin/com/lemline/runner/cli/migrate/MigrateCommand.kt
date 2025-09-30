// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.migrate

import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.repositories.bases.DatabaseManager
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
    lateinit var databaseManager: DatabaseManager

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
        val pendingMigrations = databaseManager.flyway.info().pending()

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
            print("Are you sure you want to apply the pending migrations? (yes/no): ")
            val input = readlnOrNull()
            if (input?.equals("yes", ignoreCase = true) != true) {
                println("Migration cancelled.")
                return
            }
        }

        databaseManager.flyway.migrate()
        println("Database migration completed successfully.")
    }
}
