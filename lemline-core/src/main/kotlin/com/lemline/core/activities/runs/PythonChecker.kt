// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import com.lemline.common.logger
import com.lemline.common.warn

internal object PythonChecker {
    private val log = logger()

    val exec: String? by lazy {
        getPythonExecAndCheckVersion()
    }

    /**
     * Checks that the installed Python version supports the required features (>= 3.8).
     * If not, logs a warning and returns an empty string.
     */
    private fun getPythonExecAndCheckVersion(): String? {
        val pythonExec = System.getenv("LEMLINE_PYTHON_EXEC")
            ?: System.getProperty("lemline.python.exec")
            ?: "python3"
        val commandsToTry = setOf(pythonExec, "python3", "python")
        var versionOutput: String? = null
        var foundExec: String? = null
        for (cmd in commandsToTry) {
            try {
                val process = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                if (output.isNotBlank()) {
                    versionOutput = output
                    foundExec = cmd
                    break
                }
            } catch (_: Exception) {
                // Try the next command
            }
        }
        // If no Python executable found, return null
        if (foundExec == null || versionOutput == null) return null
        // Python version format: Python 3.8.10
        val versionPattern = Regex("Python (\\d+)\\.(\\d+)\\.(\\d+)")
        val match = versionPattern.find(versionOutput)
        if (match != null) {
            val (major, minor, _) = match.destructured
            val majorInt = major.toIntOrNull() ?: 0
            val minorInt = minor.toIntOrNull() ?: 0
            if (majorInt < 3 || (majorInt == 3 && minorInt < 8)) {
                log.warn { "Python version $versionOutput detected. DSL features require Python >= 3.8.0. Some scripts may not run as expected." }
            }
        } else {
            log.warn { "Unable to parse Python version output: '$versionOutput'. Cannot verify DSL compatibility." }
        }
        return foundExec
    }
}
