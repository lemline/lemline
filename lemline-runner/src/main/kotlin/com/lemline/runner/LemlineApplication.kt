// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

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
        val exitCode = commandLine.execute(*args)

        if (!mainCommand.daemon || exitCode != 0) {
            log.info("Command execution completed with exit code: $exitCode")
            return exitCode
        }

        log.info("Starting Quarkus application in server mode")
        Quarkus.waitForExit()
        return 0
    }

    companion object {
        var configPath: Path? = null

        @JvmStatic
        fun main(args: Array<String>) {

            // --- Pre-parse Arguments Before Quarkus Init ---

            // define lemline config location
            setConfigPath(args)

            // Check for debug flags
            if (args.contains("--debug") || args.contains("-d")) {
                System.setProperty("quarkus.log.level", "DEBUG")
                System.setProperty("quarkus.log.console.level", "DEBUG")
                System.setProperty("quarkus.log.category.\"com.lemline\".level", "DEBUG")
                System.setProperty("quarkus.log.category.\"io.quarkus\".level", "DEBUG")
                System.setProperty("quarkus.log.category.\"org.flywaydb\".level", "DEBUG")
                System.setProperty("quarkus.log.category.\"io.smallrye\".level", "DEBUG")
                System.setProperty("quarkus.log.category.\"io.agroal\".level", "DEBUG")
            }

            // --- Launch Quarkus ---
            Quarkus.run(LemlineApplication::class.java, *args)
        }

        private fun checkConfigLocation(filePath: Path, provided: Boolean): Boolean {
            val path = filePath.normalize()
            val fileExists = Files.exists(path)
            val isRegularFile = Files.isRegularFile(path)
            if (!fileExists && provided) {
                System.err.println("ERROR: '${path.toAbsolutePath()} does not exist")
                exitProcess(1)
            }
            if (!isRegularFile && provided) {
                System.err.println("ERROR: '${path.toAbsolutePath()} is not a regular file")
                exitProcess(1)
            }
            if (fileExists && isRegularFile) {
                configPath = path
                return true
            }
            return false
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
        @JvmStatic
        fun setConfigPath(args: Array<String>) {
            // 1. Check Command Line Arguments (--config= or -c)
            for (i in args.indices) {
                when {
                    args[i].startsWith("--config=") -> {
                        val cliPath = args[i].substringAfter("--config=").trim()
                        when (cliPath.isEmpty()) {
                            true -> {
                                System.err.println("ERROR: --config= argument requires a value.")
                                exitProcess(1)
                            }

                            false -> if (checkConfigLocation(Paths.get(cliPath), true)) return
                        }
                    }

                    args[i] == "-c" && i + 1 < args.size -> {
                        val cliPath = args[i + 1].trim()
                        when (cliPath.isEmpty()) {
                            true -> {
                                System.err.println("ERROR: -c argument requires a value.")
                                exitProcess(1)
                            }

                            false -> if (checkConfigLocation(Paths.get(cliPath), true)) return
                        }
                    }

                    args[i] == "-c" -> { // Handle case where -c is the last argument
                        System.err.println("ERROR: -c argument requires a location value.")
                        exitProcess(1)
                    }
                }
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
