// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.tasks.runs

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/**
 * Represents a shell command execution with optional arguments and environment variables.
 *
 * @property command The shell command to execute (e.g., "echo", "sh").
 * @property arguments Arguments in order, preserving duplicates and empty strings.
 * @property environment A map of environment variables to set for the command execution.
 */
data class Shell(
    val command: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String>? = null,
    val stdin: String? = null,
) {

    companion object {
        // Pipe reads block. An elastic IO view prevents concurrent processes from
        // exhausting the shared IO limit while their other pipe readers are queued.
        private val pipeDispatcher = Dispatchers.IO.limitedParallelism(Int.MAX_VALUE)
    }

    /**
     * Parses a command string into a list of arguments, handling quoted strings.
     *
     * This method splits the input command string into individual arguments, respecting quoted sections.
     * Quoted strings are treated as a single argument, and spaces outside quotes are used as delimiters.
     *
     * @param input The command string to parse. Defaults to the `command` property of the class.
     * @return A list of strings representing the parsed arguments.
     */
    internal fun parseCommand(input: String = this.command): List<String> {
        if (input.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        var inQuotes = false
        var currentArg = StringBuilder()

        input.forEach { c ->
            when {
                c == '\"' -> inQuotes = !inQuotes
                c == ' ' && !inQuotes -> {
                    if (currentArg.isNotEmpty()) {
                        result.add(currentArg.toString())
                        currentArg = StringBuilder()
                    }
                }

                else -> currentArg.append(c)
            }
        }

        if (currentArg.isNotEmpty()) {
            result.add(currentArg.toString())
        }

        return result
    }

    /**
     * Builds the full command list by combining the parsed command and additional arguments.
     *
     * @return List containing the full command and all its arguments.
     */
    private fun buildCommandList(): List<String> {
        val fullCommand = parseCommand(command).toMutableList()

        fullCommand.addAll(arguments)

        return fullCommand
    }

    /**
     * Executes the shell command asynchronously.
     *
     * This method prepares a `ProcessBuilder` with the full command and any provided environment variables,
     * then starts the process and returns the resulting [Process] object immediately.
     * The caller is responsible for managing the process lifecycle (e.g., waiting for completion, reading output).
     *
     * @return The [Process] object representing the running shell command.
     */
    fun executeAsync(discardOutput: Boolean = false): Process {
        val processBuilder = ProcessBuilder()
        processBuilder.command(buildCommandList())

        // Set environment variables if provided
        environment?.let {
            val processEnvironment = processBuilder.environment()
            it.forEach { (key, value) ->
                processEnvironment[key] = value
            }
        }

        if (discardOutput) {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)
        }
        // Redirect input from a file so await:false also receives stdin without blocking
        // on a child that writes output before reading its input.
        val inputFile = stdin?.let { Files.createTempFile("lemline-shell-", ".stdin") }
        try {
            inputFile?.let {
                Files.writeString(it, stdin)
                processBuilder.redirectInput(it.toFile())
            }
            return processBuilder.start().also { process ->
                process.outputStream.close()
                inputFile?.let { file -> process.onExit().whenComplete { _, _ -> Files.deleteIfExists(file) } }
            }
        } catch (e: Exception) {
            inputFile?.let { Files.deleteIfExists(it) }
            throw e
        }
    }

    /**
     * Executes the shell command synchronously and returns the result.
     *
     * This method:
     * - Starts the process using the built command and environment.
     * - Reads and collects all output from both stdout and stderr.
     * - Supplies standard input and drains both output streams concurrently.
     * - Returns a [ProcessResult] containing the exit code, stdout, and stderr.
     *
     * @return [ProcessResult] with the process exit code, standard output, and standard error.
     */
    suspend fun execute(): ProcessResult = withContext(pipeDispatcher) {
        coroutineScope {
            val process = executeAsync()
            try {
                val stdout = async { process.inputStream.bufferedReader().use { it.readText().trim() } }
                val stderr = async { process.errorStream.bufferedReader().use { it.readText().trim() } }
                process.onExit().await()
                ProcessResult(code = process.exitValue(), stdout = stdout.await(), stderr = stderr.await())
            } finally {
                process.destroy()
            }
        }
    }
}
