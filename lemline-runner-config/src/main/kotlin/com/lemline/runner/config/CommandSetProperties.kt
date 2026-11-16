// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

/**
 * System properties that the Lemline command line sets itself before Quarkus starts.
 *
 * `gateway start`, for example, always sets `lemline.gateway.enabled=true`: nobody typed it as `-D`.
 * Reporting it as a typed system property would send operators looking for a flag that does not exist.
 */
object CommandSetProperties {
    /** The command that set them, e.g. "gateway start"; empty when unknown. */
    @Volatile
    var command: String = ""
        private set

    @Volatile
    var keys: Set<String> = emptySet()
        private set

    fun record(command: String, keys: Collection<String>) {
        this.command = command
        this.keys = keys.toSet()
    }

    /** Test support. */
    fun reset() = record("", emptySet())
}
