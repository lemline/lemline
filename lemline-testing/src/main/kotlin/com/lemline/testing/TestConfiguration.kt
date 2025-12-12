// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import com.lemline.testing.infrastructure.BrokerType
import com.lemline.testing.infrastructure.DatabaseType
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for E2E test execution.
 */
data class TestConfiguration(
    /**
     * Message broker type.
     */
    val brokerType: BrokerType,

    /**
     * Database type.
     */
    val databaseType: DatabaseType,

    /**
     * Path to native runner binary.
     * If null, will be auto-detected.
     */
    val runnerBinaryPath: Path? = null,

    /**
     * Maximum time to wait for workflow completion.
     */
    val defaultTimeout: Duration = 30.seconds,

    /**
     * Maximum time to wait for runner startup.
     */
    val startupTimeout: Duration = 60.seconds,

    /**
     * Maximum time to wait for runner shutdown.
     */
    val shutdownTimeout: Duration = 10.seconds,

    /**
     * Tags to exclude from test execution.
     */
    val excludeTags: Set<String> = emptySet(),

    /**
     * Platform filter (e.g., "unix-only", "windows-only").
     * Tests with non-matching platform tags are skipped.
     */
    val platform: String = detectPlatform()
) {
    companion object {
        /**
         * Default configuration using Kafka and PostgreSQL.
         */
        fun default() = TestConfiguration(
            brokerType = BrokerType.KAFKA,
            databaseType = DatabaseType.POSTGRESQL
        )

        /**
         * Configuration for Kafka with PostgreSQL.
         */
        fun kafkaPostgres() = TestConfiguration(
            brokerType = BrokerType.KAFKA,
            databaseType = DatabaseType.POSTGRESQL
        )

        /**
         * Configuration for Kafka with MySQL.
         */
        fun kafkaMysql() = TestConfiguration(
            brokerType = BrokerType.KAFKA,
            databaseType = DatabaseType.MYSQL
        )

        /**
         * Configuration for RabbitMQ with PostgreSQL.
         */
        fun rabbitmqPostgres() = TestConfiguration(
            brokerType = BrokerType.RABBITMQ,
            databaseType = DatabaseType.POSTGRESQL
        )

        /**
         * Configuration for RabbitMQ with MySQL.
         */
        fun rabbitmqMysql() = TestConfiguration(
            brokerType = BrokerType.RABBITMQ,
            databaseType = DatabaseType.MYSQL
        )

        /**
         * Detect the current platform.
         */
        fun detectPlatform(): String {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("win") -> "windows"
                os.contains("mac") -> "macos"
                os.contains("nix") || os.contains("nux") -> "linux"
                else -> "unknown"
            }
        }
    }
}
