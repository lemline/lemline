// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.config.DatabaseManager
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SQLiteDatabaseTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var directory: Path

    override fun start(): Map<String, String> {
        directory = Files.createTempDirectory("lemline-sqlite-profile-")
        return mapOf(
            "lemline.database.type" to "sqlite",
            "lemline.database.sqlite.path" to directory.resolve("orchestration.db").toString(),
            "lemline.messaging.type" to "in-memory",
        )
    }

    override fun stop() {
        directory.toFile().deleteRecursively()
    }
}

class SQLiteDatabaseProfile : QuarkusTestProfile {
    override fun testResources() =
        listOf(QuarkusTestProfile.TestResourceEntry(SQLiteDatabaseTestResource::class.java))
}

@TestProfile(SQLiteDatabaseProfile::class)
@QuarkusTest
class SQLiteDatabaseProfileTest {
    @Inject
    lateinit var database: DatabaseManager

    @Test
    fun `migrates the file and configures each physical connection`() {
        assertEquals(DatabaseType.SQLITE, database.dbType)
        assertTrue(database.getPendingMigrations().isEmpty())
        val pool = database.datasource
        pool.connection.use { first ->
            pool.connection.use { second ->
                for (connection in listOf(first, second)) {
                    assertEquals("wal", connection.scalar("PRAGMA journal_mode"))
                    assertEquals("1", connection.scalar("PRAGMA foreign_keys"))
                    assertEquals("2", connection.scalar("PRAGMA synchronous"))
                    assertEquals("5000", connection.scalar("PRAGMA busy_timeout"))
                    assertEquals("0", connection.scalar("SELECT count(*) FROM lemline_gateway_command_outbox"))
                }
            }
        }
    }

    private fun Connection.scalar(sql: String) = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next())
            rows.getString(1)
        }
    }
}
