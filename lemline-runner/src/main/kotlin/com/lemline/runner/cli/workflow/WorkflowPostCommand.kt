// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.lemline.core.workflows.Workflows
import com.lemline.runner.models.WorkflowModel
import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import java.io.File
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand

@Unremovable
@Command(name = "post", description = ["Create a new workflow from a file"])
class WorkflowPostCommand : Runnable {

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Option(
        names = ["--file", "-f"],
        required = true,
        description = ["Path to the workflow definition file (YAML/JSON)."]
    )
    lateinit var workflowFile: File

    // Workflow Command class
    @ParentCommand
    lateinit var parent: WorkflowCommand

    override fun run() {
        // we stop after this command
        parent.parent.daemon = false

        if (!workflowFile.exists() || !workflowFile.isFile) {
            throw CommandLine.ParameterException(
                CommandLine(this),
                "ERROR: Workflow file not found or is not a regular file: ${workflowFile.absolutePath}"
            )
        }

        println("Creating (POSTing) workflow from file: ${workflowFile.name}")
        try {
            val content = workflowFile.readText()
            // parse and put to
            val workflow = Workflows.parse(content)
            // save it to
            workflowRepository.insert(WorkflowModel.from(workflow))
//            val createdWorkflow = workflowRepository.create(content) // Assuming create method
//            println("Workflow created with ID: ${createdWorkflow.id}") // Placeholder for actual ID
        } catch (e: Exception) {
            throw CommandLine.ExecutionException(
                CommandLine(this),
                "ERROR creating workflow: ${e.message}",
                e
            )
        }
    }
}
