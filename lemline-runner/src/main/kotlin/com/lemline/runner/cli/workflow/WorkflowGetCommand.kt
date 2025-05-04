// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.zafarkhaja.semver.Version
import com.lemline.core.workflows.Workflows
import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand

// Removed Option for format for now

@Unremovable
@Command(name = "get", description = ["Get a specific workflow definition."])
class WorkflowGetCommand : Runnable {

    // Enum for validated format options
    enum class OutputFormat { JSON, YAML }

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Parameters(
        index = "0",
        arity = "0..1",
        description = ["Optional name of the workflow to get."]
    )
    var nameParam: String? = null

    @Parameters(
        index = "1",
        arity = "0..1",
        description = ["Optional version of the workflow to get."]
    )
    var versionParam: String? = null

    @Option(names = ["--format"], description = ["Output format (\${COMPLETION-CANDIDATES})."], defaultValue = "YAML")
    var format: OutputFormat = OutputFormat.YAML // Use enum type

    // Workflow Command class
    @ParentCommand
    lateinit var parent: WorkflowCommand

    // Removed format option for simplification

    override fun run() {
        // we stop after this command
        parent.parent.daemon = false

        try {
            // 1. Determine the target workflow name
            val targetName = determineName()
                ?: // Appropriate message already printed by determineName
                return

            // 2. Determine the target workflow version
            val targetVersion = determineVersion(targetName)
                ?: // Appropriate message already printed by determineVersion
                return

            // 3. Fetch and display the specific workflow
            val workflowModel = workflowRepository.findByNameAndVersion(targetName, targetVersion)
            if (workflowModel != null) {
                // Format the output based on the selected format
                when (format) {
                    OutputFormat.JSON -> try {
                        val workflow = Workflows.parse(workflowModel.definition)
                        val workflowJson = objectMapper
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(workflow)
                        println(workflowJson)
                    } catch (e: Exception) {
                        System.err.println("ERROR: Unable to parse Workflow '$targetName' version '$targetVersion' definition:\n${workflowModel.definition}")
                    }

                    // YAML is the default format in the database
                    OutputFormat.YAML -> println(workflowModel.definition)
                }
            } else {
                // Should not happen if name/version were selected from existing ones,
                // but could happen due to race condition.
                System.err.println("ERROR: Workflow '$targetName' version '$targetVersion' could not be retrieved.")
            }

        } catch (e: Exception) {
            throw CommandLine.ExecutionException(
                CommandLine(this),
                "ERROR retrieving workflow: ${e.message}",
                e
            )
        }
    }

    private fun determineName(): String? {
        // Name explicitly provided
        if (nameParam != null) return nameParam

        // Name not provided, query distinct names
        val distinctNames = workflowRepository.listAll().map { it.name }.distinct().sorted()

        return when (distinctNames.size) {
            0 -> null.also { println("No workflows found in the database.") }
            1 -> distinctNames.first()
            else -> promptForSelection(distinctNames, "Multiple workflow names found. Please select one:")
        }
    }

    private fun determineVersion(name: String): String? {
        // Version explicitly provided
        if (versionParam != null) return versionParam

        // Version not provided, query available versions for the selected name
        val availableWorkflows = workflowRepository.listByName(name)

        // Sort versions using SemVer
        val sortedWorkflows = availableWorkflows.sortedBy {
            try {
                Version.parse(it.version)
            } catch (e: Exception) {
                Version.parse("0.0.0-invalid")
            }
        }
        val availableVersions = sortedWorkflows.map { it.version } // Extract sorted version strings

        return when (availableVersions.size) {
            1 -> availableVersions.first()
            else -> promptForSelection(availableVersions, "Multiple versions found for '$name'. Please select one:")
        }
    }

    private fun promptForSelection(options: List<String>, promptMessage: String): String? {
        println(promptMessage)
        options.forEachIndexed { index, option ->
            println("  ${index + 1}: $option")
        }
        print("Enter number (or leave blank to cancel): ")

        while (true) {
            val input = readlnOrNull()?.trim()
            if (input.isNullOrEmpty()) {
                println("Selection cancelled.")
                return null
            }

            val choice = input.toIntOrNull()
            if (choice != null && choice in 1..options.size) {
                return options[choice - 1]
            }

            print("Invalid input.\nPlease enter a number between 1 and ${options.size}, or leave blank to cancel: ")
        }
    }
}
