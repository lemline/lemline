// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import com.lemline.runner.cli.definition.DefinitionCommand
import com.lemline.runner.cli.instance.InstanceCommand
import io.quarkus.arc.Unremovable
import io.quarkus.picocli.runtime.annotations.TopCommand
import jakarta.enterprise.context.Dependent
import org.jboss.logging.Logger.Level
import picocli.CommandLine.Command
import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ScopeType.INHERIT
import picocli.CommandLine.Spec

internal const val PROFILE_CLI = "cli"

/**
 * Entry command for Picocli command-line parsing.
 * This class is annotated with @TopCommand to identify it as the main command.
 * It doesn't implement QuarkusApplication - it's purely for command parsing.
 */
@TopCommand
@Command(
    name = "lemline",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider::class,
    subcommands = [
        DefinitionCommand::class,
        InstanceCommand::class,
        ConfigCommand::class,
        ListenCommand::class
    ]
)
@Unremovable
@Dependent
class MainCommand : Runnable {

    /**
     * The spec object is used to access the command line options and arguments.
     * It is injected by Picocli and is used to provide help and usage information.
     */
    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["-l", "--log"],
        description = ["Set log level (\${COMPLETION-CANDIDATES})."],
        converter = [LogOptionConverter::class],
        paramLabel = "<level>",
        scope = INHERIT
    )
    var logLevel: Level? = null

    @Option(
        names = ["-c", "--config"],
        description = ["Specify configuration file location"],
        paramLabel = "<config>",
        scope = INHERIT
    )
    var configFile: String? = null

    override fun run() {
        // This method is called if `lemline` is run without a recognized subcommand
        // (e.g., workflow, start, config, instance) or if only global options for MainCommand are provided.
        spec.commandLine().usage(System.out)
        // For now, printing help is the primary action.
        // And Picocli might exit with 0 after a run.
    }

    internal class LogOptionConverter : ITypeConverter<Level> {
        override fun convert(value: String): Level = Level.valueOf(value.uppercase())
    }
}
