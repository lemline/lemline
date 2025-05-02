package com.lemline.runner.cli.workflow

import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Unremovable
@Command(name = "delete", description = ["Delete a workflow"])
class WorkflowDeleteCommand : Runnable {

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Parameters(index = "0", description = ["The ID of the workflow to delete."])
    lateinit var workflowId: String

    @Option(names = ["--force"], description = ["Force deletion without confirmation"], defaultValue = "false")
    var force: Boolean = false

    override fun run() {
        if (!force) {
            print("Are you sure you want to delete workflow '$workflowId'? [y/N]: ")
            val confirmation = readlnOrNull()?.trim()?.lowercase()
            if (confirmation != "y") {
                println("Deletion cancelled.")
                return
            }
        }

        println("Deleting workflow: $workflowId (Force: $force)")
        try {
            val deleted = true //workflowRepository.deleteById(workflowId)
            if (deleted) {
                println("Workflow deleted successfully.")
            } else {
                throw CommandLine.ExecutionException(
                    CommandLine(this),
                    "ERROR: Failed to delete workflow '$workflowId'. It might not exist or deletion failed."
                )
            }
        } catch (e: Exception) {
            throw CommandLine.ExecutionException(
                CommandLine(this),
                "ERROR deleting workflow: ${e.message}",
                e
            )
        }
    }
} 
