// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import com.lemline.common.debug
import com.lemline.common.logger
import com.lemline.common.warn
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.deleteIfExists

/**
 * Represents a script execution with support for multiple scripting languages.
 *
 * @property script The script content to execute
 * @property language The script language (e.g., "js", "python")
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
    private val log = logger()

    /**
     * Executes the script asynchronously and returns the Process object
     */
    fun executeAsync(): Process = when (language.lowercase()) {
        "js" -> executeJavascriptAsync()
        // Add more languages here as needed
        else -> throw IllegalArgumentException("Unsupported script language: $language")
    }

    /**
     * Executes the script and returns the result
     */
    fun execute(): ProcessResult = when (language.lowercase()) {
        "js" -> executeJavascript()
        // Add more languages here as needed
        else -> throw IllegalArgumentException("Unsupported script language: $language")
    }

    private fun createAndWriteTempScriptFile(suffix: String = ".js"): Path {
        val scriptFile = createTempScriptFile("script", suffix)
        Files.writeString(scriptFile, script, StandardOpenOption.WRITE)
        return scriptFile
    }

    private fun startJavascriptProcess(scriptFile: Path): Process {
        val command = mutableListOf("node", scriptFile.toString())
        arguments?.forEach { (key, value) ->
            command.add(key)
            if (value.isNotBlank()) command.add(value)
        }
        val processBuilder = ProcessBuilder(command)
        workingDir?.let { processBuilder.directory(it.toFile()) }
        environment?.let { env ->
            val processEnv = processBuilder.environment()
            env.forEach { (key, value) -> processEnv[key] = value }
        }
        return processBuilder.start().apply {
            onExit()
                .whenComplete { out, ex ->
                    when (ex) {
                        null -> log.debug { "Process completed successfully with exit code ${exitValue()} and output $out" }
                        else -> log.warn(ex) { "Process terminated with an exception: ${ex.message}" }
                    }
                    // Ensure the temporary script file is deleted after process completion
                    scriptFile.deleteIfExists()
                }
        }
    }

    /**
     * Executes the JavaScript script asynchronously.
     *
     * This method performs the following steps:
     * 1. Creates a temporary file and writes the script content to it.
     * 2. Prepares a `ProcessBuilder` to execute the script using Node.js, including any provided arguments and environment variables.
     * 3. Starts the process and returns it immediately.
     * 4. Registers a callback to delete the temporary script file once the process exits, ensuring cleanup.
     *
     * @return The [Process] object representing the running script.
     */
    private fun executeJavascriptAsync(): Process {
        NodeVersionChecker.NODE_VERSION

        val scriptFile = createAndWriteTempScriptFile()
        return startJavascriptProcess(scriptFile)
    }

    /**
     * Executes the JavaScript script synchronously and returns the result.
     *
     * This method performs the following steps:
     * 1. Creates a temporary file and writes the script content to it.
     * 2. Prepares a `ProcessBuilder` to execute the script using Node.js, including any provided arguments and environment variables.
     * 3. Starts the process and captures both standard output and standard error streams.
     * 4. Waits for the process to complete and collects the exit code, stdout, and stderr.
     * 5. Deletes the temporary script file after execution, regardless of success or failure.
     *
     * @return [ProcessResult] containing the exit code, standard output, and standard error of the script execution.
     */
    private fun executeJavascript(): ProcessResult {
        NodeVersionChecker.NODE_VERSION

        val scriptFile = createAndWriteTempScriptFile()
        val process = startJavascriptProcess(scriptFile)

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

        var line: String?
        // Read standard output from the process
        while (stdoutReader.readLine().also { line = it } != null) {
            stdout.append(line).append(System.lineSeparator())
        }
        // Read standard error from the process
        while (stderrReader.readLine().also { line = it } != null) {
            stderr.append(line).append(System.lineSeparator())
        }

        // Wait for the process to finish and get the exit code
        val exitCode = process.waitFor()
        return ProcessResult(
            code = exitCode,
            stdout = stdout.toString().trim(),
            stderr = stderr.toString().trim()
        )
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
