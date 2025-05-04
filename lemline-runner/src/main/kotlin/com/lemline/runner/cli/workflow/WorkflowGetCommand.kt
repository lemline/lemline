// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.zafarkhaja.semver.Version
import com.lemline.core.workflows.Workflows
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
@Command(name = "get", description = ["Get a specific workflow definition, interactively if needed."])
class WorkflowGetCommand : Runnable {

    // Enum for validated format options (only for final output)
    enum class OutputFormat { JSON, YAML }

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

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
            when {
                // Direct fetch: Both name and version provided
                nameParam != null && versionParam != null ->
                    fetchAndDisplaySpecificWorkflow(nameParam!!, versionParam!!)

                // Interactive selection
                else -> interactiveSelectAndDisplayWorkflow(filterName = nameParam)
            }
        } catch (e: Exception) {
            throw CommandLine.ExecutionException(
                CommandLine(this),
                "ERROR retrieving workflow: ${e.message}",
                e
            )
        }
    }

    private fun fetchAndDisplaySpecificWorkflow(name: String, version: String) {
        val workflowModel = workflowRepository.findByNameAndVersion(name, version)
        if (workflowModel != null) {
            displayWorkflowDefinition(workflowModel)
        } else {
            System.err.println("ERROR: Workflow '$name' version '$version' not found.")
        }
    }

    private fun interactiveSelectAndDisplayWorkflow(filterName: String?) {
        val workflowsToDisplay = when (filterName) {
            null -> workflowRepository.listAll()
            else -> workflowRepository.listByName(filterName)
        }

        if (workflowsToDisplay.isEmpty()) {
            println(
                if (filterName != null) "No workflow '$filterName' found in the database."
                else "No workflows found in the database."
            )
            return
        }

        // If only one result after filtering/fetching, display it directly
        if (workflowsToDisplay.size == 1) {
            displayWorkflowDefinition(workflowsToDisplay.first())
            return
        }

        // Group, sort, and prepare for display
        // Grouping is still useful even if filtered by name, in case of data inconsistency (multiple names returned?)
        // Or just use workflowsToDisplay directly if filterName != null?
        // Let's keep grouping for consistency for now.
        val groupedWorkflows = workflowsToDisplay.groupBy { it.name }.toSortedMap()
        val selectionMap = mutableMapOf<Int, WorkflowModel>()
        var currentNumber = 1

        // Determine column width for alignment
        val maxNameWidth = groupedWorkflows.keys.maxOfOrNull { it?.length ?: 0 } ?: 10 // Handle potential null keys
        val nameHeader = "Name"
        val versionHeader = "Version"
        val numberHeader = "#"
        val numWidth = workflowsToDisplay.size.toString().length // Width for number column
        val paddedNumHeader = numberHeader.padStart(numWidth)
        val paddedNameHeader = nameHeader.padEnd(maxNameWidth)

        // Print header
        println()
        println("$paddedNumHeader  $paddedNameHeader  $versionHeader")
        println("${"-".repeat(numWidth)}  ${"-".repeat(maxNameWidth)}  ${"-".repeat(versionHeader.length)}")

        // Print rows and populate selection map
        groupedWorkflows.forEach { (name, versionsList) ->
            val displayName = name ?: "<Unnamed>" // Handle null names for display
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
        println()

        // Prompt for selection
        print("Enter # of workflow to view (or leave blank to cancel): ")
        while (true) {
            val input = readlnOrNull()?.trim()
            if (input.isNullOrEmpty()) {
                println("Deletion cancelled.")
                return
            }

            val choice = input.toIntOrNull()
            if (choice != null && selectionMap.containsKey(choice)) {
                // Valid selection - display the chosen workflow
                displayWorkflowDefinition(selectionMap[choice]!!)
                return // Done
            }

            print("Invalid input.\nPlease enter a number from the list, or leave blank to cancel: ")
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
