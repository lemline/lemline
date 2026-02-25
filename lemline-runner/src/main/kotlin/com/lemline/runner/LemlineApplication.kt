// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.runner.cli.MainCommand
import com.lemline.runner.cli.config.ConfigCreateCommand
import com.lemline.runner.cli.config.ConfigPathHolder
import com.lemline.runner.cli.gateway.GatewayStartCommand
import com.lemline.runner.cli.instances.InstanceStartCommand
import com.lemline.runner.cli.listen.ListenCommand
import com.lemline.runner.cli.setup
import com.lemline.runner.common.config.LEMLINE_DATABASE_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_GRPC_PORT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_SCHEDULED_ENABLED
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
            try {
                // Create a temporary CommandLine instance to parse the arguments
                val tempCli = CommandLine(MainCommand()).setup()

                val parseResults: List<ParseResult> = getParseResults(tempCli, args)

                // Check if the command line arguments contain help or version options
                val helpOrVersion = parseResults.any { it.isUsageHelpRequested || it.isVersionHelpRequested }

                // Set the logging level
                setLoggingLevel(parseResults)

                // Set JSON logging mode
                setJsonLogging(parseResults)

                // Set the config path
                setConfigPath(parseResults)

                // config create doesn't need an existing config file
                val isConfigCreate = parseResults.command<ConfigCreateCommand>() != null

                if (ConfigPathHolder.configPath == null && !helpOrVersion && !isConfigCreate) {
                    System.err.println("No valid configuration file found. Please provide one, using one of the following methods:")
                    System.err.println("1. Pass the path to the file as a command-line argument (e.g., --config=<path>).")
                    System.err.println("2. Set the LEMLINE_CONFIG environment variable to the file's path.")
                    System.err.println("3. Place a .lemline.yaml file in the current directory.")
                    System.err.println("4. Place a config.yaml file in ~/.config/lemline/.")
                    System.err.println("5. Place a .lemline.yaml file in your home directory.")
                    exitProcess(1)
                }

                if (helpOrVersion) {
                    disableMetricsEndpoint()
                    disableMessaging()
                    disableDatabase()
                } else {
                    // the gateway start command, if any
                    val gatewayStart = parseResults.command<GatewayStartCommand>()

                    // The listen command, if any
                    val listen = parseResults.command<ListenCommand>()

                    if (listen == null && gatewayStart == null) {
                        disableMetricsEndpoint()
                    }

                    if (listen == null) {
                        disableMessaging()
                        disableScheduled()
                        if (isConfigCreate) disableDatabase()
                    } else {
                        listen.port?.let { setMetricsEndpointPort(it) }
                        enableMessaging()
                    }

                    // the instance start command, if any
                    val start = parseResults.command<InstanceStartCommand>()
                    if (start != null) {
                        System.setProperty(LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED, "true")
                    }

                    if (gatewayStart != null) {
                        enableGateway()
                        gatewayStart.port?.let {
                            System.setProperty(LEMLINE_GATEWAY_GRPC_PORT, it.toString())
                        }
                    }
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
         * This function retrieves the first command of the specified type from the list of parse results.
         */
        private inline fun <reified T> List<ParseResult>.command() = firstOrNull { it.commandSpec().userObject() is T }
            ?.commandSpec()?.userObject() as? T

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
         * Sets JSON logging mode for the application based on the parsed command-line arguments.
         *
         * This function checks for the `--json` flag option across the entire command chain.
         * If the JSON logging flag is specified, it enables JSON-formatted console output.
         *
         * @param parseResults A list of `ParseResult` objects containing parsed command-line arguments.
         */
        private fun setJsonLogging(parseResults: List<ParseResult>) {
            val jsonMode = parseResults.any { it.hasMatchedOption("--json") }
            if (jsonMode) {
                System.setProperty("quarkus.log.console.json", "true")
            }
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
         * If a valid configuration file is found, it sets the `ConfigPathHolder.configPath` variable.
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
        ConfigPathHolder.configPath = path
        return true
    }
    return false
}

private fun setMetricsEndpointPort(port: Int) {
    System.setProperty("quarkus.http.port", port.toString())
    System.setProperty("quarkus.http.ssl-port", port.toString())
}

private fun disableMetricsEndpoint() {
    System.setProperty("quarkus.http.port", "0")
    System.setProperty("quarkus.http.ssl-port", "0")
    System.setProperty("quarkus.micrometer.enabled", "false")
    System.setProperty("quarkus.micrometer.export.prometheus.enabled", "false")
}

private fun enableMessaging() {
    System.setProperty(LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED, "true")
    System.setProperty(LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED, "true")
    System.setProperty(LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED, "true")
    System.setProperty(LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED, "true")
    System.setProperty(LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED, "true")
    System.setProperty(LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED, "true")
    System.setProperty(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED, "true")
    System.setProperty(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED, "true")
}

private fun disableMessaging() {
    System.setProperty(LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED, "false")
}

private fun disableDatabase() {
    System.setProperty(LEMLINE_DATABASE_ENABLED, "false")
}

private fun disableScheduled() {
    System.setProperty(LEMLINE_SCHEDULED_ENABLED, "false")
}

private fun enableGateway() {
    System.setProperty(LEMLINE_GATEWAY_ENABLED, "true")
    System.setProperty(LEMLINE_DATABASE_ENABLED, "true")
    System.setProperty(LEMLINE_SCHEDULED_ENABLED, "false")
    System.setProperty("quarkus.smallrye-health.enabled", "true")

    System.setProperty(LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED, "true")
    System.setProperty(LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED, "false")
    System.setProperty(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED, "true")
    System.setProperty(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED, "false")
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
