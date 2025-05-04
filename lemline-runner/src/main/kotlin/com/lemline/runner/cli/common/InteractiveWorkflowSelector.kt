// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.common

import com.github.zafarkhaja.semver.Version
import com.lemline.runner.models.WorkflowModel
import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
@Unremovable
class InteractiveWorkflowSelector @Inject constructor(
    private val workflowRepository: WorkflowRepository
) {

    /**
     * Fetches workflows (optionally filtered by name), sorts them, formats them into
     * a numbered list grouped by name, prints the list,
     * and returns the list of pairs (number, WorkflowModel) for selection.
     * Returns null if no workflows are found.
     */
    fun prepareSelection(filterName: String? = null): List<Pair<Int, WorkflowModel>>? {
        val workflows = if (filterName != null) {
            workflowRepository.listByName(filterName)
        } else {
            workflowRepository.listAll()
        }

        if (workflows.isEmpty()) {
            println(if (filterName != null) "No workflows found matching name '$filterName'." else "No workflows found.")
            return null
        }

        // Group by name and sort versions using SemVer within each group
        val groupedAndSorted = workflows
            .groupBy { it.name }
            .entries
            .sortedBy { it.key } // Sort groups by name
            .associate { (name, versions) -> // Use associate for Map<String, List<WorkflowModel>>
                name to versions.sortedWith(compareBy { runCatching { Version.parse(it.version) }.getOrNull() })
            }

        // --- Manually build and print the list --- 
        val selectionList = mutableListOf<Pair<Int, WorkflowModel>>()
        var currentNumber = 1
        val maxNameWidth = (groupedAndSorted.keys.maxOfOrNull { it.length } ?: 10).coerceAtLeast(4)
        val nameHeader = "Name"
        val versionHeader = "Version"
        val numberHeader = "#"
        // Calculate width needed for numbers (at least 1)
        val numWidth = workflows.size.toString().length.coerceAtLeast(1)
        val paddedNumHeader = numberHeader.padStart(numWidth)
        val paddedNameHeader = nameHeader.padEnd(maxNameWidth)

        println() // Blank line before header
        println("$paddedNumHeader  $paddedNameHeader  $versionHeader")
        println("${"-".repeat(numWidth)}  ${"-".repeat(maxNameWidth)}  ${"-".repeat(versionHeader.length)}")

        groupedAndSorted.forEach { (name, versionsList) ->
            versionsList.forEachIndexed { index, workflow ->
                val versionPart = workflow.version
                val numberPart = currentNumber.toString().padStart(numWidth)
                selectionList.add(currentNumber to workflow)
                currentNumber++

                val namePart = if (index == 0) name.padEnd(maxNameWidth) else " ".repeat(maxNameWidth)
                val prefix = when {
                    versionsList.size == 1 -> ""
                    index == versionsList.size - 1 -> "└─" // Use box drawing characters
                    index > 0 -> "├─" // Use box drawing characters
                    else -> "" // First item in a group of more than one, no prefix needed
                }

                println("$numberPart  $namePart  $prefix $versionPart")
            }
        }
        println() // Blank line after list
        // --- End of manual list building --- 

        return selectionList
    }
} 
