// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.runner.LemlineApplication.Companion.configPath
import com.lemline.runner.cli.MainCommand
import com.lemline.runner.cli.StartCommand // Required for `is StartCommand` check
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
        val commandLine = CommandLine(mainCommand, factory)
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
            val parseResult = getParseResult(args)
            val helpOrVersion = parseResult.isUsageHelpRequested || parseResult.isVersionHelpRequested

            val command = parseResult.commandSpec().userObject()

            if (command is StartCommand && !helpOrVersion) {
                System.setProperty("quarkus.profile", "consumer")
            } else {
                System.setProperty("quarkus.profile", "default")
            }

            // Set the logging level based on the command line arguments
            setLoggingLevel(parseResult)

            // Set the config path based on the command line arguments and environment variables
            setConfigPath(parseResult)

            if (configPath == null && !helpOrVersion) {
                System.err.println("ERROR: No valid configuration file found. Please provide one using one of the following methods:")
                System.err.println("1. Pass the path to the file as a command-line argument (e.g., --config=<path>).")
                System.err.println("2. Set the LEMLINE_CONFIG environment variable to the file's path.")
                System.err.println("3. Place a .lemline.yaml file in the current directory.")
                System.err.println("4. Place a config.yaml file in ~/.config/lemline/.")
                System.err.println("5. Place a .lemline.yaml file in your home directory.")
                exitProcess(1)
            }

            // --- Launch Quarkus ---
            Quarkus.run(LemlineApplication::class.java, *args)
        }

        /**
         * This function ensures that the most specific command (or subcommand) is returned for further processing.
         */
        private fun getParseResult(args: Array<String>): ParseResult {
            val tempCli = CommandLine(MainCommand(), CommandLine.defaultFactory())
            val topLevelParseResult = tempCli.parseArgs(*args)

            val subcommandsParseResults = topLevelParseResult.subcommands()
            return when {
                subcommandsParseResults.isEmpty() -> topLevelParseResult
                else -> subcommandsParseResults.last()
            }
        }

        /**
         * Sets the logging level based on the provided parse result.
         * It checks the command line arguments for log level options.
         *
         * @param parseResult The parse result from the command line.
         */
        private fun setLoggingLevel(parseResult: ParseResult) {
            val logLevelValue = parseResult.matchedOptionValue<Level>("--log", null)
                ?: parseResult.matchedOptionValue<Level>("-l", null)

            logLevelValue?.let { setLogLevel(it) }
        }

        /**
         * Sets the configuration path based on the provided parse result.
         * It checks the command line arguments, environment variables, and default locations.
         *
         * @param parseResult The parse result from the command line.
         */
        private fun setConfigPath(parseResult: ParseResult) {
            val optionPath = parseResult.matchedOptionValue<String>("--config", null)
                ?: parseResult.matchedOptionValue<String>("-c", null)

            optionPath?.let {
                if (checkConfigLocation(Paths.get(it), true)) return
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
        error("'${path.toAbsolutePath()} does not exist")
    }
    if (!isRegularFile && provided) {
        error("'${path.toAbsolutePath()} is not a regular file")
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
}

private fun error(msg: String): Nothing {
    System.err.println("ERROR: $msg")
    exitProcess(1)
}
