// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import com.lemline.common.debug
import com.lemline.common.logger
import com.lemline.common.warn

internal object NodeChecker {
    private val log = logger()

    val exec: String by lazy {
        checkNodeExecAndVersion()
    }

    /**
     * Checks that the installed Node.js version supports ES2024 (>= 22.0.0).
     * If not, logs a warning and returns "0.0.0".
     */
    private fun checkNodeExecAndVersion(): String {
        // Allow override via environment variable or system property
        val nodeExec = System.getenv("LEMLINE_NODE_EXEC")
            ?: System.getProperty("lemline.node.exec")
            ?: "node"
        val commandsToTry = setOf(nodeExec, "node")
        var version: String? = null
        var foundExec: String? = null
        for (cmd in commandsToTry) {
            try {
                val process = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                if (output.isNotBlank()) {
                    version = output
                    foundExec = cmd
                    break
                }
            } catch (_: Exception) {
                // Try next command
            }
        }
        if (foundExec == null || version == null) {
            log.warn { "Node.js executable not found. Please install Node.js >= 22.0.0 or set LEMLINE_NODE_EXEC to locate the Node.js executable." }
            return ""
        }
        // Node.js version format: v22.1.0
        val versionPattern = Regex("v(\\d+)\\.(\\d+)\\.(\\d+)")
        val match = versionPattern.matchEntire(version)
        when (match) {
            null -> log.warn { "Node.js version '$version' does not match expected format. Cannot verify ES2024 compatibility." }

            else -> {
                log.debug { "Detected Node.js version: '$version'" }
                val (major, _, _) = match.destructured
                val majorInt = major.toIntOrNull() ?: 0
                // ES2024 compatibility: Node.js >= 22.0.0
                if (majorInt < 22) log.warn { "Node.js version $version detected. ES2024 features require Node.js >= 22.0.0. Some scripts may not run as expected." }
            }
        }
        return foundExec

    }
}

