package com.lemline.runner

import com.lemline.runner.cli.MainCommand
import io.quarkus.picocli.runtime.annotations.TopCommand
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain
import jakarta.inject.Inject
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

        if (mainCommand.stop || exitCode != 0) {
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
            if (args.contains("--debug") || args.contains("-d")) {
                System.setProperty("quarkus.log.level", "DEBUG")
                System.setProperty("quarkus.log.console.level", "DEBUG")
                System.setProperty("quarkus.log.category.\"com.lemline\".level", "DEBUG")
                System.setProperty("quarkus.log.category.\"io.quarkus\".level", "DEBUG")
                System.setProperty("quarkus.log.category.\"org.flywaydb\".level", "DEBUG")
                System.setProperty("quarkus.log.category.\"io.smallrye\".level", "DEBUG")
                System.setProperty("quarkus.log.category.\"io.agroal\".level", "DEBUG")
            }
            Quarkus.run(LemlineApplication::class.java, *args)
        }
    }
} 
