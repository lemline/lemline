// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import java.io.File
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Unremovable
@Command(name = "update", description = ["Update an existing workflow"])
class WorkflowUpdateCommand : Runnable {

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Parameters(index = "0", description = ["The ID of the workflow to update."])
    lateinit var workflowId: String

    @Option(names = ["--file", "-f"], required = true, description = ["Path to the updated workflow definition file."])
    lateinit var workflowFile: File

    @Option(
        names = ["--force"],
        description = ["Force update even if validation warnings exist"],
        defaultValue = "false"
    )
    var force: Boolean = false

    override fun run() {
        if (!workflowFile.exists() || !workflowFile.isFile) {
            throw CommandLine.ParameterException(
                CommandLine(this),
                "ERROR: Workflow file not found or is not a regular file: ${workflowFile.absolutePath}"
            )
        }

        println("Updating workflow $workflowId from file: ${workflowFile.name} (Force: $force)")
        try {
            val content = workflowFile.readText()
            // TODO: Parse/validate content
            // val updatedWorkflow = workflowRepository.persist(workflowId, content, force) // Assuming update method
            // TODO: Check result of update
            println("Workflow update logic partially implemented.")
        } catch (e: Exception) {
            throw CommandLine.ExecutionException(
                CommandLine(this),
                "ERROR updating workflow: ${e.message}",
                e
            )
        }
    }
}
