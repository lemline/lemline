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
        WorkflowListCommand::class,
        WorkflowGetCommand::class,
        WorkflowPostCommand::class,
        WorkflowUpdateCommand::class,
        WorkflowDeleteCommand::class,
        WorkflowValidateCommand::class
    ]
)
class WorkflowCommand {
    // Container class
    @ParentCommand
    lateinit var parent: MainCommand
} 
