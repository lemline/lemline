// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.github.zafarkhaja.semver.Version
import com.lemline.runner.models.WorkflowModel
import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand

@Unremovable
@Command(
    name = "list",
    description = ["List workflows stored in the database."],
    mixinStandardHelpOptions = true
)
class WorkflowListCommand : Runnable {

    private val logger = LoggerFactory.getLogger(WorkflowListCommand::class.java)

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Parameters(index = "0", arity = "0..1", description = ["Optional name of the workflow to list versions for."])
    var workflowName: String? = null

    @ParentCommand
    lateinit var parent: WorkflowCommand

    override fun run() {
        // we stop after this command
        parent.parent.daemon = false

        val workflows: List<WorkflowModel> = when (val name = workflowName) {
            null -> workflowRepository.listAll()
            else -> workflowRepository.listByName(name)
        }

        if (workflows.isEmpty()) {
            println("No workflows found" + (workflowName?.let { " matching name '$it'" } ?: "") + ".")
            return
        }

        // Group workflows by name
        val groupedWorkflows = workflows.groupBy { it.name }.toSortedMap()

        // Determine column width for alignment
        val maxNameWidth = groupedWorkflows.keys.maxOf { it.length } // Find max length of names
        val nameHeader = "Name"
        val versionHeader = "Version"
        val paddedNameHeader = nameHeader.padEnd(maxNameWidth)

        // Print header
        println("$paddedNameHeader  $versionHeader")
        println("${"-".repeat(maxNameWidth)}  ${"-".repeat(versionHeader.length)}")

        // Print rows with markers for subsequent versions
        groupedWorkflows.forEach { (name, versionsList) ->
            // Sort versions using the java-semver library
            val sortedVersions = versionsList.sortedBy { workflow ->
                try {
                    Version.parse(workflow.version) // Parse using the library
                } catch (e: Exception) {
                    // Handle cases where a version string is not valid SemVer
                    logger.warn("Could not parse version '${workflow.version}' for workflow '$name' as SemVer. Treating as lowest precedence.")
                    // Return a version that sorts lower than valid versions
                    Version.parse("0.0.0-invalid")
                }
            }

            sortedVersions.forEachIndexed { index, workflow ->
                val versionPart = workflow.version
                if (index == 0) {
                    // First version for this name
                    val namePart = name.padEnd(maxNameWidth)
                    println("$namePart  $versionPart")
                } else {
                    // Subsequent versions for this name
                    val namePart = " ".repeat(maxNameWidth) // Blank space for name column alignment
                    val marker = if (index == versionsList.lastIndex) "└─" else "├─"
                    println("$namePart  $marker $versionPart")
                }
            }
        }
    }
}
