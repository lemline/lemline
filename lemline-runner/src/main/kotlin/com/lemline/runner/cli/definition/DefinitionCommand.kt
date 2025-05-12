// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definition

import io.quarkus.arc.Unremovable
import picocli.CommandLine.Command

@Unremovable
@Command(
    name = "definition",
    description = ["Manage workflow definitions"],
    mixinStandardHelpOptions = true,
    subcommands = [
        // Reference the new/updated command classes
        DefinitionGetCommand::class,
        DefinitionPostCommand::class,
        DefinitionDeleteCommand::class,
    ]
)
class DefinitionCommand
