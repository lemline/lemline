package com.lemline.runner.cli

import io.quarkus.arc.Unremovable
import io.quarkus.picocli.runtime.annotations.TopCommand
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import org.jboss.logging.Logger
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Entry command for Picocli command-line parsing.
 * This class is annotated with @TopCommand to identify it as the main command.
 * It doesn't implement QuarkusApplication - it's purely for command parsing.
 */
@TopCommand
@Command(
    name = "lemline",
    mixinStandardHelpOptions = true,
    subcommands = [
        WorkflowCommand::class,
        RuntimeCommand::class,
        InstanceCommand::class,
        ConfigCommand::class
    ]
)
@Unremovable
@Dependent
class MainCommand : Runnable {
    @Inject
    lateinit var log: Logger

    @Option(names = ["-d", "--debug"], description = ["Enable debug logging"])
    var debug: Boolean = false

    // Add option for Picocli recognition, even though logic is handled pre-startup
    @Option(
        names = ["-f", "--file"],
        description = ["Specify configuration file location"],
        paramLabel = "<location>"
    )
    var configFile: String? = null // Dummy variable, value not used by Picocli execution

    /**
     * Flag to indicate if the application should stop after executing a command.
     * The default behavior for the main command (without subcommands) is to run in daemon mode.
     */
    var daemon: Boolean = true

    override fun run() {
        // Do nothing
    }
} 
