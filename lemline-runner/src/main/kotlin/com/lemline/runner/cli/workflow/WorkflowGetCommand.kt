// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

// Assuming ExitControlCommand exists in com.lemline.runner.cli
import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand

// Removed Option for format for now

@Unremovable
@Command(name = "get", description = ["Get one or list all workflows"])
class WorkflowGetCommand : Runnable {

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Parameters(
        index = "0",
        arity = "0..1",
        description = ["Optional ID of the workflow to get. If omitted, lists all."]
    )
    var workflowId: String? = null

    // Workflow Command class
    @ParentCommand
    lateinit var parent: WorkflowCommand

    // Removed format option for simplification

    override fun run() {
        // we stop after this command
        parent.parent.daemon = false

        if (workflowId == null) {
            // List all
            println("Listing all workflows...")
            val workflows = workflowRepository.listAll()
            // TODO: Implement formatted output
            if (workflows.isEmpty()) {
                println("No workflows found.")
            } else {
                workflows.forEach { println(it) } // Placeholder output
            }
        } else {
            // Get one by ID
            println("Getting workflow: $workflowId")
            val workflow = workflowRepository.findById(workflowId!!) // Assuming findById() method
            if (workflow != null) {
                // TODO: Implement formatted output
                println(workflow) // Placeholder output
            } else {
                // Consider throwing Picocli exception
                System.err.println("ERROR: Workflow with ID '$workflowId' not found.")
            }
        }
    }
}
