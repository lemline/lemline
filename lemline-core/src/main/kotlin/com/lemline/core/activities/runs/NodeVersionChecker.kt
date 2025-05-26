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
    private fun checkNodeVersionForES2024(): String = try {
        val process = ProcessBuilder("node", "--version").start()
        val version = process.inputStream.bufferedReader().readText().trim()
        val versionPattern = Regex("v(\\d+)\\.(\\d+)\\.(\\d+)") // Node.js version format: v22.1.0
        val match = versionPattern.matchEntire(version)
        when (match) {
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
    } catch (e: Exception) {
        log.warn(e) { "Failed to check Node.js version for ES2024 compatibility." }
        "0.0.0"
    }
}
