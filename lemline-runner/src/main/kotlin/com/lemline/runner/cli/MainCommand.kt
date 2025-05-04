// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import com.lemline.runner.cli.instance.InstanceCommand
import com.lemline.runner.cli.workflow.WorkflowCommand
import io.quarkus.arc.Unremovable
import io.quarkus.picocli.runtime.annotations.TopCommand
import jakarta.enterprise.context.Dependent
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

    @Option(
        names = ["-d", "--debug"],
        description = ["Enable debug logging"]
    )
    var debug: Boolean = false

    @Option(
        names = ["-f", "--file"],
        description = ["Specify configuration file location"],
        paramLabel = "<location>"
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
}
