// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.core.workflows.Workflows
import com.lemline.runner.cli.common.InteractiveWorkflowSelector
import com.lemline.runner.models.WorkflowModel
import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand

@Unremovable
@Command(name = "get", description = ["Get specific workflow definitions, interactively if needed."])
class WorkflowGetCommand : Runnable {

    // Enum for validated format options (only for final output)
    enum class OutputFormat { JSON, YAML }

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var selector: InteractiveWorkflowSelector

    @Parameters(
        index = "0",
        arity = "0..1",
        description = ["Optional name of the workflow to get directly."]
    )
    var nameParam: String? = null

    @Parameters(
        index = "1",
        arity = "0..1",
        description = ["Optional version of the workflow (requires name)."]
    )
    var versionParam: String? = null

    @Option(
        names = ["--format"],
        description = ["Output format for the definition (\${COMPLETION-CANDIDATES})."],
        defaultValue = "YAML"
    )
    var format: OutputFormat = OutputFormat.YAML

    @ParentCommand
    lateinit var parent: WorkflowCommand

    override fun run() {
        parent.parent.daemon = false // Stop after execution

        try {
            if (nameParam != null && versionParam != null) {
                // Direct fetch: Both name and version provided - runs once and exits
                val selectedWorkflow = workflowRepository.findByNameAndVersion(nameParam!!, versionParam!!)
                if (selectedWorkflow == null) {
                    System.err.println("ERROR: Workflow '$nameParam' version '$versionParam' not found.")
                    return // Exit if direct fetch fails
                }
                displayWorkflowDefinition(selectedWorkflow)
            } else {
                // --- Interactive selection mode --- 

                // Prepare selection (displays list if needed)
                val selectionList = selector.prepareSelection(filterName = nameParam)
                    ?: return // Exit if nothing found

                // Handle single result directly
                if (selectionList.size == 1) {
                    displayWorkflowDefinition(selectionList.first().second) // Get model from the pair
                    return // Only one option, so we exit after displaying
                }

                // --- Prompt loop if multiple results --- 
                while (true) {
                    print("\nEnter # to view, or q to quit: ")
                    val input = readlnOrNull()?.trim()

                    when {
                        input.isNullOrEmpty() -> {
                            // Blank input: Just continue to re-prompt (list is not re-displayed)
                            continue
                        }

                        input.equals("q", ignoreCase = true) -> {
                            println("Exiting selection.")
                            break
                        }

                        else -> {
                            val choice = input.toIntOrNull()
                            val selectedPair = selectionList.find { it.first == choice }

                            if (selectedPair != null) {
                                val workflowToDisplay = selectedPair.second
                                println("\n--- Displaying selected workflow (#${selectedPair.first}) ---")
                                displayWorkflowDefinition(workflowToDisplay)
                                println("--- End of definition ---")
                            } else {
                                print("Invalid input. ")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw CommandLine.ExecutionException(
                CommandLine(this),
                "ERROR retrieving workflow: ${e.message}",
                e
            )
        }
    }

    /**
     * Displays the definition of a given workflow model according to the selected format.
     */
    private fun displayWorkflowDefinition(workflowModel: WorkflowModel) = when (format) {
        // Re-parse the stored definition to ensure it's valid before serializing
        OutputFormat.JSON -> try {
            val workflow = Workflows.parse(workflowModel.definition)
            val workflowJson = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(workflow)
            println(workflowJson)
        } catch (e: Exception) {
            System.err.println(
                "ERROR: Unable to parse and format Workflow '${workflowModel.name}' version '${workflowModel.version}' as JSON. Stored definition might be invalid:\n" +
                    workflowModel.definition
            )
        }

        // Assuming stored definition is already valid YAML
        OutputFormat.YAML -> println(workflowModel.definition)
    }
}
