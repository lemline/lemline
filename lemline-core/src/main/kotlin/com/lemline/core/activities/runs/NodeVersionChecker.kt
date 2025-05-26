// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import com.lemline.common.debug
import com.lemline.common.logger
import com.lemline.common.warn

internal object NodeVersionChecker {
    private val log = logger()

    val NODE_VERSION by lazy { checkNodeVersionForES2024() }

    /**
     * Checks that the installed Node.js version supports ES2024 (>= 22.0.0). Logs a warning if not.
     */
    private fun checkNodeVersionForES2024(): String {
        // Allow override via environment variable or system property
        val nodeExec = System.getenv("LEMLINE_NODE_EXEC")
            ?: System.getProperty("lemline.node.exec")
            ?: "node"
        val commandsToTry = setOf(nodeExec, "node")
        var version: String? = null
        var found = false
        for (cmd in commandsToTry) {
            try {
                val process = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                if (output.isNotBlank()) {
                    version = output
                    found = true
                    break
                }
            } catch (_: Exception) {
                // Try next command
            }
        }
        if (!found || version == null) {
            log.warn { "Node.js executable not found. Please install Node.js >= 22.0.0 or set LEMLINE_NODE_EXEC." }
            return "0.0.0"
        }
        val versionPattern = Regex("v(\\d+)\\.(\\d+)\\.(\\d+)") // Node.js version format: v22.1.0
        val match = versionPattern.matchEntire(version)
        return when (match) {
            null -> {
                log.warn { "Node.js version '$version' does not match expected format. Cannot verify ES2024 compatibility." }
                "0.0.0"
            }
            else -> {
                log.debug { "Detected Node.js version: '$version'" }
                val (major, minor, patch) = match.destructured
                val majorInt = major.toIntOrNull() ?: 0
                val minorInt = minor.toIntOrNull() ?: 0
                val patchInt = patch.toIntOrNull() ?: 0
                // ES2024 compatibility: Node.js >= 22.0.0
                if (majorInt < 22) {
                    log.warn { "Node.js version $version detected. ES2024 features require Node.js >= 22.0.0. Some scripts may not run as expected." }
                }
                "$majorInt.$minorInt.$patchInt"
            }
        }
    }
}

