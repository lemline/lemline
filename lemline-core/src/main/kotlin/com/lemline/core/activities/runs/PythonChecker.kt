package com.lemline.core.activities.runs

import com.lemline.common.logger
import com.lemline.common.warn

object PythonChecker {
    private val log = logger()

    val exec: String by lazy {
        checkPythonExecAndVersion()
    }

    private fun checkPythonExecAndVersion(): String {
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
                // Try next command
            }
        }
        if (foundExec == null || versionOutput == null) {
            log.warn { "Python executable not found. Please install Python 3.8+ or set LEMLINE_PYTHON_EXEC to locate the Python executable." }
            return ""
        }
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
