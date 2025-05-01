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

    @Option(names = ["-s", "--stop"], description = ["Stop after command"])
    var stop: Boolean = false

    override fun run() {
        // Do nothing
    }
} 
