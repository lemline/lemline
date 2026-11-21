// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/**
 * Serializes SQLite migrations across the processes sharing one database file.
 *
 * Flyway takes no lock on SQLite, so two processes starting together on a new file
 * would both apply the first migration. The lock lives in a sibling file because locking the
 * database file itself would interfere with SQLite's own file locks.
 */
object SQLiteMigrationLock {
    private const val JDBC_PREFIX = "jdbc:sqlite:"

    fun <T> withLock(jdbcUrl: String, block: () -> T): T {
        val lockFile = lockFile(jdbcUrl)
        // File locks are held per JVM: threads of one process must queue here first.
        return synchronized(this) {
            FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    private fun lockFile(jdbcUrl: String): Path {
        require(jdbcUrl.startsWith(JDBC_PREFIX)) { "Not a SQLite JDBC URL: $jdbcUrl" }
        // Connection options follow '?': they are not part of the file name, and two processes
        // opening one file with different options must still share one lock.
        val location = jdbcUrl.removePrefix(JDBC_PREFIX).substringBefore('?')
        val database = if (location.startsWith("file:")) {
            val uri = URI.create(location)
            // "file:name.db" is relative to the working directory and has no hierarchical path.
            if (uri.isOpaque) Path.of(uri.schemeSpecificPart) else Path.of(uri)
        } else {
            Path.of(location)
        }
        return database.resolveSibling("${database.fileName}.migration.lock")
    }
}
