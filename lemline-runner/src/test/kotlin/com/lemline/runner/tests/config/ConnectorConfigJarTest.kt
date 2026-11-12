// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.config

import com.lemline.runner.common.test.RequiresDocker
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer

/**
 * Verify runtime connector selection in the packaged application, independently of local services.
 * Build first in a separate invocation: ./gradlew :lemline-runner:assemble
 * Then run: RUN_JAR_TESTS=true ./gradlew :lemline-runner:test --tests '*.ConnectorConfigJarTest'
 */
@RequiresDocker
@EnabledIfEnvironmentVariable(named = "RUN_JAR_TESTS", matches = "true")
class ConnectorConfigJarTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `Kafka connector is actually used when using kafka config file`() {
        postgres().use { database ->
            database.start()
            KafkaContainer("apache/kafka:3.7.0").use { kafka ->
                kafka.start()
                assertConnectorStarts(database, """
                    kafka:
                      brokers: '${kafka.bootstrapServers}'
                """.trimIndent()) { output -> output.contains("SRMSG18258") }
            }
        }
    }

    @Test
    fun `PGMQ connector is actually used when using pgmq config file`() {
        postgres().use { database ->
            database.start()
            assertConnectorStarts(database, "pgmq:\n" + databaseSettings(database).prependIndent("  ")) { output ->
                output.lineSequence().any { it.contains("PGMQ outgoing channel") && it.contains(" initialized") }
            }
        }
    }

    private fun postgres() = PostgreSQLContainer("postgres:14-alpine")
        .withDatabaseName("connector_test")
        .withUsername("test")
        .withPassword("test")

    private fun databaseSettings(database: PostgreSQLContainer<*>) = """
        host: '${database.host}'
        port: ${database.firstMappedPort}
        database: '${database.databaseName}'
        username: '${database.username}'
        password: '${database.password}'
    """.trimIndent()

    private fun assertConnectorStarts(
        database: PostgreSQLContainer<*>,
        messaging: String,
        connectorReady: (String) -> Boolean,
    ) {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val jar = File(root, "lemline-runner/build/quarkus-app/quarkus-run.jar")
        require(jar.isFile) { "Build the JAR first in a separate invocation: ./gradlew :lemline-runner:assemble" }
        val config = temporaryDirectory.resolve("lemline.yaml").toFile()
        config.writeText("lemline:\n  database:\n    postgresql:\n" +
            databaseSettings(database).prependIndent("      ") + "\n  analytics:\n    postgresql:\n" +
            databaseSettings(database).prependIndent("      ") + "\n  messaging:\n" + messaging.prependIndent("    ") + "\n")
        val log = temporaryDirectory.resolve("application.log").toFile()
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-Dquarkus.http.port=0", "-Dquarkus.grpc.server.port=0", "-jar", jar.path,
            "listen", "--info", "--config=${config.path}",
        ).apply {
            directory(root)
            // External settings must not redirect this test to a developer's services.
            environment().keys.removeIf { it.startsWith("LEMLINE_") || it.startsWith("QUARKUS_") }
            redirectErrorStream(true)
            redirectOutput(log)
        }.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (process.isAlive && System.nanoTime() < deadline) {
                val output = log.readText()
                if (connectorReady(output) && output.contains("Start consuming messages...")) return
                Thread.sleep(100)
            }
            assertTrue(false, "Packaged application did not start the requested connector.\n${log.readText().takeLast(10000)}")
        } finally {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(10, TimeUnit.SECONDS)) { "Packaged application did not terminate" }
            }
        }
    }
}
