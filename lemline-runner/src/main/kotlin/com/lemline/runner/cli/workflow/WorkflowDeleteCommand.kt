// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.lemline.runner.cli.common.InteractiveWorkflowSelector // Import selector
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

    @Inject // Inject the selector
    lateinit var selector: InteractiveWorkflowSelector

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
                handleForcedDeletion()
            } else {
                handleInteractiveDeletion()
            }
        } catch (e: Exception) {
            throw ExecutionException("ERROR during workflow deletion: ${e.message}", e)
        }
    }

    private fun handleForcedDeletion() {
        when (params.size) {
            0 -> deleteAllWorkflows()
            1 -> deleteAllVersionsByName(params[0])
            2 -> deleteSpecificVersion(params[0], params[1])
        }
    }

    private fun handleInteractiveDeletion() {
        val filterName = if (params.isNotEmpty()) params[0] else null

        // --- Prepare and display list ONCE --- 
        val initialSelectionList = selector.prepareSelection(filterName = filterName)
            ?: return // Exit if nothing found initially

        // Use a mutable copy for the loop
        val currentSelectionList = initialSelectionList.toMutableList()

        // --- Prompt loop --- 
        while (true) {
            // Handle single/empty list cases based on the CURRENT list
            if (currentSelectionList.isEmpty()) {
                println("\nAll listed workflows have been deleted or the list is now empty.")
                break // Exit loop if list becomes empty
            }
            if (currentSelectionList.size == 1) {
                println("\nOnly one workflow remaining in the list:")
                val (num, wf) = currentSelectionList.first()
                println("  #${num}: Name: ${wf.name} Version: ${wf.version}")
                print("Delete this last workflow? [y/N]: ")
                val confirmation = readlnOrNull()?.trim()?.lowercase()
                if (confirmation == "y") {
                    // Pass full details to deleteSpecificVersion, which handles confirmation again
                    deleteSpecificVersion(wf.name, wf.version)
                }
                println("Exiting selection.")
                break // Exit loop after handling the last item
            }

            // Prompt only if size > 1
            print("\nEnter # to delete, * to delete all listed, or q to quit: ")
            val input = readlnOrNull()?.trim()

            when {
                input.isNullOrEmpty() -> {
                    // Blank input: Just continue to re-prompt (list is not re-displayed)
                    continue
                }

                input.equals("q", ignoreCase = true) -> {
                    println("Exiting selection.")
                    break // Exit the loop on 'q'
                }

                input == "*" -> {
                    // Pass the current selection list
                    handleDeleteAllListed(currentSelectionList, filterName)
                    // Exit after attempting bulk action
                    break // Exit loop after handling '*'
                }

                else -> {
                    val choice = input.toIntOrNull()
                    val selectedPair = currentSelectionList.find { it.first == choice }

                    if (selectedPair != null) {
                        val (_, workflowToDelete) = selectedPair // Destructure pair

                        // deleteSpecificVersion handles confirmation internally since force=false
                        deleteSpecificVersion(workflowToDelete.name, workflowToDelete.version)

                        // Assume deletion was successful if confirmDeletion passed inside deleteSpecificVersion
                        // (or add return value to deleteSpecificVersion if more robustness needed)
                        // Remove the item from our local list *after* attempting deletion.
                        currentSelectionList.remove(selectedPair)

                        // Loop continues without re-displaying the list
                    } else {
                        print("Invalid input. ") // Re-prompt in the loop
                    }
                }
            } // End of when
        } // End of while loop
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

    // --- Method ONLY for INTERACTIVE Deletion (called when '*' selected) --- //

    private fun handleDeleteAllListed(selectionList: List<Pair<Int, WorkflowModel>>, filterName: String?) {
        val workflowsToDelete = selectionList.map { it.second } // Extract models
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

                if (deletedCount == count || (filterName == null && deletedCount >= 0)) {
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
            println("Deletion cancelled.")
            return false
        }
        return true
    }

    // Helper for concise exception throwing
    private fun ExecutionException(message: String, cause: Throwable? = null) =
        CommandLine.ExecutionException(CommandLine(this), message, cause)
}

