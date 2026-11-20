// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.config

import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import io.quarkus.arc.Unremovable
import picocli.CommandLine.Command
import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

@Unremovable
@Command(
    name = "create",
    description = ["Generate a new configuration file"],
)
class ConfigCreateCommand : Runnable {
    @Mixin
    lateinit var mixin: GlobalMixin

    @Option(
        names = ["-m", "--messaging"],
        description = ["Messaging broker type (kafka, rabbitmq, pgmq, sqlite)"],
        converter = [MessagingTypeConverter::class]
    )
    var messaging: MessagingType? = null

    @Option(
        names = ["-d", "--database"],
        description = ["Database type (postgresql, mysql, sqlite)"],
        converter = [DatabaseTypeConverter::class]
    )
    var database: DatabaseType? = null

    @Option(
        names = ["-o", "--output"],
        description = ["Output file path (default: lemline.yaml)"]
    )
    var output: String = "lemline.yaml"

    @Option(
        names = ["--force"],
        description = ["Overwrite existing file"]
    )
    var force: Boolean = false

    @Option(
        names = ["--dry-run"],
        description = ["Print configuration to stdout without writing file"]
    )
    var dryRun: Boolean = false

    private val generator = ConfigTemplateGenerator()

    override fun run() {
        // Prompt for missing values if interactive
        if (messaging == null || database == null) {
            val console = System.console()
            if (console == null) {
                throw ParameterException(
                    picocli.CommandLine(this),
                    "Error: --messaging and --database are required in non-interactive mode"
                )
            }

            if (messaging == null) {
                messaging = promptMessaging(console)
            }
            if (database == null) {
                database = promptDatabase(console)
            }
        }

        // Generate configuration
        val config = generator.generate(messaging!!, database!!)

        // Dry run: print to stdout and exit
        if (dryRun) {
            println(config)
            return
        }

        // Check if file exists
        val outputPath = Path.of(output)
        if (outputPath.exists() && !force) {
            throw ParameterException(
                picocli.CommandLine(this),
                "Error: File '$output' already exists. Use --force to overwrite."
            )
        }

        // Write file
        try {
            outputPath.writeText(config)
            println("Configuration written to: ${outputPath.toAbsolutePath()}")
            println()
            println("Next steps:")
            println("  1. Review and customize the configuration")
            if (messaging == MessagingType.SQLITE) {
                println("  2. Start the database services using the command in the generated file")
                println("  3. Apply database migrations: LEMLINE_CONFIG=$output lemline migrate --force")
                println("  4. Run Lemline: LEMLINE_CONFIG=$output lemline listen")
            } else {
                println("  2. Start infrastructure: docker compose --profile <profile> up -d")
                println("  3. Run Lemline: LEMLINE_CONFIG=$output lemline listen")
            }
        } catch (e: Exception) {
            throw ParameterException(
                picocli.CommandLine(this),
                "Error writing file: ${e.message}"
            )
        }
    }

    private fun promptMessaging(console: java.io.Console): MessagingType {
        println("? Select messaging broker:")
        println("  1) kafka")
        println("  2) rabbitmq")
        println("  3) pgmq")
        println("  4) sqlite")
        print("> ")

        val input = console.readLine().trim()
        return when (input) {
            "1", "kafka" -> MessagingType.KAFKA
            "2", "rabbitmq" -> MessagingType.RABBITMQ
            "3", "pgmq" -> MessagingType.PGMQ
            "4", "sqlite" -> MessagingType.SQLITE
            else -> {
                throw ParameterException(
                    picocli.CommandLine(this),
                    "Invalid selection. Please choose 1, 2, 3 or 4."
                )
            }
        }
    }

    private fun promptDatabase(console: java.io.Console): DatabaseType {
        println("? Select database:")
        println("  1) postgresql")
        println("  2) mysql")
        println("  3) sqlite")
        print("> ")

        val input = console.readLine().trim()
        return when (input) {
            "1", "postgresql" -> DatabaseType.POSTGRESQL
            "2", "mysql" -> DatabaseType.MYSQL
            "3", "sqlite" -> DatabaseType.SQLITE
            else -> {
                throw ParameterException(
                    picocli.CommandLine(this),
                    "Invalid selection. Please choose 1, 2, or 3."
                )
            }
        }
    }

    private class MessagingTypeConverter : ITypeConverter<MessagingType> {
        override fun convert(value: String): MessagingType {
            return when (value.lowercase()) {
                "kafka" -> MessagingType.KAFKA
                "rabbitmq" -> MessagingType.RABBITMQ
                "pgmq" -> MessagingType.PGMQ
                "sqlite" -> MessagingType.SQLITE
                else -> throw IllegalArgumentException(
                    "Invalid messaging type: '$value'. Allowed values: kafka, rabbitmq, pgmq, sqlite"
                )
            }
        }
    }

    private class DatabaseTypeConverter : ITypeConverter<DatabaseType> {
        override fun convert(value: String): DatabaseType {
            return when (value.lowercase()) {
                "postgresql" -> DatabaseType.POSTGRESQL
                "mysql" -> DatabaseType.MYSQL
                "sqlite" -> DatabaseType.SQLITE
                else -> throw IllegalArgumentException(
                    "Invalid database type: '$value'. Allowed values: postgresql, mysql, sqlite"
                )
            }
        }
    }
}
