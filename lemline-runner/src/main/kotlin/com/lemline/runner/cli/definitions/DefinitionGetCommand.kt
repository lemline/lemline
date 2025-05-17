// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definitions

import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.core.workflows.Workflows
import com.lemline.runner.cli.GlobalMixin
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
    name = "get",
    description = ["Get specific workflow definitions, interactively if needed."],
)
class DefinitionGetCommand : Runnable {

    @Mixin
    lateinit var mixin: GlobalMixin

    // Enum for validated format options (only for final output)
    enum class OutputFormat { JSON, YAML }

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
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
        names = ["-f", "--format"],
        description = ["Output format for the definition (\${COMPLETION-CANDIDATES})."],
        defaultValue = "YAML"
    )
    var format: OutputFormat = OutputFormat.YAML

    override fun run() {

        try {
            if (name != null && version != null) {
                // Direct fetch: Both name and version provided - runs once and exits
                val selectedWorkflow = definitionRepository.findByNameAndVersion(name!!, version!!)
                if (selectedWorkflow == null) {
                    System.err.println("ERROR: Workflow '$name' version '$version' not found.")
                    return // Exit if direct fetch fails
                }
                displayWorkflowDefinition(selectedWorkflow)
            } else {
                // --- Interactive selection mode ---

                // Prepare selection (displays the list if needed)
                val selectionList = selector.prepareSelection(filterName = name)
                    ?: return // Exit if nothing found

                // Handle a single result directly
                if (selectionList.size == 1) {
                    displayWorkflowDefinition(selectionList.first().second)
                    return // Only one option, so we exit after displaying
                }

                // --- Prompt loop if multiple results ---
                while (true) {
                    print("Enter # to view, or q to quit: ")
                    val input = readlnOrNull()?.trim()

                    when {
                        input.isNullOrEmpty() -> {
                            // Blank input: Redisplay the list and re-prompt
                            selector.prepareSelection(filterName = name) ?: break
                            continue
                        }

                        input.equals("q", ignoreCase = true) -> {
                            // quit loop, and exit
                            break
                        }

                        else -> {
                            val choice = input.toIntOrNull()
                            val selectedPair = selectionList.find { it.first == choice }

                            if (selectedPair != null) {
                                val workflowToDisplay = selectedPair.second
                                displayWorkflowDefinition(workflowToDisplay)
                            } else {
                                println("Invalid input. ")
                                println()
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
    private fun displayWorkflowDefinition(definitionModel: DefinitionModel) = when (format) {
        // Re-parse the stored definition to ensure it's valid before serializing
        OutputFormat.JSON -> try {
            val workflow = Workflows.parse(definitionModel.definition)
            val workflowJson = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(workflow)
            println(workflowJson)
        } catch (_: Exception) {
            System.err.println(
                "ERROR: Unable to parse and format Workflow '${definitionModel.name}' version '${definitionModel.version}' as JSON. Stored definition might be invalid:\n" +
                    definitionModel.definition
            )
        }

        // Assuming stored definition is already valid YAML
        OutputFormat.YAML -> println(definitionModel.definition)
    }
}
