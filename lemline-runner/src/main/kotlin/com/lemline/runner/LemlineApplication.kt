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

        if (mainCommand.stop || args.isNotEmpty()) {
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
            Quarkus.run(LemlineApplication::class.java, *args)
        }
    }
} 
