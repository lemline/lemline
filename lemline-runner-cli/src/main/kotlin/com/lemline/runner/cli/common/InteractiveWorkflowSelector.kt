// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.common

import com.github.zafarkhaja.semver.Version
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionService
import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
@Unremovable
class InteractiveWorkflowSelector @Inject constructor(
    private val definitionService: DefinitionService
) {
    /**
     * Fetches workflows (optionally filtered by namespace and/or name), sorts them, formats them into
     * a numbered list grouped by namespace and name, prints the list, stores the formatted list,
     * and returns the list of pairs (number, WorkflowModel) for selection.
     * Returns null if no workflows are found.
     */
    suspend fun prepareSelection(
        workflowNamespace: WorkflowNamespace? = null,
        filterName: WorkflowName? = null
    ): List<Pair<Int, DefinitionModel>>? {
        val workflows = when {
            workflowNamespace != null && filterName != null ->
                definitionService.listByName(workflowNamespace, filterName)

            workflowNamespace != null ->
                definitionService.listAllInNamespace(workflowNamespace)

            else ->
                definitionService.listAll()
        }

        if (workflows.isEmpty()) {
            println(filterName?.let { "No workflows found matching name '$it'." } ?: "No workflows found.")
            return null
        }

        // Group by namespace+name and sort versions using SemVer within each group
        val groupedAndSorted = workflows
            .groupBy { "${it.namespace}/${it.name}" }
            .entries
            .sortedBy { it.key } // Sort groups by namespace/name
            .associate { (key, versions) ->
                key to versions.sortedWith(compareBy { runCatching { Version.parse(it.version.toString()) }.getOrNull() })
            }

        // Generate the selection list (needed for return value)
        val selectionList = buildSelectionList(groupedAndSorted)

        // Format, store, and print the list
        formatAndPrintList(groupedAndSorted, selectionList)

        return selectionList
    }

    /**
     * Builds the list of (number, WorkflowModel) pairs based on the grouped data.
     * This is separated so prepareSelection can return it while formatting happens elsewhere.
     */
    private fun buildSelectionList(groupedData: Map<String, List<DefinitionModel>>): List<Pair<Int, DefinitionModel>> {
        val selectionList = mutableListOf<Pair<Int, DefinitionModel>>()
        var currentNumber = 1
        groupedData.values.flatten().forEach { // Simple flatten and iterate to assign numbers
            selectionList.add(currentNumber to it)
            currentNumber++
        }
        return selectionList
    }

    /**
     * Formats the workflow list into an ASCII table string, stores it,
     * and prints it to the console.
     */
    private fun formatAndPrintList(
        groupedData: Map<String, List<DefinitionModel>>,
        selectionList: List<Pair<Int, DefinitionModel>>
    ) {
        val output = StringBuilder()

        // Calculate column widths based on actual data
        val allWorkflows = groupedData.values.flatten()
        val maxNamespaceWidth = (allWorkflows.maxOfOrNull { it.namespace.toString().length } ?: 9).coerceAtLeast(9)
        val maxNameWidth = (allWorkflows.maxOfOrNull { it.name.toString().length } ?: 4).coerceAtLeast(4)

        val namespaceHeader = "Namespace"
        val nameHeader = "Name"
        val versionHeader = "Version"
        val numberHeader = "#"
        val numWidth = selectionList.size.toString().length.coerceAtLeast(1)
        val paddedNumHeader = numberHeader.padStart(numWidth)
        val paddedNamespaceHeader = namespaceHeader.padEnd(maxNamespaceWidth)
        val paddedNameHeader = nameHeader.padEnd(maxNameWidth)

        output.appendLine() // Blank line before header
        output.appendLine("$paddedNumHeader  $paddedNamespaceHeader  $paddedNameHeader  $versionHeader")
        output.appendLine(
            "${"-".repeat(numWidth)}  ${"-".repeat(maxNamespaceWidth)}  ${"-".repeat(maxNameWidth)}  ${
                "-".repeat(
                    versionHeader.length
                )
            }"
        )

        var itemIndex = 0 // Use index from selectionList to get correct number
        groupedData.forEach { (_, versionsList) ->
            versionsList.forEachIndexed { index, workflow ->
                val (currentNumber, _) = selectionList[itemIndex]
                val versionPart = workflow.version
                val numberPart = currentNumber.toString().padStart(numWidth)
                itemIndex++

                val namespacePart =
                    if (index == 0) workflow.namespace.toString().padEnd(maxNamespaceWidth) else " ".repeat(
                        maxNamespaceWidth
                    )
                val namePart =
                    if (index == 0) workflow.name.toString().padEnd(maxNameWidth) else " ".repeat(maxNameWidth)
                val prefix = when {
                    versionsList.size == 1 -> ""
                    index == versionsList.size - 1 -> "└─"
                    index > 0 -> "├─"
                    else -> ""
                }

                output.appendLine("$numberPart  $namespacePart  $namePart  $prefix $versionPart")
            }
        }

        println(output.toString())
    }
}
