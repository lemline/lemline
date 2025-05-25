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
     * Executes the shell command with the specified arguments and environment variables.
     *
     * @return A [ProcessResult] containing the exit code, standard output, and standard error of the command execution.
     * @throws IOException If an I/O error occurs during command execution.
     */
    fun execute(): ProcessResult {
        val processBuilder = ProcessBuilder()

        // Build the full command with arguments
        val fullCommand = mutableListOf<String>()
        fullCommand.add(command)
        arguments?.forEach { (key, value) ->
            fullCommand.add(key)
            fullCommand.add(value)
        }
        processBuilder.command(fullCommand)

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
