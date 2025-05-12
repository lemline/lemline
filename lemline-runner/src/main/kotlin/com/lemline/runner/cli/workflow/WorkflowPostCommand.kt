// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.workflow

import com.lemline.core.workflows.Workflows
import com.lemline.runner.models.WorkflowModel
import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import java.io.File
import picocli.CommandLine
import picocli.CommandLine.ArgGroup
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Unremovable
@Command(
    name = "post",
    description = ["Create or update workflows from definition files."],
    mixinStandardHelpOptions = true,
)
class WorkflowPostCommand : Runnable {

    // Define an argument group to enforce at least one source is provided
    class FileOrDirectorySource {

        @Option(
            names = ["--file", "-f"],
            description = ["Path to a single workflow definition file."]
        )
        var file: File? = null

        @Option(
            names = ["--directory", "-d"],
            description = ["Path to a directory containing workflow definition files."],
        )
        var directory: File? = null

        @Option(
            names = ["--recursive", "-r"],
            description = ["Walk through folders recursively when using the -d option"],
            defaultValue = "false"
        )
        var recursive: Boolean = false
    }

    @ArgGroup(multiplicity = "1..*", heading = "Workflow Source:%n") // 1..* means at least one
    lateinit var source: FileOrDirectorySource

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Option(
        names = ["--force", "-F"],
        description = ["Override the workflow definition if it already exists."]
    )
    var force: Boolean = false

    override fun run() {

        // Process file if provided
        source.file?.let {
            processSingleFile(it)
        }

        // Process directory if provided
        source.directory?.let {
            processDirectory(it)
        }
    }

    private fun processSingleFile(file: File) {
        if (!file.exists() || !file.isFile) {
            throw CommandLine.ParameterException(
                CommandLine(this),
                "ERROR: Workflow file not found or is not a regular file: ${file.absolutePath}"
            )
        }
        processWorkflowFile(file)
    }

    private fun processDirectory(directory: File) {
        if (!directory.exists() || !directory.isDirectory) {
            throw CommandLine.ParameterException(
                CommandLine(this),
                "ERROR: Workflow directory not found or is not a directory: ${directory.absolutePath}"
            )
        }
        println("Processing files in directory: ${directory.absolutePath}" + if (source.recursive) " (recursively)" else "")

        var filesProcessed = 0
        val filesToProcess = when (source.recursive) {
            true -> directory.walkTopDown().filter { it.isFile }
            false -> directory.listFiles()?.filter { it.isFile }?.asSequence() ?: emptySequence()
        }

        filesToProcess.forEach { file ->
            processWorkflowFile(file)
            filesProcessed++
        }

        if (filesProcessed == 0) {
            println("No files found in directory: ${directory.absolutePath}")
        }
    }

    /**
     * Processes a single workflow definition file.
     */
    private fun processWorkflowFile(file: File) {
        val prefix = "  ->"
        try {
            val content = file.readText()
            val workflow = Workflows.parse(content)
            val model = WorkflowModel.from(workflow)
            val workflowName = "'${model.name}' (version '${model.version}')"
            when (workflowRepository.insert(model)) {
                1 -> println("$prefix Workflow successfully created: $workflowName")

                0 -> when (force) {
                    true -> when (workflowRepository.update(model)) {
                        1 -> println("$prefix Workflow successfully updated: $workflowName")
                        0 -> System.err.println("$prefix Failed to update workflow: $workflowName") // this should not happen
                    }

                    false -> println("$prefix Workflow already exists (use --force to overwrite): $workflowName")
                }
            }
        } catch (e: Exception) {
            // Log error for the specific file but continue if processing a directory
            System.err.println("ERROR processing file '${file.absolutePath}': ${e.message}")
        }
    }
}
