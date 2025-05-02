// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.runner.cli.MainCommand
import io.quarkus.picocli.runtime.annotations.TopCommand
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain
import jakarta.inject.Inject
import java.nio.file.Files
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
        @JvmStatic
        fun main(args: Array<String>) {

            // --- Pre-parse Arguments Before Quarkus Init ---

            // define lemline config locations
            getConfigLocations(args)?.let { System.setProperty("lemline.config.locations", it) }

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

        private fun getConfigLocations(args: Array<String>): String? {
            val configLocations = args.foldIndexed(mutableListOf<String>()) { i, acc, arg ->
                when {
                    arg.startsWith("--file=") -> {
                        val locations = arg.substringAfter("--file=").takeIf { it.isNotEmpty() }
                            ?: run {
                                System.err.println("ERROR: --file= argument requires a value.")
                                exitProcess(1)
                            }
                        acc.addAll(locations.split(',').map(String::trim).filter(String::isNotEmpty))
                    }

                    arg == "-f" -> {
                        val locations = args.getOrNull(i + 1)?.takeIf { it.isNotEmpty() }
                            ?: run {
                                System.err.println("ERROR: -f argument requires a location value.")
                                exitProcess(1)
                            }
                        acc.addAll(locations.split(',').map(String::trim).filter(String::isNotEmpty))
                    }
                }
                acc
            }

            return when (configLocations.size) {
                0 -> null
                else -> {
                    // check that all files exist
                    configLocations.firstOrNull {
                        val path = Paths.get(it)
                        !Files.exists(path) || !Files.isRegularFile(path)
                    }?.let {
                        System.err.println(
                            "ERROR: Specified configuration file does not exist or is not a regular file: ${
                                Paths.get(it).toAbsolutePath()
                            }"
                        )
                        exitProcess(1)
                    }

                    configLocations.joinToString(",")
                }
            }
        }
    }
}
