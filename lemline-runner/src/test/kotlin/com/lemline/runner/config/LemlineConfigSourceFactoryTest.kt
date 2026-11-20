// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import io.smallrye.config.EnvConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.common.MapBackedConfigSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import org.eclipse.microprofile.config.spi.ConfigSource

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
    fun `SQLite path alone keeps the existing default and broker inference`() {
        val settings = mapOf("lemline.messaging.sqlite.path" to "ignored.db",
            "lemline.messaging.commands.consumer.enabled" to "true")
        for ((brokerProperties, expected) in listOf(
            emptyMap<String, String>() to "in-memory",
            mapOf("lemline.messaging.kafka.brokers" to "localhost:9092") to "kafka",
            mapOf("lemline.messaging.rabbitmq.hostname" to "localhost") to "rabbitmq",
            mapOf("lemline.messaging.pgmq.host" to "localhost") to "pgmq",
        )) {
            val config = SmallRyeConfigBuilder()
                .withSources(source(settings + brokerProperties, 300))
                .withSources(LemlineConfigSourceFactory())
                .build()
            assertEquals(expected, config.getValue("lemline.messaging.type", String::class.java))
        }
    }

    @Test
    fun `an empty unused SQLite path permits historical broker selection`() {
        val prefix = "lemline.messaging.sqlite"
        val config = SmallRyeConfigBuilder()
            .withMapping(LemlineConfiguration.SqliteMessagingConfig::class.java, prefix)
            .withSources(source(mapOf(
                "$prefix.path" to "",
                "lemline.messaging.pgmq.host" to "localhost",
            ), 300))
            .withSources(LemlineConfigSourceFactory())
            .build()
        assertEquals("pgmq", config.getValue("lemline.messaging.type", String::class.java))
        assertFalse(config.getConfigMapping(LemlineConfiguration.SqliteMessagingConfig::class.java, prefix).path().isPresent)
    }

    @Test
    fun `SQLite generates only enabled channels and keeps the lifecycle queue shared`() {
        val config = SmallRyeConfigBuilder()
            .withSources(source(mapOf(
                "lemline.messaging.type" to "sqlite",
                "lemline.messaging.kafka.brokers" to "ignored:9092",
                "lemline.messaging.prefix" to "prefix",
                "lemline.namespaces" to "acme,team-blue",
                "lemline.messaging.commands.consumer.enabled" to "true",
                "lemline.messaging.events.producer.enabled" to "true",
                "lemline.messaging.cloudevents.consumer.enabled" to "true",
                "lemline.messaging.lifecycleevents.consumer.enabled" to "true",
                "lemline.messaging.lifecycleevents.producer.enabled" to "true",
            ), 300))
            .withSources(LemlineConfigSourceFactory())
            .build()
        fun value(key: String) = config.getValue("mp.messaging.$key", String::class.java)
        assertEquals("smallrye-sqlite", value("incoming.commands-in.connector"))
        assertEquals("messaging.db", value("incoming.commands-in.path"))
        assertEquals("prefix-acme-commands,prefix-team-blue-commands", value("incoming.commands-in.queues"))
        assertEquals("prefix-default-events", value("outgoing.events-out.queue"))
        assertEquals("prefix-acme-cloudevents,prefix-team-blue-cloudevents", value("incoming.cloudevents-in.queues"))
        assertEquals("true", value("incoming.cloudevents-in.ordered"))
        assertEquals("lemline-lifecycle-events", value("incoming.lifecycleevents-in.queue"))
        assertEquals("lemline-lifecycle-events", value("outgoing.lifecycleevents-out.queue"))
        assertFalse(config.getOptionalValue("mp.messaging.outgoing.commands-out.path", String::class.java).isPresent)
        assertFalse(config.getOptionalValue("mp.messaging.incoming.events-in.path", String::class.java).isPresent)
    }

    @Test
    fun `SQLite path precedence is system property then environment then config file`() {
        val previousPath = ConfigPathHolder.configPath
        val file = Files.createTempFile("lemline-sqlite-config-", ".yaml")
        val path = "lemline.messaging.sqlite.path"
        try {
            Files.writeString(file, """
                lemline:
                  messaging:
                    type: sqlite
                    prefix: yaml-prefix
                    sqlite:
                      path: file.db
                    commands:
                      producer:
                        enabled: true
            """.trimIndent())
            ConfigPathHolder.configPath = file
            val env = EnvConfigSource(mapOf("LEMLINE_MESSAGING_SQLITE_PATH" to "env.db"), 300)
            val system = source(mapOf(path to "system.db"), 400)
            for ((sources, expected) in listOf(
                emptyList<ConfigSource>() to "file.db",
                listOf(env) to "env.db",
                listOf(env, system) to "system.db",
            )) {
                val config = SmallRyeConfigBuilder()
                    .withSources(*sources.toTypedArray())
                    .withSources(LemlineConfigSourceFactory())
                    .build()
                assertEquals(expected, config.getValue("mp.messaging.outgoing.commands-out.path", String::class.java))
            }
            val fromYaml = "${'$'}{lemline.messaging.prefix:default}.db"
            val expanded = SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(EnvConfigSource(mapOf("LEMLINE_MESSAGING_SQLITE_PATH" to fromYaml), 300))
                .withSources(LemlineConfigSourceFactory())
                .build()
            assertEquals("yaml-prefix.db", expanded.getValue("mp.messaging.outgoing.commands-out.path", String::class.java))

            val unresolved = "${'$'}{missing.lemline.config.path}"
            val config = SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(EnvConfigSource(mapOf("LEMLINE_MESSAGING_SQLITE_PATH" to unresolved), 300))
                .withSources(LemlineConfigSourceFactory())
                .build()
            assertEquals(unresolved, config.getConfigValue("mp.messaging.outgoing.commands-out.path").rawValue)

            val fileText = Files.readString(file)
            Files.writeString(file, fileText, Charsets.UTF_16)
            val utf16 = SmallRyeConfigBuilder().withSources(LemlineConfigSourceFactory()).build()
            assertEquals("file.db", utf16.getValue("mp.messaging.outgoing.commands-out.path", String::class.java))

            val invalidFiles = listOf(
                fileText.replace("path: file.db", "path: ' '"),
                fileText.replace("path: file.db", "path:"),
                "$fileText\n---\nlemline:\n  messaging:\n    sqlite:\n      path:\n",
                "$fileText\n---\nlemline:\n  messaging:\n    sqlite:\n      path: {}\n",
            )
            for (contents in invalidFiles) {
                Files.writeString(file, contents)
                assertFailsWith<IllegalArgumentException> {
                    SmallRyeConfigBuilder().withSources(LemlineConfigSourceFactory()).build()
                }
            }
        } finally {
            ConfigPathHolder.configPath = previousPath
            Files.deleteIfExists(file)
        }
    }

    private fun source(properties: Map<String, String>, ordinal: Int) =
        object : MapBackedConfigSource("test-$ordinal", properties, ordinal) {}
}
