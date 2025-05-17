// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definitions

import com.lemline.runner.cli.LemlineMixin
import io.quarkus.arc.Unremovable
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

@Unremovable
@Command(
    name = "definition",
    description = ["Manage workflow definitions"],
    subcommands = [
        // Reference the new/updated command classes
        DefinitionGetCommand::class,
        DefinitionPostCommand::class,
        DefinitionDeleteCommand::class,
    ],
)
class DefinitionCommand {
    @Mixin
    lateinit var mixin: LemlineMixin
}
