// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * The external configuration file as it was read when the process started.
 *
 * Captured once, while [LemlineConfigSource] merges the file: later edits on disk are not reflected,
 * so readers see what the process actually runs with.
 */
object LoadedConfigFile {
    /** Files larger than this are reported without their text. */
    const val MAX_TEXT_BYTES: Long = 256 * 1024

    data class Snapshot(
        val path: Path,
        val locator: ConfigFileLocator?,
        /** Raw text, or null when the file is too large or could not be read again. */
        val text: String?,
        /** `lemline.*` values read from the file, as merged into the configuration. */
        val properties: Map<String, String>,
    )

    @Volatile
    var snapshot: Snapshot? = null
        private set

    /** Records the file. The configuration source may be built several times: the first capture of a path wins. */
    fun capture(path: Path, locator: ConfigFileLocator?, properties: Map<String, String>) {
        val absolute = path.toAbsolutePath().normalize()
        if (snapshot?.path == absolute) return
        val text = runCatching { if (Files.size(absolute) <= MAX_TEXT_BYTES) Files.readString(absolute) else null }
            .getOrNull()
        snapshot = Snapshot(absolute, locator, text, properties.toMap())
    }

    /** Test support. */
    fun reset() {
        snapshot = null
    }
}
