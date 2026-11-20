// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.common.config.SQLiteDatabase
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.common.MapBackedConfigSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LemlineConfigSourceSQLiteDatabaseTest {
    @Test
    fun `a SQLite path alone does not opt in or change server inference`() {
        val props = mapOf("lemline.database.sqlite.path" to ":memory:")
        assertEquals("in-memory", generated(props)["lemline.database.type"])
        assertEquals("postgresql", generated(props + ("lemline.database.postgresql.host" to "db"))["lemline.database.type"])
        assertEquals("mysql", generated(props + ("lemline.database.mysql.host" to "db"))["lemline.database.type"])
        assertNull(generated(props)["quarkus.datasource.sqlite.jdbc.url"])
    }

    @Test
    fun `the default SQLite path is relative to the process working directory`() {
        val config = generated(mapOf("lemline.database.type" to "sqlite", "lemline.database.postgresql.host" to "db"))
        assertEquals("sqlite", config["lemline.database.type"])
        assertEquals("true", config["quarkus.datasource.sqlite.active"])
        assertEquals("false", config["quarkus.datasource.postgresql.active"])
        assertEquals("false", config["quarkus.datasource.mysql.active"])
        val expected = Path.of("").toRealPath().resolve("orchestration.db")
        assertEquals(SQLiteDatabase.jdbcUrl(expected), config["quarkus.datasource.sqlite.jdbc.url"])
    }

    @Test
    fun `external values override file settings and aliases resolve to real paths`() {
        val directory = Files.createTempDirectory("lemline-sqlite-config-")
        val previousConfig = ConfigPathHolder.configPath
        try {
            val file = directory.resolve("config.yaml")
            Files.writeString(file, "lemline:\n  database:\n    type: sqlite\n    sqlite:\n      path: file.db\n")
            ConfigPathHolder.configPath = file
            val actual = Files.createDirectory(directory.resolve("actual"))
            val alias = Files.createSymbolicLink(directory.resolve("alias"), actual)
            val builder = SmallRyeConfigBuilder()
                .withSources(source(mapOf("lemline.database.sqlite.path" to alias.resolve("orchestration ?.db").toString())))
                .withSources(LemlineConfigSourceFactory())
            assertEquals(
                SQLiteDatabase.jdbcUrl(actual.toRealPath().resolve("orchestration ?.db")),
                builder.build().getConfigValue("quarkus.datasource.sqlite.jdbc.url").value,
            )
        } finally {
            ConfigPathHolder.configPath = previousConfig
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects invalid paths only when selected and enabled`() {
        val props = mapOf("lemline.database.type" to "sqlite")
        for (path in listOf("", " ", "file:orchestration.db", ":memory:", ".", "absent-parent/orchestration.db")) {
            val withPath = props + ("lemline.database.sqlite.path" to path)
            assertFailsWith<IllegalArgumentException> { generated(withPath) }
            val disabled = generated(withPath + ("lemline.database.enabled" to "false"))
            assertEquals("false", disabled["quarkus.datasource.sqlite.active"])
            assertNull(disabled["quarkus.datasource.sqlite.jdbc.url"])
        }
    }

    private fun generated(props: Map<String, String>): Map<String, String> =
        SmallRyeConfigBuilder().withSources(source(props)).withSources(LemlineConfigSourceFactory())
            .build().configSources.first { it is LemlineConfigSource }.properties

    private fun source(props: Map<String, String>) = object : MapBackedConfigSource("overrides", props, 400) {}
}
