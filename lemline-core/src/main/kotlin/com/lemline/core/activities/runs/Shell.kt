// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Represents a shell command execution with optional arguments and environment variables.
 *
 * @property command The shell command to execute (e.g., "echo", "sh").
 * @property arguments A map of arguments to pass to the command. Keys are argument names, and values are their corresponding values.
 * @property environment A map of environment variables to set for the command execution.
 */
data class Shell(
    val command: String,
    val arguments: Map<String, String>? = null,
    val environment: Map<String, String>? = null
) {

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

        // Add additional arguments from the arguments map
        arguments?.forEach { (key, value) ->
            fullCommand.add(key)
            if (value.isNotBlank()) fullCommand.add(value)
        }

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
    fun executeAsync(): Process {
        val processBuilder = ProcessBuilder()
        processBuilder.command(buildCommandList())

        // Set environment variables if provided
        environment?.let {
            val processEnvironment = processBuilder.environment()
            it.forEach { (key, value) ->
                processEnvironment[key] = value
            }
        }

        // Start the process and return it immediately
        return processBuilder.start()
    }

    /**
     * Executes the shell command synchronously and returns the result.
     *
     * This method:
     * - Starts the process using the built command and environment.
     * - Reads and collects all output from both stdout and stderr.
     * - Waits up to 60 seconds for the process to complete.
     * - Returns a [ProcessResult] containing the exit code, stdout, and stderr.
     *
     * @return [ProcessResult] with the process exit code, standard output, and standard error.
     */
    suspend fun execute(): ProcessResult {
        val process = executeAsync()
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

        // Read standard output
        var line: String?
        while (stdoutReader.readLine().also { line = it } != null) {
            stdout.append(line).append(System.lineSeparator())
        }

        // Read standard error
        while (stderrReader.readLine().also { line = it } != null) {
            stderr.append(line).append(System.lineSeparator())
        }

        // Wait for the process to complete
        withContext(Dispatchers.IO) {
            process.waitFor()
        }

        // Return the result of the process execution
        return ProcessResult(
            code = process.exitValue(),
            stdout = stdout.toString().trim(),
            stderr = stderr.toString().trim()
        )
    }
}
