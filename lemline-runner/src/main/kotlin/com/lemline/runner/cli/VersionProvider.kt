// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import picocli.CommandLine
import java.util.Properties

class VersionProvider : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> {
        return try {
            val props = Properties()
            VersionProvider::class.java.getResourceAsStream("/version.properties")?.use {
                props.load(it)
            } ?: return arrayOf("Lemline Runner: version.properties not found")

            val version = props.getProperty("version", "unknown")
            arrayOf("Lemline Runner $version")
        } catch (e: Exception) {
            // Consider logging the exception e.g., using org.jboss.logging.Logger
            // Logger.getLogger(VersionProvider::class.java).warn("Error reading version properties", e)
            arrayOf("Lemline Runner: Error reading version (see logs for details)")
        }
    }
}
