// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.util.*

const val PRODUCER_ENABLED = "lemline.messaging.producer.enabled"
const val CONSUMER_ENABLED = "lemline.messaging.consumer.enabled"
const val DATABASE_TYPE = "lemline.database.type"
const val MESSAGING_TYPE = "lemline.messaging.type"
const val MESSAGING_CONSUMER_CONCURRENCY = "lemline.messaging.consumer.concurrency"
const val MIGRATE_AT_START = "lemline.database.migrate-at-start"

/**
 * Type-safe configuration mapping for Lemline.
 * This interface defines the structure of Lemline's configuration using Quarkus's @ConfigMapping.
 *
 * Configuration Loading Process:
 * 1. Quarkus loads configuration in this order:
 *    - System properties (-D parameters)
 *    - Environment variables
 *    - .env file
 *    - application.properties/application.yaml
 *    - Profile-specific files (application-{profile}.properties)
 *    - Custom ConfigSource implementations
 *
 * 2. Profile Support:
 *    - Different configurations for dev, test, prod environments
 *    - Profile-specific files: application-dev.properties, application-prod.properties, etc.
 *    - Activate with: -Dquarkus.profile=dev
 *
 * 3. Environment Variables:
 *    - Converted automatically: lemline.database.type -> LEMLINE_DATABASE_TYPE
 *    - Case-insensitive matching
 *
 * @see LemlineConfigSourceFactory for configuration transformation
 * @see https://quarkus.io/guides/config-reference for Quarkus configuration details
 */
@Suppress("unused")
@ConfigMapping(prefix = "lemline")
interface LemlineConfiguration {
    fun config(): Optional<String>
    fun database(): DatabaseConfig
    fun messaging(): MessagingConfig
    fun outbox(): OutboxConfig
    fun metrics(): MetricsConfig

    /**
     * Database configuration mapping.
     */
    interface DatabaseConfig {

        /**
         * Database type. Must be one of: in-memory, postgresql, mysql
         */
        @Pattern(regexp = "in-memory|postgresql|mysql")
        fun type(): String

        /**
         * Whether to run database migrations at startup
         * Default: false
         */
        @WithDefault("false")
        fun migrateAtStart(): Boolean

        /**
         * Whether to baseline existing database
         * Default: false
         */
        @WithDefault("false")
        fun baselineOnMigrate(): Boolean

        /**
         * Optional PostgresSQL configuration
         */
        fun postgresql(): Optional<PostgreSQLConfig>

        /**
         * Optional MySQL configuration
         */
        fun mysql(): Optional<MySQLConfig>
    }

    /**
     * PostgresSQL-specific configuration.
     * IMPORTANT: default values are not applied here, but in [LemlineConfigSourceFactory].
     * Adding default values here would automatically create an entry in the configuration.
     */
    interface PostgreSQLConfig {
        fun host(): String

        @Min(1)
        fun port(): Int
        fun username(): String
        fun password(): String
        fun name(): String
    }

    /**
     * MySQL-specific configuration.
     * IMPORTANT: default values are not applied here, but in [LemlineConfigSourceFactory].
     * Adding default values here would automatically create an entry in the configuration.
     */
    interface MySQLConfig {
        fun host(): String

        @Min(1)
        fun port(): Int
        fun username(): String
        fun password(): String
        fun name(): String
    }

    /**
     * Messaging configuration mapping.
     */
    interface MessagingConfig {

        // Producer settings
        fun producer(): ProducerConfig

        // Consumer settings
        fun consumer(): ConsumerConfig

        @Pattern(regexp = "in-memory|kafka|rabbitmq")
        fun type(): String

        // Broker Specific settings
        fun kafka(): Optional<KafkaConfig>
        fun rabbitmq(): Optional<RabbitMQConfig>
    }

    interface ProducerConfig {
        @WithDefault("false")
        fun enabled(): Boolean
    }

    interface ConsumerConfig {
        @WithDefault("false")
        fun enabled(): Boolean

        @WithDefault("64")
        fun concurrency(): Int
    }

    /**
     * Kafka-specific configuration.
     * IMPORTANT: default values are not applied here, but in [LemlineConfigSourceFactory].
     * Adding default values here would automatically create an entry in the configuration.
     */
    interface KafkaConfig {
        fun brokers(): String
        fun topic(): String
        fun groupId(): String

        @Pattern(regexp = "earliest|latest")
        fun offsetReset(): String

        // Optional settings
        fun topicDlq(): Optional<String>
        fun topicOut(): Optional<String>
        fun securityProtocol(): Optional<String>
        fun saslMechanism(): Optional<String>
        fun saslUsername(): Optional<String>
        fun saslPassword(): Optional<String>
//
//        fun workflows(): Optional<KafkaTopicConfig>
//        fun ingestion(): Optional<KafkaTopicConfig>
//    }
//
//    interface KafkaTopicConfig {
//        fun topic(): String
//        fun consumer(): KafkaConsumerConfig
//        fun producer(): KafkaProducerConfig
//
//        @TestOnly
//        fun topicOut(): Optional<String>
//    }
//
//    interface KafkaConsumerConfig {
//        @WithDefault("false")
//        fun enabled(): Boolean
//
//        @WithDefault("1")
//        fun concurrency(): Int
//
//        @WithDefault("group-1")
//        fun groupId(): String
//
//        @WithDefault("earliest")
//        @Pattern(regexp = "earliest|latest")
//        fun offsetReset(): String
//
//        @WithDefault("earliest")
//        fun topicDlq(): String
//    }
//
//    interface KafkaProducerConfig {
//        @WithDefault("false")
//        fun enabled(): Boolean
    }

    /**
     * RabbitMQ-specific configuration.
     * IMPORTANT: default values are not applied here, but in [LemlineConfigSourceFactory].
     * Adding default values here would automatically create an entry in the configuration.
     */
    interface RabbitMQConfig {
        fun hostname(): String

        @Min(1)
        fun port(): Int
        fun username(): String
        fun password(): String
        fun virtualHost(): String
        fun queue(): String

        fun queueDlq(): Optional<String>
        fun queueOut(): Optional<String>
        fun exchangeName(): Optional<String>
        fun sslEnabled(): Optional<Boolean>
    }

    interface OutboxConfig {
        fun enabled(): Optional<Boolean>
        fun wait(): WaitOutboxConfig
        fun retry(): RetryOutboxConfig
        fun runWorkflow(): RunWorkflowOutboxConfig
        fun schedule(): ScheduleOutboxConfig
    }

    /**
     * Wait service configuration.
     * Controls the behavior of the wait message processing.
     */
    interface WaitOutboxConfig {
        fun enabled(): Optional<Boolean>
        fun outbox(): OutboxProcessingConfig
        fun cleanup(): OutboxCleanupConfig
    }

    /**
     * Retry service configuration.
     * Controls the behavior of the retry message processing.
     */
    interface RetryOutboxConfig {
        fun enabled(): Optional<Boolean>
        fun outbox(): OutboxProcessingConfig
        fun cleanup(): OutboxCleanupConfig
    }

    /**
     * Run Workflow service configuration.
     * Controls the behavior of the run workflow message processing.
     */
    interface RunWorkflowOutboxConfig {
        fun enabled(): Optional<Boolean>
        fun outbox(): OutboxProcessingConfig
        fun cleanup(): OutboxCleanupConfig
    }

    /**
     * Schedule service configuration.
     * Controls the behavior of the schedule message processing.
     */
    interface ScheduleOutboxConfig {
        fun enabled(): Optional<Boolean>
        fun outbox(): OutboxProcessingConfig
        fun cleanup(): OutboxCleanupConfig
    }

    /**
     * Outbox pattern configuration.
     * Controls the behavior of message processing in the outbox pattern.
     */
    interface OutboxProcessingConfig {
        /**
         * Processing interval
         * Default: 10 second
         */
        @WithDefault("10s")
        fun every(): String

        /**
         * Maximum number of messages to process in one batch
         * Default: 1000
         */
        @WithDefault("1000")
        @Min(1)
        fun batchSize(): Int

        /**
         * Initial delay before starting processing
         * Default: 30 seconds
         */
        @WithDefault("30s")
        fun initialDelay(): String

        /**
         * Maximum number of processing attempts
         * Default: 5
         */
        @WithDefault("5")
        @Min(1)
        fun maxAttempts(): Int

        val every get() = every().toDuration()
        val batchSize get() = batchSize()
        val initialDelay get() = initialDelay().toDuration()
        val maxAttempts get() = maxAttempts()
    }

    /**
     * Cleanup configuration.
     * Controls the behavior of message cleanup in the outbox pattern.
     */
    interface OutboxCleanupConfig {
        /**
         * Cleanup interval
         * Default: 1 hour
         */
        @WithDefault("1h")
        fun every(): String

        /**
         * Age of messages to clean up
         * Default: 7 days
         */
        @WithDefault("7d")
        fun after(): String

        /**
         * Maximum number of messages to clean up in one batch
         * Default: 1000
         */
        @WithDefault("1000")
        @Min(1)
        fun batchSize(): Int

        val every get() = every().toDuration()
        val after get() = after().toDuration()
        val batchSize get() = batchSize()
    }

    /**
     * Metrics configuration
     * default values are not applied here, but in [LemlineConfigSourceFactory].
     */
    interface MetricsConfig {
        fun port(): Int
        fun path(): String
    }
}
