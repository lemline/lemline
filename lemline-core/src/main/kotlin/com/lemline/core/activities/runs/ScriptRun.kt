// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists

/**
 * Represents a script execution with support for multiple scripting languages.
 *
 * @property script The script content to execute
 * @property language The script language (e.g., "javascript", "python")
 * @property arguments Arguments to pass to the script
 * @property environment Environment variables for the script execution
 * @property workingDir Optional working directory for script execution
 */
data class ScriptRun(
    val script: String,
    val language: String,
    val arguments: Map<String, String>? = null,
    val environment: Map<String, String>? = null,
    val workingDir: Path? = null
) {

    /**
     * Executes the script and returns the result
     */
    fun execute(): ProcessResult {
        return when (language.lowercase()) {
            "javascript" -> executeJavascript()
            // Add more languages here as needed
            else -> throw IllegalArgumentException("Unsupported script language: $language")
        }
    }

    /**
     * Executes a JavaScript script using the system's Node.js installation
     */
    private fun executeJavascript(): ProcessResult {
        val scriptFile = createTempScriptFile("script", ".js")
        try {
            // Write the script to a temporary file
            Files.writeString(scriptFile, script, StandardOpenOption.WRITE)

            // Build the command to execute the script
            val command = mutableListOf("node", scriptFile.toString())

            // Add arguments to the command
            arguments?.forEach { (key, value) ->
                command.add("--$key")
                if (value.isNotBlank()) command.add(value)
            }

            // Set up the process
            val processBuilder = ProcessBuilder(command)
            workingDir?.let { processBuilder.directory(it.toFile()) }

            // Set environment variables if provided
            environment?.let { env ->
                val processEnv = processBuilder.environment()
                env.forEach { (key, value) ->
                    processEnv[key] = value
                }
            }

            // Start the process
            val process = processBuilder.start()

            // Capture output
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            // Read output streams
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            // Read stdout
            var line: String?
            while (stdoutReader.readLine().also { line = it } != null) {
                stdout.append(line).append(System.lineSeparator())
            }

            // Read stderr
            while (stderrReader.readLine().also { line = it } != null) {
                stderr.append(line).append(System.lineSeparator())
            }

            // Wait for process to complete
            val completed = process.waitFor(1, TimeUnit.MINUTES)
            val exitCode = if (completed) process.exitValue() else -1

            return ProcessResult(
                code = exitCode,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim()
            )
        } finally {
            // Clean up the temporary script file
            scriptFile.deleteIfExists()
        }
    }

    /**
     * Creates a temporary file with the given prefix and suffix
     */
    private fun createTempScriptFile(prefix: String, suffix: String): Path {
        // Using a consistent prefix for all script files
        @Suppress("UNUSED_PARAMETER") // prefix is kept for future use
        return Files.createTempFile("lemline-script-", suffix).apply {
            toFile().deleteOnExit()
        }
    }
}

