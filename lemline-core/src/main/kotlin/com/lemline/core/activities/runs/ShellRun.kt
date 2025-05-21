// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ShellRun(
    val command: String,
    val arguments: Map<String, String>? = null,
    val environment: Map<String, String>? = null
) {

    fun execute(): ProcessResult {
        val processBuilder = ProcessBuilder()
        val fullCommand = mutableListOf<String>()
        fullCommand.add(command)
        arguments?.forEach { (key, value) ->
            fullCommand.add(key)
            fullCommand.add(value)
        }
        processBuilder.command(fullCommand)

        environment?.let {
            val processEnvironment = processBuilder.environment()
            it.forEach { (key, value) ->
                processEnvironment[key] = value
            }
        }

        val process = processBuilder.start()
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

        process.waitFor(60, TimeUnit.SECONDS) // Timeout for the process

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
