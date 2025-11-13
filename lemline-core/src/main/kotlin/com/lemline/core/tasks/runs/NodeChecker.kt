// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.tasks.runs

import com.lemline.common.logger.logger

internal object NodeChecker {
    private val logger = logger()

    val exec: String? by lazy {
        getNodeExecAndCheckVersion()
    }

    /**
     * Checks that the installed Node.js version supports ES2024 (>= 22.0.0).
     * If not, logs a warning and returns "0.0.0".
     */
    private fun getNodeExecAndCheckVersion(): String? {
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
                // Try the next command
            }
        }
        // If no Node.js executable found,  return null
        if (foundExec == null || version == null) return null
        // Node.js version format: v22.1.0
        val versionPattern = Regex("v(\\d+)\\.(\\d+)\\.(\\d+)")
        when (val match = versionPattern.matchEntire(version)) {
            null -> logger.warn { "Node.js version '$version' does not match expected format. Cannot verify ES2024 compatibility." }

            else -> {
                logger.debug { "Detected Node.js version: '$version'" }
                val (major, _, _) = match.destructured
                val majorInt = major.toIntOrNull() ?: 0
                // ES2024 compatibility: Node.js >= 22.0.0
                if (majorInt < 22) logger.warn { "Node.js version $version detected. ES2024 features require Node.js >= 22.0.0. Some scripts may not run as expected." }
            }
        }
        return foundExec

    }
}
