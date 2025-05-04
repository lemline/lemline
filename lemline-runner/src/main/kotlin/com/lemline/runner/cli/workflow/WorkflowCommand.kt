// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.lemline.runner.cli.MainCommand
import io.quarkus.arc.Unremovable
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand

@Unremovable
@Command(
    name = "workflow",
    description = ["Manage workflows using RESTful verbs"],
    subcommands = [
        // Reference the new/updated command classes
        WorkflowGetCommand::class,
        WorkflowPostCommand::class,
        WorkflowDeleteCommand::class,
    ]
)
class WorkflowCommand {
    // Container class
    @ParentCommand
    lateinit var parent: MainCommand
}
