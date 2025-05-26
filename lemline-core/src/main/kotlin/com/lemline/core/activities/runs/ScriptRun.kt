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
        "python" -> executePythonAsync()
        else -> throw IllegalArgumentException("Unsupported script language: $language")
    }

    /**
     * Executes the script and returns the result
     */
    fun execute(): ProcessResult = when (language.lowercase()) {
        "js" -> executeJavascript()
        "python" -> executePython()
        else -> throw IllegalArgumentException("Unsupported script language: $language")
    }

    /**
     * Executes the JavaScript script asynchronously.
     */
    private fun executeJavascriptAsync(): Process {
        val scriptFile = createAndWriteTempFile(".js")
        return startProcess(NodeChecker.exec, scriptFile)
    }

    /**
     * Executes the Python script asynchronously.
     */
    private fun executePythonAsync(): Process {
        val scriptFile = createAndWriteTempFile(".py")
        return startProcess(PythonChecker.exec, scriptFile)
    }

    /**
     * Unified script execution for JavaScript and Python.
     * @param ext file extension (".js" or ".py")
     * @param processStarter lambda to start the process given a script file
     */
    private fun executeScriptSync(
        ext: String,
        processStarter: (Path) -> Process
    ): ProcessResult {
        val scriptFile = createAndWriteTempFile(ext)
        val process = processStarter(scriptFile)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
        var line: String?
        while (stdoutReader.readLine().also { line = it } != null) {
            stdout.append(line).append(System.lineSeparator())
        }
        while (stderrReader.readLine().also { line = it } != null) {
            stderr.append(line).append(System.lineSeparator())
        }
        val exitCode = process.waitFor()

        return ProcessResult(
            code = exitCode,
            stdout = stdout.toString().trim(),
            stderr = stderr.toString().trim()
        )
    }

    private fun executeJavascript(): ProcessResult = executeScriptSync(
        ext = ".js",
        processStarter = { startProcess(NodeChecker.exec, it) }
    )

    private fun executePython(): ProcessResult = executeScriptSync(
        ext = ".py",
        processStarter = { startProcess(PythonChecker.exec, it) }
    )


    /**
     * Creates a temporary file with the given prefix and suffix
     */
    private fun createTempScriptFile(suffix: String): Path =
        Files.createTempFile("lemline-script-", suffix).apply { toFile().deleteOnExit() }

    /**
     * Creates a temporary script file with the given suffix, writes the script content to it,
     * and returns the file path.
     *
     * @param suffix The file extension (e.g., ".js", ".py")
     * @return The path to the created temporary script file
     */
    private fun createAndWriteTempFile(suffix: String): Path {
        val scriptFile = createTempScriptFile(suffix)
        Files.writeString(scriptFile, script, StandardOpenOption.WRITE)
        return scriptFile
    }

    /**
     * Starts a process using the provided script file.
     *
     * @param scriptFile The path to the script file to execute
     * @return The started Process object
     */
    private fun startProcess(exec: String, scriptFile: Path): Process {
        val command = mutableListOf(exec, scriptFile.toString())
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
                    scriptFile.deleteIfExists()
                }
        }
    }
}
