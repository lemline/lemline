// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException

object SQLiteDatabase {
    // Orchestration timestamps use signed Unix epoch microseconds.
    const val CURRENT_TIMESTAMP_SQL = "CAST(round(unixepoch('subsec') * 1000000) AS INTEGER)"

    fun resolvePath(value: String, setting: String): Path {
        require(
            value.isNotBlank() && !value.startsWith("file:", ignoreCase = true) &&
                !value.startsWith("jdbc:", ignoreCase = true) && value != ":memory:" && "://" !in value
        ) { "$setting must be a local filesystem path: '$value'" }

        try {
            val path = Path.of(value).toAbsolutePath()
            require(path.fileName != null) { "$setting must name a file: '$path'" }
            val resolved = if (Files.exists(path, NOFOLLOW_LINKS)) {
                path.toRealPath().also {
                    require(Files.isRegularFile(it) && Files.isWritable(it)) {
                        "$setting must be a writable regular file: '$it'"
                    }
                }
            } else {
                path.parent.toRealPath().resolve(path.fileName)
            }
            require(Files.isDirectory(resolved.parent) && Files.isWritable(resolved.parent)) {
                "$setting must have an existing writable parent directory: '$resolved'"
            }
            return resolved
        } catch (e: IOException) {
            throw IllegalArgumentException("Cannot resolve $setting '$value': ${e.message}", e)
        }
    }

    fun jdbcUrl(path: Path): String = "jdbc:sqlite:${path.toUri().toASCIIString()}" +
        "?busy_timeout=5000&foreign_keys=on&synchronous=FULL"

    /** Establish the persistent journal mode before opening Flyway or serving traffic. */
    fun initialize(connection: Connection) {
        connection.createStatement().use { statement ->
            val journalMode = statement.executeQuery("PRAGMA journal_mode").use { rs ->
                check(rs.next())
                rs.getString(1)
            }
            if (!journalMode.equals("wal", ignoreCase = true)) {
                statement.executeQuery("PRAGMA journal_mode=WAL").use { rs ->
                    if (!rs.next() || !rs.getString(1).equals("wal", ignoreCase = true)) {
                        throw SQLException("SQLite database could not enable WAL")
                    }
                }
            }
        }
    }

    /**
     * Reserve the writer before reading candidates. Use SQL transaction boundaries because
     * Xerial's JDBC commit and rollback begin a new transaction before returning.
     */
    suspend fun <R> writeTransaction(connection: Connection, block: suspend (Connection) -> R): R {
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val result = block(connection)
            connection.createStatement().use { it.execute("COMMIT") }
            return result
        } catch (failure: Throwable) {
            try {
                connection.createStatement().use { it.execute("ROLLBACK") }
            } catch (rollbackFailure: SQLException) {
                failure.addSuppressed(rollbackFailure)
                releaseWriter(connection, rollbackFailure, failure)
            }
            throw failure
        }
    }

    /**
     * A failed ROLLBACK can leave the transaction open on a pooled connection, which would keep
     * the only writer. Closing the physical connection makes SQLite roll back and release it;
     * the pool's validation then discards the closed connection. SQLite has already rolled back
     * when it reports that no transaction is active.
     */
    private fun releaseWriter(connection: Connection, rollbackFailure: SQLException, failure: Throwable) {
        if (rollbackFailure.message?.contains("no transaction is active", ignoreCase = true) == true) return
        try {
            connection.unwrap(Connection::class.java).close()
        } catch (closeFailure: SQLException) {
            failure.addSuppressed(closeFailure)
        }
    }
}
