// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import com.lemline.runner.cli.instance.InstanceCommand
import com.lemline.runner.cli.workflow.WorkflowCommand
import io.quarkus.arc.Unremovable
import io.quarkus.picocli.runtime.annotations.TopCommand
import jakarta.enterprise.context.Dependent
import org.jboss.logging.Logger.Level
import picocli.CommandLine.Command
import picocli.CommandLine.ITypeConverter
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

    @Option(
        names = ["-l", "--log"],
        description = ["Set log level (\${COMPLETION-CANDIDATES})."],
        converter = [LogOptionConverter::class],
        paramLabel = "<level>"
    )
    lateinit var logLevel: Level

    @Option(
        names = ["-c", "--config"],
        description = ["Specify configuration file location"],
        paramLabel = "<config>"
    )
    var configFile: String? = null

    /**
     * Flag to indicate if the application should stop after executing a command.
     * The default behavior for the main command (without subcommands) is to run in daemon mode.
     */
    var daemon: Boolean = true

    override fun run() {
        // Do nothing
    }

    internal class LogOptionConverter : ITypeConverter<Level> {
        override fun convert(value: String): Level = Level.valueOf(value.uppercase())
    }
}
