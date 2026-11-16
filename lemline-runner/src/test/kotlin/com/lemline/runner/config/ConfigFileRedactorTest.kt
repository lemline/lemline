// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigFileRedactorTest {

    private val yaml = """
        # Production configuration
        lemline:
          database:
            type: postgresql
            postgresql:
              host: db.internal
              username: lemline
              password: "s3cr3t-db"   # rotated monthly
          messaging:
            pgmq:
              password: s3cr3t-mq
          gateway:
            grpc:
              port: 9443
            tls:
              private-key: /etc/lemline/tls/gateway.key
              trust-store-password: s3cr3t-ts
            cors:
              origins: http://localhost:5173
    """.trimIndent() + "\n"

    @AfterTest
    fun reset() = LoadedConfigFile.reset()

    @Test
    fun `masks every secret and keeps the rest as written`() {
        val result = assertNotNull(ConfigFileRedactor.redact(yaml))
        listOf("s3cr3t-db", "s3cr3t-mq", "s3cr3t-ts").forEach { assertFalse(it in result.text, "$it leaked") }
        assertTrue("# Production configuration" in result.text)
        assertTrue("# rotated monthly" in result.text)
        assertTrue("private-key: /etc/lemline/tls/gateway.key" in result.text)
        assertTrue("password: ${ConfigFileRedactor.MASK}   # rotated monthly" in result.text)
        assertEquals(yaml.lines().size, result.text.lines().size)
    }

    @Test
    fun `reports each key with its line`() {
        val keys = assertNotNull(ConfigFileRedactor.redact(yaml)).keys.associateBy { it.key }
        assertEquals(19, keys.getValue("lemline.gateway.cors.origins").line)
        assertEquals(14, keys.getValue("lemline.gateway.grpc.port").line)
        assertTrue(keys.getValue("lemline.database.postgresql.password").secret)
        assertFalse(keys.getValue("lemline.gateway.tls.private-key").secret)
    }

    @Test
    fun `masks a secret reached through an alias`() {
        val text = """
            shared: &pw hunter2
            lemline:
              database:
                postgresql:
                  password: *pw
        """.trimIndent()
        val result = assertNotNull(ConfigFileRedactor.redact(text))
        assertFalse("hunter2" in result.text)
    }

    @Test
    fun `keeps line numbers for a multi-line secret`() {
        val text = "lemline:\n  token: |\n    line-one\n    line-two\n  after: 1\n"
        val result = assertNotNull(ConfigFileRedactor.redact(text))
        assertFalse("line-one" in result.text)
        assertEquals(text.lines().size, result.text.lines().size)
        assertEquals(5, result.keys.single { it.key == "lemline.after" }.line)
    }

    @Test
    fun `masks credentials stored under other names or inside a URL`() {
        val text = """
            quarkus:
              kafka:
                sasl:
                  jaas-config: org.apache.kafka.common.security.plain.PlainLoginModule required password="k4fka";
            lemline:
              analytics:
                url: postgresql://app:urlpw@db.internal/lemline
              gateway:
                cors:
                  origins: https://dashboard.example
              brokers:
                - kafka://svc:listpw@kafka-1:9092
        """.trimIndent()
        val result = assertNotNull(ConfigFileRedactor.redact(text))
        listOf("k4fka", "urlpw", "listpw").forEach { assertFalse(it in result.text, "$it leaked") }
        assertTrue("origins: https://dashboard.example" in result.text)
        assertTrue(result.keys.single { it.key == "lemline.analytics.url" }.secret)
        assertTrue(ConfigFileRedactor.hasEmbeddedPassword("https://user:pw@host/path"))
        assertFalse(ConfigFileRedactor.hasEmbeddedPassword("https://host:9443/path"))
    }

    @Test
    fun `refuses text it cannot parse`() {
        assertNull(ConfigFileRedactor.redact("lemline: [unclosed\n  password: x"))
    }

    @Test
    fun `the first capture of the file wins over later edits`() {
        val file = Files.createTempFile("lemline", ".yaml")
        Files.writeString(file, "lemline:\n  gateway:\n    enabled: true\n")
        LoadedConfigFile.capture(file, ConfigFileLocator.OPTION, mapOf("lemline.gateway.enabled" to "true"))
        Files.writeString(file, "lemline:\n  gateway:\n    enabled: false\n")
        LoadedConfigFile.capture(file, ConfigFileLocator.OPTION, mapOf("lemline.gateway.enabled" to "false"))

        val snapshot = assertNotNull(LoadedConfigFile.snapshot)
        assertTrue("enabled: true" in snapshot.text!!)
        assertEquals("true", snapshot.properties["lemline.gateway.enabled"])
        assertEquals(ConfigFileLocator.OPTION, snapshot.locator)
    }

    @Test
    fun `building the configuration source captures the file`() {
        val file = Files.createTempFile("lemline", ".yaml")
        Files.writeString(file, "lemline:\n  gateway:\n    cors:\n      origins: https://dashboard.example\n")
        val previous = ConfigPathHolder.configPath
        try {
            ConfigPathHolder.configPath = file
            ConfigPathHolder.locator = ConfigFileLocator.ENVIRONMENT
            LemlineConfigSource()
        } finally {
            ConfigPathHolder.configPath = previous
            ConfigPathHolder.locator = null
        }
        val snapshot = assertNotNull(LoadedConfigFile.snapshot)
        assertEquals(file.toAbsolutePath().normalize(), snapshot.path)
        assertEquals(ConfigFileLocator.ENVIRONMENT, snapshot.locator)
        assertEquals("https://dashboard.example", snapshot.properties["lemline.gateway.cors.origins"])
    }
}
