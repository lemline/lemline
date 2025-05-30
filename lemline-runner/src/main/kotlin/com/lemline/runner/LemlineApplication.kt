// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.runner.LemlineApplication.Companion.configPath
import com.lemline.runner.cli.CustomExceptionHandler
import com.lemline.runner.cli.CustomParameterHandler
import com.lemline.runner.cli.MainCommand
import com.lemline.runner.cli.instances.InstanceStartCommand
import com.lemline.runner.cli.listen.ListenCommand
import com.lemline.runner.config.CONSUMER_ENABLED
import com.lemline.runner.config.PRODUCER_ENABLED
import io.quarkus.picocli.runtime.annotations.TopCommand
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess
import org.jboss.logging.Logger
import org.jboss.logging.Logger.Level
import picocli.CommandLine
import picocli.CommandLine.IFactory
import picocli.CommandLine.ParseResult

/**
 * Main entry point for the Lemline application.
 * This class is responsible for starting the Quarkus application and processing CLI commands.
 */
@QuarkusMain
class LemlineApplication : QuarkusApplication {

    @Inject
    @TopCommand
    lateinit var mainCommand: MainCommand

    @Inject
    lateinit var factory: IFactory

    @Inject
    lateinit var log: Logger

    override fun run(vararg args: String): Int {
        val commandLine = CommandLine(mainCommand, factory).setup()
        //  val commandLine = mainCommand.commandLine
        val exitCode = commandLine.execute(*args)

        log.info("Execution completed. Exit code: $exitCode. Exiting.")

        return exitCode
    }

    companion object {
        var configPath: Path? = null

        /**
         * Main method to start the Lemline application.
         *
         * It parses command line arguments BEFORE quarkus starts, to be able to set
         * - logging level
         * - config path
         * - profile
         *
         * @param args Command line arguments.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            // Create a temporary CommandLine instance to parse the arguments
            val tempCli = CommandLine(MainCommand()).setup()

            try {
                val parseResults = getParseResults(tempCli, args)

                // Check if the command line arguments contain help or version options
                val helpOrVersion = parseResults.any { it.isUsageHelpRequested || it.isVersionHelpRequested }

                // for the listen command (not overridden by --help or --version)
                // enable the consumer and producer
                if (parseResults.any { it.commandSpec().userObject() is ListenCommand && !helpOrVersion }) {
                    System.setProperty(CONSUMER_ENABLED, "true")
                    System.setProperty(PRODUCER_ENABLED, "true")
                }

                // for the start command (not overridden by --help or --version)
                // enable the producer only
                if (parseResults.any { it.commandSpec().userObject() is InstanceStartCommand && !helpOrVersion }) {
                    System.setProperty(PRODUCER_ENABLED, "true")
                }

                // Set the logging level
                setLoggingLevel(parseResults)

                // Set the config path
                setConfigPath(parseResults)

                if (configPath == null && !helpOrVersion) {
                    System.err.println("No valid configuration file found. Please provide one, using one of the following methods:")
                    System.err.println("1. Pass the path to the file as a command-line argument (e.g., --config=<path>).")
                    System.err.println("2. Set the LEMLINE_CONFIG environment variable to the file's path.")
                    System.err.println("3. Place a .lemline.yaml file in the current directory.")
                    System.err.println("4. Place a config.yaml file in ~/.config/lemline/.")
                    System.err.println("5. Place a .lemline.yaml file in your home directory.")
                    exitProcess(1)
                }
            } catch (ex: Exception) {
                // Handle all exceptions in a unified way
                System.err.println("⚠️ ${ex.message}")
                exitProcess(1)
            }

            // --- Launch Quarkus ---
            Quarkus.run(LemlineApplication::class.java, *args)
        }

        /**
         * This function collects the main parse result and any subcommand parse results into a list.
         *
         * @param args The command-line arguments to parse.
         * @return A list of `ParseResult` objects representing the parsed arguments.
         * @throws CommandLine.PicocliException If an error occurs during argument parsing.
         */
        private fun getParseResults(cl: CommandLine, args: Array<String>): List<ParseResult> {
            val mainParseResult = cl.parseArgs(*args)

            fun collectAllSubcommands(pr: ParseResult): List<ParseResult> =
                listOf(pr) + pr.subcommands().flatMap { collectAllSubcommands(it) }

            return collectAllSubcommands(mainParseResult)
        }

        /**
         * Sets the logging level for the application based on the parsed command-line arguments.
         *
         * This function checks for the `--debug`, `--info`, `--warn`, and `--error` flag options
         * across the entire command chain. If any of these logging level flags is specified,
         * it updates the application's logging configuration accordingly.
         *
         * @param parseResults A list of `ParseResult` objects containing parsed command-line arguments.
         */
        private fun setLoggingLevel(parseResults: List<ParseResult>) {
            // Check all ParseResults for any of the log level flags
            val debugMode = parseResults.any { it.hasMatchedOption("--debug") }
            val infoMode = parseResults.any { it.hasMatchedOption("--info") }
            val warnMode = parseResults.any { it.hasMatchedOption("--warn") }
            val errorMode = parseResults.any { it.hasMatchedOption("--error") }

            // Prioritize flags in order: debug > info > warn > error
            val logLevel = when {
                debugMode -> Level.DEBUG
                infoMode -> Level.INFO
                warnMode -> Level.WARN
                errorMode -> Level.ERROR
                else -> null
            }

            logLevel?.let { setLogLevel(it) }
        }

        /**
         * Sets the configuration path for the application by checking various sources in order of priority:
         * 1. Command-line arguments (`--config` or `-c`).
         * 2. Environment variable `LEMLINE_CONFIG`.
         * 3. A `.lemline.yaml` file in the current directory.
         * 4. User-specific configuration files:
         *    - `~/.config/lemline/config.yaml`
         *    - `~/.lemline.yaml`
         *
         * If a valid configuration file is found, it sets the `configPath` variable.
         * If no valid configuration file is found, the application continues without setting `configPath`.
         *
         * @param parseResults A list of `ParseResult` objects containing parsed command-line arguments.
         */
        private fun setConfigPath(parseResults: List<ParseResult>) {
            parseResults.forEach { parseResult ->
                val optionPath = parseResult.matchedOptionValue<String>("--config", null)
                    ?: parseResult.matchedOptionValue<String>("-c", null)

                optionPath?.let {
                    if (checkConfigLocation(Paths.get(it), true)) return
                }
            }

            // If not found via CLI, or if parseResult was null, check other sources:
            System.getenv("LEMLINE_CONFIG")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (checkConfigLocation(Paths.get(it), true)) return
            }

            if (checkConfigLocation(Paths.get(".lemline.yaml"), false)) return

            System.getProperty("user.home")?.let {
                val xdgPath = Paths.get(it, ".config", "lemline", "config.yaml")
                if (checkConfigLocation(xdgPath, false)) return
                val homePath = Paths.get(it, ".lemline.yaml")
                if (checkConfigLocation(homePath, false)) return
            }
                ?: System.err.println("Warning: Could not determine user home directory. Skipping user-specific config locations.")

        }
    }
}

private fun CommandLine.setup() = this
    .setUsageHelpAutoWidth(true)
    .setCaseInsensitiveEnumValuesAllowed(true)
    .setUnmatchedArgumentsAllowed(false)
    .apply {
        parameterExceptionHandler = CustomParameterHandler()
        executionExceptionHandler = CustomExceptionHandler()
    }

private fun checkConfigLocation(filePath: Path, provided: Boolean): Boolean {
    val path = filePath.normalize()
    val fileExists = Files.exists(path)
    val isRegularFile = Files.isRegularFile(path)
    if (!fileExists && provided) {
        throw IllegalArgumentException("'${path.toAbsolutePath()}' does not exist")
    }
    if (!isRegularFile && provided) {
        throw IllegalArgumentException("'${path.toAbsolutePath()}' is not a regular file")
    }
    if (fileExists && isRegularFile) {
        configPath = path
        return true
    }
    return false
}

private fun setLogLevel(level: Level) = level.name.let {
    System.setProperty("quarkus.log.level", it)
    System.setProperty("quarkus.log.console.level", it)
    System.setProperty("quarkus.log.category.\"com.lemline\".level", it)
    System.setProperty("quarkus.log.category.\"io.quarkus\".level", it)
    System.setProperty("quarkus.log.category.\"org.flywaydb\".level", it)
    System.setProperty("quarkus.log.category.\"io.smallrye\".level", it)
    System.setProperty("quarkus.log.category.\"io.agroal\".level", it)
    System.setProperty("quarkus.log.category.\"org.apache.kafka\".level", it)
}
