// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definitions

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.cli.common.InteractiveWorkflowSelector
import com.lemline.runner.cli.exceptions.CliException
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionService
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Unremovable
@ExperimentalTime
@ExperimentalSerializationApi
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
open class DefinitionDeleteCommand : Runnable {

    @Mixin
    lateinit var mixin: GlobalMixin

    @Inject
    lateinit var definitionService: DefinitionService

    @Inject // Inject the selector
    lateinit var selector: InteractiveWorkflowSelector

    @Parameters(
        index = "0",
        arity = "1",
        description = ["Namespace of the workflows to delete."]
    )
    var namespace: String? = null

    @Parameters(
        index = "1",
        arity = "0..1",
        description = ["Optional name of the workflow to delete."]
    )
    var name: String? = null

    @Parameters(
        index = "2",
        arity = "0..1",
        description = ["Optional version of the workflow to delete."]
    )
    var version: String? = null

    @Option(
        names = ["--force", "-F"],
        description = ["Force deletion without confirmation, using provided args for scope."],
        defaultValue = "false"
    )
    var force: Boolean = false

    val workflowNamespace by lazy {
        namespace?.let { WorkflowNamespace(it) } ?: cliError("Workflow namespace must be provided")
    }
    val workflowName by lazy { name?.let { WorkflowName(it) } }
    val workflowVersion by lazy { version?.let { WorkflowVersion(it) } }

    override fun run() = runBlocking {
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

    private suspend fun handleForcedDeletion() {
        when {
            name == null -> deleteAllWorkflowsInNamespace()
            version == null -> deleteAllVersionsByNameInNamespace(workflowName!!)
            else -> deleteSpecificVersion(workflowName!!, workflowVersion!!)
        }
    }

    private suspend fun handleInteractiveDeletion() {
        // --- Prepare and display list ONCE ---
        var currentSelectionList = selector.prepareSelection(workflowNamespace, workflowName)?.toMutableList()
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
                    currentSelectionList =
                        selector.prepareSelection(workflowNamespace, workflowName)?.toMutableList() ?: break
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

    // --- Methods used for BOTH Forced & Interactive Deletion --- //

    private suspend fun deleteAllWorkflowsInNamespace() {
        // Preview count for confirmation
        val workflowCount = definitionService.countAllInNamespace(workflowNamespace)
        if (workflowCount == 0L) {
            println("No workflows found to delete.")
            return
        }
        if (!confirmDeletion("ALL $workflowCount workflows")) return

        when (val result = definitionService.deleteAllInNamespace(workflowNamespace)) {
            is DefinitionService.DeleteResult.Deleted ->
                println("Successfully deleted ${result.count} workflows." + if (force) " (forced)" else "")
            is DefinitionService.DeleteResult.NotFound ->
                println(result.message)
        }
    }

    private suspend fun deleteAllVersionsByNameInNamespace(workflowName: WorkflowName): Boolean {
        // Preview versions for confirmation
        val workflowsToDelete = definitionService.listByName(workflowNamespace, workflowName)
        if (workflowsToDelete.isEmpty()) {
            println("No workflows found with name '$workflowName'.")
            return false
        }
        val versionsString = workflowsToDelete.joinToString { it.version.toString() }
        if (!confirmDeletion("all ${workflowsToDelete.size} versions ($versionsString) of workflow '$workflowName'")) {
            return false
        }

        return when (val result = definitionService.deleteAllVersions(workflowNamespace, workflowName)) {
            is DefinitionService.DeleteResult.Deleted -> {
                println("Successfully deleted ${result.details}." + if (force) " (forced)" else "")
                true
            }
            is DefinitionService.DeleteResult.NotFound -> {
                println(result.message)
                false
            }
        }
    }

    private suspend fun deleteSpecificVersion(name: WorkflowName, version: WorkflowVersion): Boolean {
        // Preview for confirmation
        if (definitionService.findByNameAndVersion(workflowNamespace, name, version) == null) {
            println("Workflow '$name' version '$version' not found.")
            return false
        }
        if (!confirmDeletion("workflow '$name' version '$version'")) return false

        return when (val result = definitionService.delete(workflowNamespace, name, version)) {
            is DefinitionService.DeleteResult.Deleted -> {
                println("Successfully deleted ${result.details}." + if (force) " (forced)" else "")
                true
            }
            is DefinitionService.DeleteResult.NotFound -> {
                System.err.println("Warning: ${result.message}")
                false
            }
        }
    }

    // --- Method ONLY for INTERACTIVE Deletion (called when '*' selected) --- //
    private suspend fun handleDeleteAllListed(
        selectionList: List<Pair<Int, DefinitionModel>>,
        filterName: String?
    ): Boolean {
        val workflowsToDelete = selectionList.map { it.second } // Extract models
        val count = workflowsToDelete.size
        val subject = if (filterName != null) {
            val versionsString = workflowsToDelete.joinToString { it.version.toString() }
            "all $count listed versions ($versionsString) of workflow '$filterName'"
        } else {
            "all $count listed workflows"
        }

        if (!confirmDeletion(subject)) return false

        try {
            val result = if (filterName == null) {
                // If no filter was applied, '*' means delete absolutely all
                definitionService.deleteAllInNamespace(workflowNamespace)
            } else {
                // If a name filter was applied, '*' means delete all versions of that name
                definitionService.deleteAllVersions(workflowNamespace, WorkflowName(filterName))
            }

            return when (result) {
                is DefinitionService.DeleteResult.Deleted -> {
                    println("Successfully deleted ${result.count} workflow(s) as requested by '*' selection.")
                    true
                }
                is DefinitionService.DeleteResult.NotFound -> {
                    println(result.message)
                    false
                }
            }
        } catch (e: Exception) {
            System.err.println("ERROR deleting selected workflows: ${e.message}")
            return false
        }
    }

    // --- Common Helper --- //

    // Made protected to enable testing through inheritance
    protected fun confirmDeletion(subjectDescription: String): Boolean {
        // This check makes confirmation a no-op if force is true
        if (force) return true

        print("Are you sure you want to delete $subjectDescription? [y/N]: ")
        val confirmation = readUserInput()?.trim()?.lowercase()
        if (confirmation != "y") {
            println("Deletion cancelled.")
            return false
        }
        return true
    }

    // Made protected and extracted to support testing through override
    protected open fun readUserInput(): String? = readlnOrNull()

    // Helper for concise exception throwing
    private fun ExecutionException(message: String, cause: Throwable? = null) =
        CommandLine.ExecutionException(CommandLine(this), message, cause)

    internal fun cliError(msg: String): Nothing = throw CliException(msg)

}
