// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.github.zafarkhaja.semver.Version // For sorting
import com.lemline.runner.models.WorkflowModel // Import model
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
        "- Interactively selects one or all (*) listed workflows if name/version are omitted.",
        "- Requires confirmation unless --force is used.",
        "--force options determine scope:",
        "  - No args: Deletes ALL workflows.",
        "  - <name>: Deletes all versions of the workflow with the specified name.",
        "  - <name> <version>: Deletes the specific workflow version."
    ],
    mixinStandardHelpOptions = true
)
class WorkflowDeleteCommand : Runnable {

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Parameters(
        index = "0..*",
        arity = "0..2",
        description = ["Optional: workflow name.", "Optional: workflow version."]
    )
    var params: List<String> = emptyList()

    @Option(
        names = ["--force", "-F"],
        description = ["Force deletion without confirmation, using provided args for scope."],
        defaultValue = "false"
    )
    var force: Boolean = false

    @ParentCommand
    lateinit var parent: WorkflowCommand

    override fun run() {
        parent.parent.daemon = false

        try {
            if (force) {
                // Forced mode: Use parameters to determine deletion scope
                when (params.size) {
                    0 -> deleteAllWorkflows()
                    1 -> deleteAllVersionsByName(params[0])
                    2 -> deleteSpecificVersion(params[0], params[1])
                    // No 'else' needed due to arity = "0..2"
                }
            } else {
                // Interactive / Confirmation mode:
                when (params.size) {
                    // No params, list all
                    0 -> interactiveSelectAndDeleteWorkflow(filterName = null)
                    // Name provided, list versions
                    1 -> interactiveSelectAndDeleteWorkflow(filterName = params[0])
                    // Both name and version provided - confirm THIS specific one
                    2 -> deleteSpecificVersion(params[0], params[1]) // confirmDeletion inside will prompt
                    // No 'else' needed due to arity = "0..2"
                }
            }
        } catch (e: Exception) {
            // Catch exceptions from both interactive and forced modes
            throw ExecutionException("ERROR during workflow deletion: ${e.message}", e)
        }
    }

    // --- Methods used for BOTH Forced & Confirmation Deletion --- //

    private fun deleteAllWorkflows() {
        val workflowCount = workflowRepository.count()
        if (workflowCount == 0L) {
            println("No workflows found to delete.")
            return
        }
        val subject = "ALL $workflowCount workflows"
        if (!confirmDeletion(subject)) return

        val deletedCount = workflowRepository.deleteAll()
        println("Successfully deleted $deletedCount workflows." + if (force) " (forced)" else "")
    }

    private fun deleteAllVersionsByName(name: String) {
        val workflowsToDelete = workflowRepository.listByName(name)
        if (workflowsToDelete.isEmpty()) {
            println("No workflows found with name '$name'.")
            return
        }
        val versionsString = workflowsToDelete.joinToString { it.version }
        val subject = "all ${workflowsToDelete.size} versions ($versionsString) of workflow '$name'"
        if (!confirmDeletion(subject)) return // confirmDeletion handles 'force'

        val deletedCount = workflowRepository.delete(workflowsToDelete)
        if (deletedCount == workflowsToDelete.size) {
            println("Successfully deleted $deletedCount versions of workflow '$name'." + if (force) " (forced)" else "")
        } else {
            System.err.println("Warning: Expected to delete ${workflowsToDelete.size} workflows, but deleted $deletedCount.")
        }
    }

    private fun deleteSpecificVersion(name: String, version: String) {
        val workflowToDelete = workflowRepository.findByNameAndVersion(name, version)
            ?: run {
                println("Workflow '$name' version '$version' not found.")
                return
            }

        val subject = "workflow '$name' version '$version'"
        if (!confirmDeletion(subject)) return // confirmDeletion handles 'force'

        val deletedCount = workflowRepository.delete(workflowToDelete)
        if (deletedCount == 1) {
            println("Successfully deleted workflow '$name' version '$version'." + if (force) " (forced)" else "")
        } else {
            System.err.println("Warning: Expected to delete '$name' version '$version'. Deleted concurrently?")
        }
    }

    // --- Method ONLY for INTERACTIVE Deletion --- //

    private fun interactiveSelectAndDeleteWorkflow(filterName: String?) {
        val workflowsToDisplay = if (filterName != null) {
            workflowRepository.listByName(filterName)
        } else {
            workflowRepository.listAll()
        }

        if (workflowsToDisplay.isEmpty()) {
            println(
                if (filterName != null) "No versions found for workflow '$filterName'."
                else "No workflows found in the database."
            )
            return
        }

        // *** Add check for single result ***
        if (workflowsToDisplay.size == 1) {
            val singleWorkflow = workflowsToDisplay.first()
            // Directly attempt deletion for this single workflow (will trigger confirmation)
            deleteSpecificVersion(singleWorkflow.name, singleWorkflow.version)
            return // Skip the list display and prompt
        }

        // --- Proceed with list display and selection only if size > 1 ---
        val selectionMap = mutableMapOf<Int, WorkflowModel>()
        displayWorkflowListForSelection(workflowsToDisplay, selectionMap)

        // Prompt for selection
        print("\nEnter # to delete, * to delete all listed, or leave blank to exit: ")
        while (true) {
            val input = readlnOrNull()?.trim()

            when {
                input.isNullOrEmpty() -> {
                    println("Selection cancelled.") // Explicit cancel from prompt
                    return // Exit loop only on explicit cancel
                }

                input == "*" -> {
                    // User selected all listed workflows
                    handleDeleteAllListed(workflowsToDisplay, filterName)
                    // Don't return, re-prompt after handling
                }

                else -> {
                    // User entered something else, try parsing as number
                    val choice = input.toIntOrNull()
                    if (choice != null && selectionMap.containsKey(choice)) {
                        // Valid number selected
                        val workflowToDelete = selectionMap[choice]!!
                        val subject = "workflow '${workflowToDelete.name}' version '${workflowToDelete.version}'"

                        // Confirm deletion for the selected workflow (force is false here)
                        if (confirmDeletion(subject)) {
                            // --- Deletion Confirmed --- 
                            try {
                                val deletedCount = workflowRepository.delete(workflowToDelete)
                                if (deletedCount == 1) {
                                    println("Successfully deleted workflow '${workflowToDelete.name}' version '${workflowToDelete.version}'.")
                                    // Remove from selection map to prevent re-selection/errors
                                    selectionMap.remove(choice)
                                    // Optional: Refresh the list display here if desired, 
                                    // or just let the user know it's gone if they try again.
                                } else {
                                    System.err.println("ERROR: Failed to delete selected workflow. Maybe it was deleted concurrently?")
                                }
                            } catch (e: Exception) {
                                System.err.println("ERROR deleting selected workflow: ${e.message}")
                            }
                            // Don't return, re-prompt after action
                        }
                        // else: confirmDeletion returned false and printed "Deletion cancelled."
                        // Loop will continue and re-prompt automatically.

                    } else {
                        // Invalid number input
                        print("Invalid input. Enter #, *, or leave blank to exit: ")
                        continue // Skip re-printing the main prompt on invalid input
                    }
                }
            }
            // Re-prompt after handling valid input (* or number)
            print("\nEnter # to delete, * to delete all listed, or leave blank to exit: ")
        }
        // Loop is only exited by explicit cancel (return)
    }

    // Helper to display the numbered list
    private fun displayWorkflowListForSelection(
        workflowsToDisplay: List<WorkflowModel>,
        selectionMap: MutableMap<Int, WorkflowModel>
    ) {
        val groupedWorkflows = workflowsToDisplay.groupBy { it.name }.toSortedMap()
        var currentNumber = 1
        val maxNameWidth = groupedWorkflows.keys.maxOfOrNull { it?.length ?: 0 } ?: 10
        val nameHeader = "Name"
        val versionHeader = "Version"
        val numberHeader = "#"
        val numWidth = workflowsToDisplay.size.toString().length
        val paddedNumHeader = numberHeader.padStart(numWidth)
        val paddedNameHeader = nameHeader.padEnd(maxNameWidth)

        println()
        println("$paddedNumHeader  $paddedNameHeader  $versionHeader")
        println("${"-".repeat(numWidth)}  ${"-".repeat(maxNameWidth)}  ${"-".repeat(versionHeader.length)}")

        groupedWorkflows.forEach { (name, versionsList) ->
            val displayName = name ?: "<Unnamed>"
            val sortedVersions = versionsList.sortedBy {
                try {
                    Version.parse(it.version)
                } catch (e: Exception) {
                    Version.parse("0.0.0-invalid")
                }
            }
            sortedVersions.forEachIndexed { index, workflow ->
                val versionPart = workflow.version
                val numberPart = currentNumber.toString().padStart(numWidth)
                selectionMap[currentNumber] = workflow // Map number to the model
                currentNumber++
                if (index == 0) {
                    val namePart = displayName.padEnd(maxNameWidth)
                    println("$numberPart  $namePart  $versionPart")
                } else {
                    val namePart = " ".repeat(maxNameWidth)
                    val marker = if (index == sortedVersions.lastIndex) "└─" else "├─"
                    println("$numberPart  $namePart  $marker $versionPart")
                }
            }
        }
    }

    // Helper to handle deletion when '*' is chosen interactively
    private fun handleDeleteAllListed(workflowsToDelete: List<WorkflowModel>, filterName: String?) {
        val count = workflowsToDelete.size
        val subject = if (filterName != null) {
            val versionsString = workflowsToDelete.joinToString { it.version }
            "all $count listed versions ($versionsString) of workflow '$filterName'"
        } else {
            "all $count listed workflows"
        }

        if (confirmDeletion(subject)) { // Will prompt user as force is false
            try {
                val deletedCount = if (filterName == null) {
                    // If no filter was applied, '*' means delete absolutely all
                    workflowRepository.deleteAll()
                } else {
                    // If a name filter was applied, '*' means delete all versions of that name
                    workflowRepository.delete(workflowsToDelete)
                }

                if (deletedCount == count || (filterName == null && deletedCount >= 0)) { // deleteAll might return 0 or more
                    println("Successfully deleted $deletedCount workflow(s) as requested by '*' selection.")
                } else {
                    System.err.println("Warning: Expected to delete $count workflow(s) via '*' selection, but repository reported $deletedCount deleted.")
                }
            } catch (e: Exception) {
                System.err.println("ERROR deleting selected workflows: ${e.message}")
            }
        }
    }

    // --- Common Helper --- //

    private fun confirmDeletion(subjectDescription: String): Boolean {
        // This check makes confirmation a no-op if force is true
        if (force) return true

        print("Are you sure you want to delete $subjectDescription? [y/N]: ")
        val confirmation = readlnOrNull()?.trim()?.lowercase()
        if (confirmation != "y") {
            println("Selection cancelled.")
            return false
        }
        return true
    }

    // Helper for concise exception throwing
    private fun ExecutionException(message: String, cause: Throwable? = null) =
        CommandLine.ExecutionException(CommandLine(this), message, cause)
}

