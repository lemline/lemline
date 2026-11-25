// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definitions

import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionService
import com.lemline.runner.definitions.DefinitionService.SaveResult
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import java.io.File
import java.util.concurrent.Callable
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.ExitCode
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

@Unremovable
@Command(
    name = "post",
    description = ["Create or update workflows from definition files."],
)
class DefinitionPostCommand : Callable<Int> {

    @Mixin
    lateinit var mixin: GlobalMixin

    @Option(
        names = ["--file", "-f"],
        description = ["Path to a workflow definition file."],
        required = false
    )
    var files: List<File> = emptyList()

    @Option(
        names = ["--directory", "-d"],
        description = ["Path to a directory containing workflow definition files."],
        required = false
    )
    var directories: List<File> = emptyList()

    @Option(
        names = ["--recursive", "-r"],
        description = ["Walk through directories recursively."],
        defaultValue = "false"
    )
    var recursive: Boolean = false

    @Inject
    lateinit var definitionService: DefinitionService

    @Option(
        names = ["--force", "-F"],
        description = ["Override a workflow definition if it already exists."]
    )
    var force: Boolean = false

    override fun call(): Int = runBlocking {

        // Validate that the recursive option is used with directories
        if (recursive && directories.isEmpty()) {
            throw CommandLine.ParameterException(
                CommandLine(this@DefinitionPostCommand),
                "The --recursive option can only be used with the --directory option."
            )
        }

        // Ensure that at least one source is provided
        if (files.isEmpty() && directories.isEmpty()) {
            throw CommandLine.ParameterException(
                CommandLine(this@DefinitionPostCommand),
                "You must specify at least one file (-f) or directory (-d)"
            )
        }

        var successful = true
        files.forEach { file ->
            if (!processSingleFile(file)) successful = false
        }
        directories.forEach { directory ->
            if (!processDirectory(directory)) successful = false
        }
        if (successful) ExitCode.OK else ExitCode.SOFTWARE
    }

    private suspend fun processSingleFile(file: File): Boolean {
        if (!file.exists()) {
            throw CommandLine.ParameterException(
                CommandLine(this@DefinitionPostCommand),
                "The specified file does not exist: ${file.absolutePath}"
            )
        }
        if (!file.isFile) {
            throw CommandLine.ParameterException(
                CommandLine(this@DefinitionPostCommand),
                "The specified path is not a regular file: ${file.absolutePath}"
            )
        }
        return processWorkflowFile(file)
    }

    internal suspend fun processDirectory(directory: File): Boolean {
        if (!directory.exists()) {
            throw CommandLine.ParameterException(
                CommandLine(this@DefinitionPostCommand),
                "The specified directory does not exist: ${directory.absolutePath}"
            )
        }
        if (!directory.isDirectory) {
            throw CommandLine.ParameterException(
                CommandLine(this@DefinitionPostCommand),
                "The specified path is not a directory: ${directory.absolutePath}"
            )
        }
        println("Processing files in directory: ${directory.absolutePath}" + if (recursive) " (recursively)" else "")

        var successful = true
        var filesProcessed = 0
        val filesToProcess = when (recursive) {
            true -> directory.walkTopDown().filter { it.isFile }
            false -> directory.listFiles()?.filter { it.isFile }?.asSequence() ?: emptySequence()
        }

        filesToProcess.forEach { file ->
            if (!processWorkflowFile(file)) successful = false
            filesProcessed++
        }

        if (filesProcessed == 0) {
            println("No files found in directory: ${directory.absolutePath}")
        }
        return successful
    }

    /**
     * Processes a single workflow definition file.
     */
    internal suspend fun processWorkflowFile(file: File): Boolean {
        val prefix = "  ->"
        return try {
            val content = file.readText()
            val model = DefinitionModel.from(content)
            val workflowName = "'${model.name}' (version '${model.version}')"

            val result = definitionService.save(model, force)
            when (result) {
                SaveResult.CREATED -> println("$prefix Workflow successfully created: $workflowName")
                SaveResult.UPDATED -> println("$prefix Workflow successfully updated: $workflowName")
                SaveResult.ALREADY_EXISTS -> println("$prefix Workflow already exists (use --force to overwrite): $workflowName")
                SaveResult.FAILED -> System.err.println("$prefix Failed to save workflow: $workflowName")
            }
            result != SaveResult.FAILED
        } catch (e: Exception) {
            System.err.println("ERROR processing file '${file.absolutePath}': ${e.message}")
            false
        }
    }
}
