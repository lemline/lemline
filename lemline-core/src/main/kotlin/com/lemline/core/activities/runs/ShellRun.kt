// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Represents a shell command execution with optional arguments and environment variables.
 *
 * @property command The shell command to execute (e.g., "echo", "sh").
 * @property arguments A map of arguments to pass to the command. Keys are argument names, and values are their corresponding values.
 * @property environment A map of environment variables to set for the command execution.
 */
data class ShellRun(
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

    fun execute(): ProcessResult {
        val processBuilder = ProcessBuilder()
        processBuilder.command(buildCommandList())

        // Set environment variables if provided
        environment?.let {
            val processEnvironment = processBuilder.environment()
            it.forEach { (key, value) ->
                processEnvironment[key] = value
            }
        }

        // Start the process
        val process = processBuilder.start()

        // Capture standard output and error streams
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

        // Wait for the process to complete with a timeout of 60 seconds
        process.waitFor(60, TimeUnit.SECONDS)

        // Return the result of the process execution
        return ProcessResult(
            code = process.exitValue(),
            stdout = stdout.toString().trim(),
            stderr = stderr.toString().trim()
        )
    }
}

data class ProcessResult(
    val code: Int,
    val stdout: String,
    val stderr: String
)
