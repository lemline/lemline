// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import com.lemline.testing.infrastructure.BrokerType
import com.lemline.testing.infrastructure.DatabaseType
import com.lemline.testing.infrastructure.KafkaTestContainer
import com.lemline.testing.infrastructure.MySQLTestContainer
import com.lemline.testing.infrastructure.PostgresTestContainer
import com.lemline.testing.infrastructure.RabbitMQTestContainer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates runner configuration files pointing to Testcontainers infrastructure.
 */
class RunnerConfigGenerator {

    /**
     * Generate a runner configuration file for the given infrastructure.
     *
     * @param brokerType Type of message broker
     * @param databaseType Type of database
     * @param kafka Kafka container (required if brokerType is KAFKA)
     * @param rabbitMQ RabbitMQ container (required if brokerType is RABBITMQ)
     * @param postgres PostgreSQL container (required if databaseType is POSTGRESQL)
     * @param mysql MySQL container (required if databaseType is MYSQL)
     * @return Path to the generated configuration file
     */
    fun generate(
        brokerType: BrokerType,
        databaseType: DatabaseType,
        kafka: KafkaTestContainer? = null,
        rabbitMQ: RabbitMQTestContainer? = null,
        postgres: PostgresTestContainer? = null,
        mysql: MySQLTestContainer? = null
    ): Path {
        // Use a random port to avoid conflicts with other test runs
        val randomPort = (10000..60000).random()

        val config = buildString {
            appendLine("lemline:")

            // Database configuration
            appendLine("  database:")
            appendLine("    migrate-at-start: true")
            appendLine("    baseline-on-migrate: true")
            when (databaseType) {
                DatabaseType.POSTGRESQL -> {
                    requireNotNull(postgres) { "PostgreSQL container required for POSTGRESQL database type" }
                    appendLine("    type: postgresql")
                    appendLine("    postgresql:")
                    appendLine("      host: ${postgres.getHost()}")
                    appendLine("      port: ${postgres.getPort()}")
                    appendLine("      name: ${postgres.getDatabaseName()}")
                    appendLine("      username: ${postgres.getUsername()}")
                    appendLine("      password: ${postgres.getPassword()}")
                }
                DatabaseType.MYSQL -> {
                    requireNotNull(mysql) { "MySQL container required for MYSQL database type" }
                    appendLine("    type: mysql")
                    appendLine("    mysql:")
                    appendLine("      host: ${mysql.getHost()}")
                    appendLine("      port: ${mysql.getPort()}")
                    appendLine("      name: ${mysql.getDatabaseName()}")
                    appendLine("      username: ${mysql.getUsername()}")
                    appendLine("      password: ${mysql.getPassword()}")
                }
            }

            // Messaging configuration
            appendLine("  messaging:")

            // Channel enable flags (required for runner to activate channels)
            appendLine("    commands:")
            appendLine("      consumer:")
            appendLine("        enabled: true")
            appendLine("      producer:")
            appendLine("        enabled: true")
            appendLine("    events:")
            appendLine("      consumer:")
            appendLine("        enabled: true")
            appendLine("      producer:")
            appendLine("        enabled: true")
            appendLine("    lifecycleevents:")
            appendLine("      producer:")
            appendLine("        enabled: true")

            when (brokerType) {
                BrokerType.KAFKA -> {
                    requireNotNull(kafka) { "Kafka container required for KAFKA broker type" }
                    appendLine("    type: kafka")
                    appendLine("    kafka:")
                    appendLine("      brokers: ${kafka.getBootstrapServers()}")
                    // Use ConfigMapping-compatible property names (workflows/database)
                    // These match LemlineConfiguration interface paths
                    appendLine("      workflows:")
                    appendLine("        topic: lemline-test-commands")
                    appendLine("        consumer:")
                    appendLine("          group-id: lemline-test-commands-group")
                    appendLine("      database:")
                    appendLine("        topic: lemline-test-events")
                    appendLine("        consumer:")
                    appendLine("          group-id: lemline-test-events-group")
                    appendLine("      lifecycleevents:")
                    appendLine("        topic: lemline-test-lifecycle")
                }
                BrokerType.RABBITMQ -> {
                    requireNotNull(rabbitMQ) { "RabbitMQ container required for RABBITMQ broker type" }
                    appendLine("    type: rabbitmq")
                    appendLine("    rabbitmq:")
                    appendLine("      hostname: ${rabbitMQ.getHost()}")
                    appendLine("      port: ${rabbitMQ.getAmqpPort()}")
                    appendLine("      username: ${rabbitMQ.getUsername()}")
                    appendLine("      password: ${rabbitMQ.getPassword()}")
                    // Use ConfigMapping-compatible property names (workflows/database)
                    appendLine("      workflows:")
                    appendLine("        queue: lemline-test-commands")
                    appendLine("      database:")
                    appendLine("        queue: lemline-test-events")
                    appendLine("      lifecycleevents:")
                    appendLine("        queue: lemline-test-lifecycle")
                }
            }

            // Use random port to avoid conflicts between test runs
            appendLine("  metrics:")
            appendLine("    port: $randomPort")
        }

        // Write to temp file
        val configFile = Files.createTempFile("lemline-test-config-", ".yaml")
        Files.writeString(configFile, config)

        return configFile
    }

    /**
     * Generate a mock configuration file from MockConfig.
     *
     * @param mockConfig The mock configuration
     * @return Path to the generated mock configuration file
     */
    fun generateMockConfig(mockConfig: MockConfig): Path {
        val yaml = mockConfig.toYaml()
        val mockFile = Files.createTempFile("lemline-mock-config-", ".yaml")
        Files.writeString(mockFile, yaml)
        return mockFile
    }
}
