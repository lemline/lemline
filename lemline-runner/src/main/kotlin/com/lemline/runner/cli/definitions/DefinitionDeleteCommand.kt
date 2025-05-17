// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definitions

import com.lemline.runner.cli.LemlineMixin
import com.lemline.runner.cli.common.InteractiveWorkflowSelector
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Unremovable
@Command(
    name = "delete",
    description = [
        "Deletes workflow definition.",
        "- Interactively selects one or all (*) listed workflows if name/version are omitted.",
        "- Requires confirmation unless --force is used.",
        "--force options determine scope:",
        "  - No args: Deletes ALL workflows.",
        "  - <name>: Deletes all versions of the workflow with the specified name.",
        "  - <name> <version>: Deletes the specific workflow version."
    ],
)
class DefinitionDeleteCommand : Runnable {

    @Mixin
    lateinit var mixin: LemlineMixin

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    @Inject // Inject the selector
    lateinit var selector: InteractiveWorkflowSelector

    @Parameters(
        index = "0",
        arity = "0..1",
        description = ["Optional name of the workflow to get directly."]
    )
    var name: String? = null

    @Parameters(
        index = "1",
        arity = "0..1",
        description = ["Optional version of the workflow (requires name)."]
    )
    var version: String? = null

    @Option(
        names = ["--force", "-F"],
        description = ["Force deletion without confirmation, using provided args for scope."],
        defaultValue = "false"
    )
    var force: Boolean = false

    override fun run() {

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
        when {
            name == null -> deleteAllWorkflows()
            version == null -> deleteAllVersionsByName(name!!)
            else -> deleteSpecificVersion(name!!, version!!)
        }
    }

    private fun handleInteractiveDeletion() {

        // --- Prepare and display list ONCE ---
        var currentSelectionList = selector.prepareSelection(filterName = name)?.toMutableList()
            ?: return // Exit if nothing found initially

        // --- Prompt loop ---
        while (true) {
            // Handle single/empty list cases based on the CURRENT list
            if (currentSelectionList.isEmpty()) {
                println("\nAll listed workflows have been deleted or the list is now empty.")
                break // Exit loop if the list becomes empty
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
                // Exit loop after handling the last item
                break
            }

            // Prompt only if size > 1
            print("\nEnter # to delete, * to delete all listed, or q to quit: ")
            val input = readlnOrNull()?.trim()

            when {
                input.isNullOrEmpty() -> {
                    // Blank input: Redisplay the list and re-prompt
                    currentSelectionList = selector.prepareSelection(filterName = name)?.toMutableList() ?: break
                    continue
                }

                input.equals("q", ignoreCase = true) -> {
                    break // Exit the loop on 'q'
                }

                input == "*" -> {
                    // Pass the current selection list
                    if (handleDeleteAllListed(currentSelectionList, filterName = name)) break
                    // canceled the delete request
                    continue
                }

                else -> {
                    val choice = input.toIntOrNull()
                    val selectedPair = currentSelectionList.find { it.first == choice }

                    if (selectedPair != null) {
                        val (_, workflowToDelete) = selectedPair // Destructure pair

                        // deleteSpecificVersion handles confirmation internally since force=false
                        if (deleteSpecificVersion(workflowToDelete.name, workflowToDelete.version)) {
                            // Remove the item from our local list *after* attempting deletion.
                            currentSelectionList.remove(selectedPair)
                        }
                        // Loop continues without re-displaying the list
                    } else {
                        print("Invalid input. ") // Re-prompt in the loop
                    }
                }
            }
        }
    }

    // --- Methods used for BOTH Forced & Intercative Deletion --- //

    private fun deleteAllWorkflows() {
        val workflowCount = definitionRepository.count()
        if (workflowCount == 0L) {
            println("No workflows found to delete.")
            return
        }
        val subject = "ALL $workflowCount workflows"
        if (!confirmDeletion(subject)) return

        val deletedCount = definitionRepository.deleteAll()
        println("Successfully deleted $deletedCount workflows." + if (force) " (forced)" else "")
    }

    private fun deleteAllVersionsByName(name: String): Boolean {
        val workflowsToDelete = definitionRepository.listByName(name)
        if (workflowsToDelete.isEmpty()) {
            println("No workflows found with name '$name'.")
            return false
        }
        val versionsString = workflowsToDelete.joinToString { it.version }
        val subject = "all ${workflowsToDelete.size} versions ($versionsString) of workflow '$name'"
        if (!confirmDeletion(subject)) return false

        val deletedCount = definitionRepository.delete(workflowsToDelete)
        if (deletedCount == workflowsToDelete.size) {
            println("Successfully deleted $deletedCount versions of workflow '$name'." + if (force) " (forced)" else "")
            return true
        } else {
            System.err.println("Warning: Expected to delete ${workflowsToDelete.size} workflows, but deleted $deletedCount.")
            return false
        }
    }

    private fun deleteSpecificVersion(name: String, version: String): Boolean {
        val workflowToDelete = definitionRepository.findByNameAndVersion(name, version)
            ?: run {
                println("Workflow '$name' version '$version' not found.")
                return false
            }

        val subject = "workflow '$name' version '$version'"
        if (!confirmDeletion(subject)) return false // confirmDeletion handles 'force'

        val deletedCount = definitionRepository.delete(workflowToDelete)
        if (deletedCount == 1) {
            println("Successfully deleted workflow '$name' version '$version'." + if (force) " (forced)" else "")
            return true
        } else {
            System.err.println("Warning: Expected to delete '$name' version '$version'. Deleted concurrently?")
            return false
        }
    }

    // --- Method ONLY for INTERACTIVE Deletion (called when '*' selected) --- //
    private fun handleDeleteAllListed(selectionList: List<Pair<Int, DefinitionModel>>, filterName: String?): Boolean {
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
                    definitionRepository.deleteAll()
                } else {
                    // If a name filter was applied, '*' means delete all versions of that name
                    definitionRepository.delete(workflowsToDelete)
                }

                if (deletedCount == count) {
                    println("Successfully deleted $deletedCount workflow(s) as requested by '*' selection.")
                    return true
                } else {
                    System.err.println("Warning: Expected to delete $count workflow(s) via '*' selection, but repository reported $deletedCount deleted.")
                }
            } catch (e: Exception) {
                System.err.println("ERROR deleting selected workflows: ${e.message}")
            }
        }

        return false
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
