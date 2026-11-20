// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import io.smallrye.config.EnvConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.common.MapBackedConfigSource
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals

class LemlineConfigSourceFactoryTest {
    @Test
    fun `generates connector properties from profile overrides without system properties`() {
        val config = SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .withProfile("test")
            .withSources(source(mapOf(
                "lemline.messaging.type" to "in-memory",
                "%test.lemline.messaging.type" to "kafka",
                "%test.lemline.messaging.lifecycleevents.consumer.enabled" to "true"
            ), 1000))
            .withSources(LemlineConfigSourceFactory())
            .build()
        assertEquals("smallrye-kafka", config.getValue(
            "mp.messaging.incoming.lifecycleevents-in.connector", String::class.java
        ))
    }

    @Test
    fun `external file overrides defaults but higher priority sources override the file`() {
        val previousPath = ConfigPathHolder.configPath
        val file = Files.createTempFile("lemline-config-factory-", ".yaml")
        try {
            Files.writeString(file, "lemline:\n  messaging:\n    type: kafka\n")
            ConfigPathHolder.configPath = file
            for ((ordinal, expected) in listOf(250 to "kafka", 300 to "rabbitmq")) {
                val config = SmallRyeConfigBuilder()
                    .withSources(source(mapOf("lemline.messaging.type" to "rabbitmq"), ordinal))
                    .withSources(LemlineConfigSourceFactory())
                    .build()
                assertEquals(expected, config.getValue("lemline.messaging.type", String::class.java))
            }
        } finally {
            ConfigPathHolder.configPath = previousPath
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `mapping defaults do not suppress backend inference`() {
        val config = SmallRyeConfigBuilder()
            .withDefaultValue("lemline.database.type", "in-memory")
            .withSources(source(mapOf("lemline.database.type" to "in-memory"), 0))
            .withDefaultValue("lemline.database.mysql.host", "localhost")
            .withDefaultValue("lemline.database.postgresql.host", "localhost")
            .withSources(source(mapOf("lemline.database.postgresql.host" to "db.example.test"), 400))
            .withSources(LemlineConfigSourceFactory())
            .build()
        assertEquals("postgresql", config.getValue("lemline.database.type", String::class.java))
    }

    @Test
    fun `SQLite path respects YAML environment and system precedence`(@TempDir directory: Path) {
        val file = directory.resolve("lemline.yaml")
        Files.writeString(file, """
            lemline:
              gateway:
                enabled: true
              analytics:
                type: sqlite
                sqlite:
                  path: yaml.db
        """.trimIndent())
        val previousPath = ConfigPathHolder.configPath
        try {
            ConfigPathHolder.configPath = file
            val env = EnvConfigSource(mapOf("LEMLINE_ANALYTICS_SQLITE_PATH" to "env.db"), 300)
            val system = source(mapOf("lemline.analytics.sqlite.path" to "system.db"), 400)
            val profiles = listOf(
                "yaml.db" to emptyList(),
                "env.db" to listOf(env),
                "system.db" to listOf(env, system)
            )
            for ((name, sources) in profiles) {
                val config = SmallRyeConfigBuilder()
                    .withSources(sources)
                    .withSources(LemlineConfigSourceFactory())
                    .build()
                val expected = Path.of(".").toRealPath().resolve(name).toUri()
                assertEquals(
                    "jdbc:sqlite:$expected",
                    config.getValue("quarkus.datasource.analytics-sqlite.jdbc.url", String::class.java)
                )
            }
        } finally {
            ConfigPathHolder.configPath = previousPath
        }
    }

    private fun source(properties: Map<String, String>, ordinal: Int) =
        object : MapBackedConfigSource("test-$ordinal", properties, ordinal) {}
}
