// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.config

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Integration test that verifies connector configuration works correctly when running
 * the actual JAR file with external config.
 *
 * This test catches the Quarkus 3.27-3.30.1 regression where ConfigSource ordinal was
 * ignored for mp.messaging.*.connector properties. The bug only manifests when running
 * the built JAR, not in Quarkus test context.
 *
 * To run this test:
 * 1. Build the JAR: ./gradlew :lemline-runner:assemble
 * 2. Set environment variable: export RUN_JAR_TESTS=true
 * 3. Run: ./gradlew :lemline-runner:test --tests "*.ConnectorConfigJarTest"
 *
 * The test is disabled by default to avoid CI failures when JAR is not built.
 */
@EnabledIfEnvironmentVariable(named = "RUN_JAR_TESTS", matches = "true")
class ConnectorConfigJarTest {

    private val projectRoot = findProjectRoot()
    private val jarPath = "$projectRoot/lemline-runner/build/quarkus-app/quarkus-run.jar"
    private val kafkaConfigPath = "$projectRoot/examples/lemline-kafka-pg.yaml"
    private val pgmqConfigPath = "$projectRoot/examples/lemline-pgmq.yaml"

    @Test
    fun `Kafka connector is actually used when using kafka config file`() {
        val output = runJarWithConfig(kafkaConfigPath)

        // Check that Kafka producer is actually created (not just configured)
        // SmallRye Kafka logs "SRMSG18258: Kafka producer..." when creating producers
        val kafkaProducerCreated = output.contains("SRMSG18258") ||
            output.contains("Kafka producer") ||
            output.contains("kafka-producer-commands-out")

        assertTrue(
            kafkaProducerCreated,
            """
            Kafka producer not created!

            The config shows Kafka connector, but SmallRye didn't create a Kafka producer.
            This indicates the Quarkus ConfigSource ordinal regression (3.27-3.30.1)
            where the build-time default connector overrides the runtime config.

            Expected: SRMSG18258 log message showing Kafka producer creation

            Resolution: Upgrade to Quarkus 3.30.6+

            Output snippet (last 3000 chars):
            ${output.takeLast(3000)}
            """.trimIndent()
        )
    }

    @Test
    fun `PGMQ connector is actually used when using pgmq config file`() {
        val output = runJarWithConfig(pgmqConfigPath)

        // Check that PGMQ channel is actually created (not just configured)
        // PgmqConnector logs "Creating PGMQ outgoing channel" when setting up channels
        val pgmqChannelCreated = output.contains("PGMQ outgoing channel")

        assertTrue(
            pgmqChannelCreated,
            """
            PGMQ channel not created!

            The config shows PGMQ connector, but PgmqConnector didn't create a channel.
            This indicates the Quarkus ConfigSource ordinal regression (3.27-3.30.1)
            where the build-time default connector overrides the runtime config.

            Expected: "Creating PGMQ outgoing channel" log message

            Resolution: Upgrade to Quarkus 3.30.6+

            Output snippet (last 3000 chars):
            ${output.takeLast(3000)}
            """.trimIndent()
        )
    }

    private fun runJarWithConfig(configPath: String): String {
        val jarFile = File(jarPath)
        require(jarFile.exists()) {
            "JAR file not found at $jarPath. Run './gradlew :lemline-runner:assemble' first."
        }

        val configFile = File(configPath)
        require(configFile.exists()) {
            "Config file not found at $configPath"
        }

        // Use 'instance start' with --info to see the generated mp.messaging properties
        // The command will fail (no DB/broker) but we can see the config in the logs
        val process = ProcessBuilder(
            "java", "-jar", jarPath,
            "instance", "start", "test", "test-workflow",
            "--info",
            "--config=$configPath"
        )
            .directory(File(projectRoot))
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)

        return output
    }

    private fun extractGeneratedProperties(output: String): String {
        val startMarker = "Lemline generated properties:"
        val startIndex = output.indexOf(startMarker)
        if (startIndex == -1) return output

        // Find the next log line (starts with timestamp pattern or end of output)
        val afterStart = output.substring(startIndex)
        val endIndex = afterStart.indexOf("\n[") // Next log line starts with timestamp
        return if (endIndex > 0) {
            afterStart.substring(0, endIndex)
        } else {
            afterStart.take(2000)
        }
    }

    private fun findProjectRoot(): String {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").exists() || File(dir, "settings.gradle").exists()) {
                return dir.absolutePath
            }
            dir = dir.parentFile
        }
        return System.getProperty("user.dir")
    }
}
