// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.runner.analytics.config.AnalyticsPostgresqlFlywayCustomizer
import com.lemline.runner.common.test.H2TestDatabaseConfig
import com.lemline.runner.common.test.PostgresTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.common.config.SQLiteMigrationLock
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LifecycleAnalyticsMigrationTest {
    @Test
    fun `H2 migrations retain the unfiltered lifecycle event index`() {
        H2TestDatabaseConfig("classpath:db/migration/analytics/h2").use { database ->
            database.migrate()
            database.jdbcDataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = ?"
                ).use { statement ->
                    statement.setString(1, H2_INDEX_NAME.uppercase())
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
        }
    }

    @RequiresDocker
    @Test
    fun `PostgreSQL migration removes the obsolete partial lifecycle index`() {
        PostgresTestDatabaseConfig("classpath:db/migration/analytics/postgresql").use { database ->
            database.start()
            val configuration = Flyway.configure()
                .dataSource(database.jdbcDataSource)
                .locations("classpath:db/migration/analytics/postgresql")
                .defaultSchema("lemline")
                .createSchemas(true)
            AnalyticsPostgresqlFlywayCustomizer().customize(configuration)
            configuration.load().migrate()
            database.jdbcDataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'lemline' AND indexname = ?"
                ).use { statement ->
                    statement.setString(1, H2_INDEX_NAME)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1))
                    }
                }
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'lemline' AND indexname = ?"
                ).use { statement ->
                    statement.setString(1, POSTGRESQL_INDEX_NAME)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
        }
    }

    @Test
    fun `SQLite migration creates the lifecycle indexes on a file and is idempotent`(@TempDir directory: Path) {
        val path = directory.resolve("analytics.db")
        val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:$path" }
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/analytics/sqlite")
            .table("lemline_flyway_schema_history_analytics")
            .load()

        assertEquals(1, flyway.migrate().migrationsExecuted)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA index_info('$H2_INDEX_NAME')").use { rows ->
                    assertEquals(
                        listOf("lemline_workflow_namespace", "type", "event_time"),
                        buildList { while (rows.next()) add(rows.getString("name")) }
                    )
                }
            }
        }
        assertEquals(0, flyway.migrate().migrationsExecuted)
        flyway.validate()
    }

    @Test
    fun `SQLite migration tolerates two processes preparing the same new file`(@TempDir directory: Path) {
        repeat(20) { round ->
            val url = "jdbc:sqlite:${directory.resolve("analytics-$round.db")}"
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val executed = List(2) {
                    pool.submit<Int> {
                        val dataSource = SQLiteDataSource().apply {
                            this.url = url
                            setBusyTimeout(5_000)
                        }
                        val flyway = Flyway.configure()
                            .dataSource(dataSource)
                            .locations("classpath:db/migration/analytics/sqlite")
                            .table("lemline_flyway_schema_history_analytics")
                            .load()
                        barrier.await()
                        SQLiteMigrationLock.withLock(url) { flyway.migrate().migrationsExecuted }
                    }
                }.map { it.get() }
                assertEquals(listOf(0, 1), executed.sorted())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `SQLite migration lock ignores JDBC connection options`(@TempDir directory: Path) {
        val database = directory.resolve("analytics.db")
        val lockFile = directory.resolve("analytics.db.migration.lock")
        for (location in listOf(database.toUri().toString(), database.toString())) {
            java.nio.file.Files.deleteIfExists(lockFile)
            SQLiteMigrationLock.withLock("jdbc:sqlite:$location?busy_timeout=10000&foreign_keys=on") {}
            assertEquals(true, java.nio.file.Files.exists(lockFile))
        }
        // A relative file: URL names a file in the working directory.
        val name = "lock-probe-${System.nanoTime()}.db"
        val relative = Path.of("$name.migration.lock")
        try {
            SQLiteMigrationLock.withLock("jdbc:sqlite:file:$name") {}
            assertEquals(true, java.nio.file.Files.exists(relative))
        } finally {
            java.nio.file.Files.deleteIfExists(relative)
        }
    }

    private companion object {
        const val H2_INDEX_NAME = "idx_lemline_lifecycle_events_namespace_wftype_time"
        const val POSTGRESQL_INDEX_NAME = "idx_lemline_lifecycle_events_namespace_type"
    }
}
