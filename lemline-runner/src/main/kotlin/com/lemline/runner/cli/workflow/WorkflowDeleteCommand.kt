// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand

@Unremovable
@Command(
    name = "delete",
    description = [
        "Deletes workflows.",
        "- No args: Deletes ALL workflows.",
        "- <name>: Deletes all versions of the workflow with the specified name.",
        "- <name> <version>: Deletes the specific workflow version."
    ],
    mixinStandardHelpOptions = true
)
class WorkflowDeleteCommand : Runnable {

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    // 0 = delete all, 1 = delete by name, 2 = delete by name and version
    @Parameters(
        index = "0..*", // Accept 0 to 2 parameters
        arity = "0..2",
        description = ["Optional: workflow name.", "Optional: workflow version."]
    )
    var params: List<String> = emptyList()

    @Option(
        names = ["--force", "-F"],
        description = ["Force deletion without confirmation"],
        defaultValue = "false"
    )
    var force: Boolean = false

    @ParentCommand
    lateinit var parent: WorkflowCommand

    override fun run() {
        // we stop after this command
        parent.parent.daemon = false

        when (params.size) {
            0 -> deleteAllWorkflows()
            1 -> deleteAllVersionsByName(params[0])
            2 -> deleteSpecificVersion(params[0], params[1])
            else -> {
                // Should not happen with arity="0..2"
                throw CommandLine.ParameterException(CommandLine(this), "Invalid number of parameters.")
            }
        }
    }

    private fun deleteAllWorkflows() {
        val workflowCount = workflowRepository.count()
        if (workflowCount == 0L) {
            println("No workflows found to delete.")
            return
        }
        val subject = "ALL $workflowCount workflows"
        if (!confirmDeletion(subject)) return

        try {
            val deletedCount = workflowRepository.deleteAll()
            println("Successfully deleted $deletedCount workflows.")
        } catch (e: Exception) {
            throw ExecutionException("ERROR deleting all workflows: ${e.message}", e)
        }
    }

    private fun deleteAllVersionsByName(name: String) {
        val workflowsToDelete = workflowRepository.listByName(name)
        if (workflowsToDelete.isEmpty()) {
            println("No workflows found with name '$name'.")
            return
        }
        val versionsString = workflowsToDelete.joinToString { it.version }
        val subject = "all ${workflowsToDelete.size} versions ($versionsString) of workflow '$name'"
        if (!confirmDeletion(subject)) return

        try {
            val deletedCount = workflowRepository.delete(workflowsToDelete)
            if (deletedCount == workflowsToDelete.size) {
                println("Successfully deleted $deletedCount versions of workflow '$name'.")
            } else {
                // This might indicate a race condition or other issue if not all were deleted
                System.err.println("Warning: Expected to delete ${workflowsToDelete.size} workflows, but deleted $deletedCount.")
            }
        } catch (e: Exception) {
            throw ExecutionException("ERROR deleting versions for workflow '$name': ${e.message}", e)
        }
    }

    private fun deleteSpecificVersion(name: String, version: String) {
        val workflowToDelete = workflowRepository.findByNameAndVersion(name, version)
            ?: run {
                println("Workflow '$name' version '$version' not found.")
                return
            }

        val subject = "workflow '$name' version '$version')"
        if (!confirmDeletion(subject)) return

        try {
            val deletedCount = workflowRepository.delete(workflowToDelete)
            if (deletedCount == 1) {
                println("Successfully deleted workflow '$name' version '$version'.")
            } else {
                // Should ideally not happen if findByNameAndVersion succeeded
                throw ExecutionException("ERROR: Failed to delete workflow '$name' version '$version'. It might have been deleted concurrently.")
            }
        } catch (e: Exception) {
            throw ExecutionException("ERROR deleting workflow '$name' version '$version': ${e.message}", e)
        }
    }

    private fun confirmDeletion(subjectDescription: String): Boolean {
        if (force) return true

        print("Are you sure you want to delete $subjectDescription? [y/N]: ")
        val confirmation = readlnOrNull()?.trim()?.lowercase()
        if (confirmation != "y") {
            println("Deletion cancelled.")
            return false
        }
        return true
    }

    // Helper for concise exception throwing
    private fun ExecutionException(message: String, cause: Throwable? = null) =
        CommandLine.ExecutionException(CommandLine(this), message, cause)
}

