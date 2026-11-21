// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.sqlite

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.flywaydb.core.Flyway
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import org.sqlite.SQLiteOpenMode

internal object SqliteDatabase {
    // This module stays independent of the runner configuration modules, which own the
    // setting. The name is repeated here only to point operators at it in error messages.
    private const val PATH_SETTING = "lemline.messaging.sqlite.path"
    private const val BUSY_TIMEOUT_MS = 5_000

    fun resolve(value: String): Path {
        require(value.isNotBlank() && !value.startsWith("file:", ignoreCase = true) &&
            !value.startsWith("jdbc:", ignoreCase = true) && value != ":memory:"
        ) { "$PATH_SETTING must be a local file path: '$value'" }

        val absolute = Path.of(value).toAbsolutePath()
        val path = if (Files.exists(absolute)) absolute.toRealPath() else {
            val parent = requireNotNull(absolute.parent) { "$PATH_SETTING must name a file: $absolute" }
            val directory = try {
                parent.toRealPath()
            } catch (e: NoSuchFileException) {
                throw IllegalArgumentException("$PATH_SETTING requires an existing parent directory: $parent", e)
            }
            directory.resolve(absolute.fileName)
        }
        require(!Files.isSymbolicLink(path) && (!Files.exists(path) || Files.isRegularFile(path))) {
            "$PATH_SETTING must name a regular file: $path"
        }
        require(Files.isDirectory(path.parent) && Files.isWritable(path.parent)) {
            "$PATH_SETTING requires a writable parent directory: ${path.parent}"
        }
        return path
    }

    fun open(path: Path): SQLiteDataSource = SQLiteDataSource(SQLiteConfig().apply {
        // File creation is confined to prepareFile, which sets its permissions.
        resetOpenMode(SQLiteOpenMode.CREATE)
        enforceForeignKeys(true)
        setSynchronous(SQLiteConfig.SynchronousMode.FULL)
        setBusyTimeout(BUSY_TIMEOUT_MS)
    }).apply {
        url = "jdbc:sqlite:${path.toUri().toASCIIString()}"
    }

    fun initialize(path: Path): SqliteQueue {
        try {
            val dataSource = open(path)
            val queue = SqliteQueue(dataSource, path)
            // Flyway can inspect the schema before taking its transaction lock. Serialize
            // startup across processes, including the first creation of the history table.
            val lockPath = path.resolveSibling(".${path.fileName}.lemline-migrate.lock")
            check(!Files.exists(lockPath, NOFOLLOW_LINKS) || Files.isRegularFile(lockPath, NOFOLLOW_LINKS)) {
                "SQLite messaging requires a regular migration lock file: $lockPath"
            }
            FileChannel.open(lockPath, CREATE, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                migrationLock(channel).use {
                    prepareFile(path)
                    dataSource.connection.use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("PRAGMA journal_mode").use { mode ->
                                mode.next()
                                mode.getString(1)
                            }.let { mode ->
                                if (mode != "wal") {
                                    statement.executeQuery("PRAGMA journal_mode=WAL").use { result ->
                                        check(result.next() && result.getString(1) == "wal") {
                                            "SQLite did not enable WAL for $path"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration/messaging/sqlite")
                        .resourceProvider(SqliteMigrationResources)
                        .javaMigrationClassProvider { emptyList() }
                        .load()
                        .migrate()
                    queue.verifySchema()
                }
            }
            return queue
        } catch (e: Exception) {
            throw IllegalStateException("Cannot initialize SQLite messaging at '$path' ($PATH_SETTING)", e)
        }
    }

    private fun prepareFile(path: Path) {
        try {
            if (Files.getFileStore(path.parent).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
                val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                Files.createFile(path, permissions)
            } else {
                Files.createFile(path)
            }
        } catch (_: FileAlreadyExistsException) {
            // Preserve operator-managed permissions on existing queue files.
        }
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.isReadable(path) && Files.isWritable(path)) {
            "SQLite messaging requires a readable, writable regular file: $path"
        }
        // Hard links keep different names after canonicalization, so SQLite would
        // use different WAL files for the same database inode.
        if (Files.getFileStore(path).supportsFileAttributeView("unix")) {
            for (file in listOf(path, path.resolveSibling("${path.fileName}-wal"), path.resolveSibling("${path.fileName}-shm"))) {
                val links = try {
                    (Files.getAttribute(file, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong()
                } catch (_: NoSuchFileException) {
                    // WAL sidecars can disappear when another process closes its connection.
                    continue
                }
                check(links == 1L && !Files.isSymbolicLink(file)) {
                    "SQLite messaging requires a database and sidecars without link aliases: $file"
                }
            }
        }
    }

    private fun migrationLock(channel: FileChannel): FileLock {
        val start = System.nanoTime()
        do {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) return lock
            Thread.sleep(25)
        } while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(BUSY_TIMEOUT_MS.toLong()))

        error("Timed out waiting for the SQLite messaging migration lock")
    }
}
