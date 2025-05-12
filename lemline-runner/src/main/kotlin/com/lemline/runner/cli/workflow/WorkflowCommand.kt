// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import io.quarkus.arc.Unremovable
import picocli.CommandLine.Command

@Unremovable
@Command(
    name = "workflow",
    description = ["Manage workflow definitions"],
    mixinStandardHelpOptions = true,
    subcommands = [
        // Reference the new/updated command classes
        WorkflowGetCommand::class,
        WorkflowPostCommand::class,
        WorkflowDeleteCommand::class,
    ]
)
class WorkflowCommand {

}
