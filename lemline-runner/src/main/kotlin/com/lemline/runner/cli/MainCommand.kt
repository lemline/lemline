// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import com.lemline.runner.cli.config.ConfigCommand
import com.lemline.runner.cli.definitions.DefinitionCommand
import com.lemline.runner.cli.instances.InstanceCommand
import com.lemline.runner.cli.listen.ListenCommand
import io.quarkus.arc.Unremovable
import io.quarkus.picocli.runtime.annotations.TopCommand
import jakarta.enterprise.context.Dependent
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

/**
 * Entry command for Picocli command-line parsing.
 * This class is annotated with @TopCommand to identify it as the main command.
 * It doesn't implement QuarkusApplication - it's purely for command parsing.
 */
@TopCommand
@Command(
    name = "lemline",
    subcommands = [
        DefinitionCommand::class,
        InstanceCommand::class,
        ConfigCommand::class,
        ListenCommand::class
    ]
)
@Unremovable
@Dependent
class MainCommand {
    @Mixin
    lateinit var mixin: GlobalMixin
}
