// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.runner.LemlineApplication.Companion.configPath
import com.lemline.runner.cli.MainCommand
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
        // mainCommand.daemon is initially false.

        val exitCode = commandLine.execute(*args)
        // - If `lemline --help` or `lemline --version` was called, Picocli handles it.
        //   `mainCommand.run()` is NOT called, so `mainCommand.daemon` remains `false`. `exitCode` is 0.
        // - If `lemline subcommand ...` was called, the subcommand's `run` is executed.
        //   `mainCommand.run()` is NOT called, so `mainCommand.daemon` remains `false`. `exitCode` is from the subcommand.
        // - If `lemline` (no subcommand, no main command help/version) was called, `mainCommand.run()` IS called,
        //   setting `mainCommand.daemon` to `true`. `exitCode` is 0.

        // After execute(), Picocli stores the parse result. We can use it to check if help/version was shown.
        val parseResult = commandLine.parseResult
        // This covers help/version for the main command or any subcommand.
        if (parseResult?.isUsageHelpRequested == true || parseResult?.isVersionHelpRequested == true) {
            // Picocli has already printed the help/version message.
            log.info(
                "Help or version was displayed for command '${
                    parseResult.commandSpec().qualifiedName()
                }'. Exiting with code $exitCode."
            )
            return exitCode // exitCode is typically 0 for successful help/version display
        }

        // If no help or version was displayed, proceed with existing logic.
        // `mainCommand.daemon` will be true if MainCommand.run() was called (i.e., server mode).
        // `mainCommand.daemon` will be false if a subcommand was run or if main command help/version was shown (already handled above).
        if (!mainCommand.daemon || exitCode != 0) {
            log.info("Command execution completed. Exit code: $exitCode. mainCommand.daemon: ${mainCommand.daemon}. Exiting.")
            return exitCode
        }

        log.info("Starting Quarkus application in server mode (mainCommand.daemon: ${mainCommand.daemon}, exitCode: $exitCode).")
        Quarkus.waitForExit()
        return 0
    }

    companion object {
        var configPath: Path? = null

        @JvmStatic
        fun main(args: Array<String>) {

            // define logging level
            setLoggingLevel(args)

            // define lemline config location
            setConfigPath(args)

            // --- Launch Quarkus ---
            Quarkus.run(LemlineApplication::class.java, *args)
        }

        /**
         * Sets the logging level for the application based on the provided command-line arguments.
         *
         * This function searches for a logging level argument in the form of `--log=<level>` or `-l <level>`.
         * If a valid logging level is found, it updates the application's logging configuration accordingly.
         *
         * @param args The command-line arguments provided to the application.
         */
        private fun setLoggingLevel(args: Array<String>) {
            getArgValue("--log=", "-l", args)?.let { setLogLevel(it) }
        }

        /**
         * Searches for the Lemline configuration file in a predefined order and returns the absolute path of the first valid file found.
         *
         * Search Order:
         * 1. Command-line argument: --config=<file> or -c <file> (highest priority)
         * 2. Environment variable: LEMLINE_CONFIG
         * 3. Current directory: ./.lemline.yaml
         * 4. User config directory: ~/.config/lemline/config.yaml
         * 5. User home directory: ~/.lemline.yaml (lowest priority)
         *
         * @param args The command-line arguments provided to the application.
         * @return The absolute path of the first valid configuration file found, or null if no valid file is found.
         *         Exits the application with an error if a specified file (via CLI or ENV) does not exist or is invalid.
         */
        private fun setConfigPath(args: Array<String>) {
            // 1. Check Command Line Arguments (--config= or -c)
            getArgValue("--config=", "-c", args)?.let {
                if (checkConfigLocation(Paths.get(it), true)) return
            }

            // 2. Check Environment Variable (LEMLINE_CONFIG)
            System.getenv("LEMLINE_CONFIG")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (checkConfigLocation(Paths.get(it), true)) return
            }

            // 3. Check Current Directory (./.lemline.yaml)
            if (checkConfigLocation(Paths.get(".lemline.yaml"), false)) return

            System.getProperty("user.home")?.let {
                // 4. Check User Config Directory (~/.config/lemline/config.yaml)
                val xdgPath = Paths.get(it, ".config", "lemline", "config.yaml")
                if (checkConfigLocation(xdgPath, false)) return

                // 5. Check User Home Directory (~/.lemline.yaml)
                val homePath = Paths.get(it, ".lemline.yaml")
                if (checkConfigLocation(homePath, false)) return
            }
                ?: System.err.println("Warning: Could not determine user home directory. Skipping user-specific config locations.")

            // No configuration file found in any standard location
            System.err.println("ERROR: No valid configuration file found. Please provide one using one of the following methods:")
            System.err.println("1. Pass the path to the file as a command-line argument (e.g., --config=<path>).")
            System.err.println("2. Set the LEMLINE_CONFIG environment variable to the file's path.")
            System.err.println("3. Place a .lemline.yaml file in the current directory.")
            System.err.println("4. Place a config.yaml file in ~/.config/lemline/.")
            System.err.println("5. Place a .lemline.yaml file in your home directory.")

            exitProcess(1)
        }
    }
}

private fun getArgValue(long: String, short: String, args: Array<String>): String? {
    for (i in args.indices) {
        when {
            args[i].startsWith(long) -> {
                val value = args[i].substringAfter(long).trim()
                if (value.isEmpty()) error("$short argument requires a value.")
                return value
            }

            args[i] == short && i + 1 < args.size -> {
                val value = args[i + 1].trim()
                if (value.isEmpty()) error("$short argument requires a value.")
                return value
            }

            // Handle case where -c is the last argument
            args[i] == short -> error("$short argument requires a value.")
        }
    }
    return null
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

private fun setLogLevel(arg: String) {
    val level = try {
        Logger.Level.valueOf(arg.uppercase()).toString()
    } catch (e: Exception) {
        val validLevels = Level.entries.joinToString(", ") { it.name }
        error("Invalid log level '$arg'. Allowed values are: $validLevels")
    }
    System.setProperty("quarkus.log.level", level)
    System.setProperty("quarkus.log.console.level", level)
    System.setProperty("quarkus.log.category.\"com.lemline\".level", level)
    System.setProperty("quarkus.log.category.\"io.quarkus\".level", level)
    System.setProperty("quarkus.log.category.\"org.flywaydb\".level", level)
    System.setProperty("quarkus.log.category.\"io.smallrye\".level", level)
    System.setProperty("quarkus.log.category.\"io.agroal\".level", level)

}

private fun error(msg: String): Nothing {
    System.err.println("ERROR: $msg")
    exitProcess(1)
}
