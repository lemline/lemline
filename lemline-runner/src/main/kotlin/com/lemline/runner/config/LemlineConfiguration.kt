// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.config.LemlineConfigConstants.CONSUMER_CONCURRENCY_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.DB_BASELINE_ON_MIGRATE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.DB_MIGRATE_AT_START_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.INGESTION_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_BROKERS_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_OFFSET_RESET_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.METRICS_PATH_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.METRICS_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_NAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_USERNAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_NAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_USERNAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_VHOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.WORKFLOWS_TOPIC_DEFAULT
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.util.*

const val DATABASE_TYPE = "lemline.database.type"
const val MESSAGING_TYPE = "lemline.messaging.type"
const val MIGRATE_AT_START = "lemline.database.migrate-at-start"

const val WORKFLOWS_PRODUCER_ENABLED = "lemline.messaging.workflows.producer.enabled"
const val WORKFLOWS_CONSUMER_ENABLED = "lemline.messaging.workflows.consumer.enabled"
const val WORKFLOWS_CONSUMER_CONCURRENCY = "lemline.messaging.workflows.consumer.concurrency"

const val INGESTION_PRODUCER_ENABLED = "lemline.messaging.ingestion.producer.enabled"
const val INGESTION_CONSUMER_ENABLED = "lemline.messaging.ingestion.consumer.enabled"
const val INGESTION_CONSUMER_CONCURRENCY = "lemline.messaging.ingestion.consumer.concurrency"

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
 * @see LemlineConfigSource for configuration transformation
 * @see [https://quarkus.io/guides/config-reference] for Quarkus configuration details
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
         * Database type.
         * If not provided, it will be set in [LemlineConfigSource]
         */
        @Pattern(regexp = "in-memory|postgresql|mysql")
        fun type(): String

        /**
         * Whether to run database migrations at startup
         */
        @WithDefault(DB_MIGRATE_AT_START_DEFAULT)
        fun migrateAtStart(): Boolean

        /**
         * Whether to baseline existing database
         */
        @WithDefault(DB_BASELINE_ON_MIGRATE_DEFAULT)
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
     */
    interface PostgreSQLConfig {
        @WithDefault(POSTGRES_HOST_DEFAULT)
        fun host(): String

        @WithDefault(POSTGRES_PORT_DEFAULT)
        fun port(): Int

        @WithDefault(POSTGRES_USERNAME_DEFAULT)
        fun username(): String

        @WithDefault(POSTGRES_PASSWORD_DEFAULT)
        fun password(): Optional<String>

        @WithDefault(POSTGRES_NAME_DEFAULT)
        fun name(): Optional<String>
    }

    /**
     * MySQL-specific configuration.
     */
    interface MySQLConfig {
        @WithDefault(MYSQL_HOST_DEFAULT)
        fun host(): String

        @WithDefault(MYSQL_PORT_DEFAULT)
        fun port(): Optional<Int>

        @WithDefault(MYSQL_USERNAME_DEFAULT)
        fun username(): Optional<String>

        @WithDefault(MYSQL_PASSWORD_DEFAULT)
        fun password(): Optional<String>

        @WithDefault(MYSQL_NAME_DEFAULT)
        fun name(): Optional<String>
    }

    /**
     * Messaging configuration mapping.
     */
    interface MessagingConfig {

        /**
         * Database type.
         * If not provided, it will be set in [LemlineConfigSource]
         */
        @Pattern(regexp = "in-memory|kafka|rabbitmq")
        fun type(): String

        fun workflows(): Optional<WorkflowsConfig>


        fun ingestion(): Optional<IngestionConfig>

        /**
         * Optional Kafka configuration
         */
        fun kafka(): Optional<KafkaConfig>

        /**
         * Optional RabbitMQ configuration
         */
        fun rabbitmq(): Optional<RabbitMQConfig>
    }

    interface WorkflowsConfig {
        fun producer(): ProducerConfig
        fun consumer(): ConsumerConfig
    }

    interface IngestionConfig {
        fun producer(): ProducerConfig
        fun consumer(): ConsumerConfig
    }

    interface ProducerConfig {
        @WithDefault("false")
        fun enabled(): Boolean
    }

    interface ConsumerConfig {
        @WithDefault("false")
        fun enabled(): Boolean

        @WithDefault(CONSUMER_CONCURRENCY_DEFAULT)
        fun concurrency(): Long
    }

    /**
     * Kafka-specific configuration.
     */
    interface KafkaConfig {
        @WithDefault(KAFKA_BROKERS_DEFAULT)
        fun brokers(): String

        // Optional
        fun securityProtocol(): Optional<String>
        fun saslMechanism(): Optional<String>
        fun saslUsername(): Optional<String>
        fun saslPassword(): Optional<String>

        fun workflows(): KafkaWorkflowsConfig
        fun ingestion(): KafkaIngestionConfig
    }

    interface KafkaWorkflowsConfig {
        @WithDefault(WORKFLOWS_TOPIC_DEFAULT)
        fun topic(): String
        fun consumer(): KafkaConsumerConfig
        fun producer(): KafkaProducerConfig
    }

    interface KafkaIngestionConfig {
        @WithDefault(INGESTION_TOPIC_DEFAULT)
        fun topic(): String
        fun consumer(): KafkaConsumerConfig
        fun producer(): KafkaProducerConfig
    }

    interface KafkaConsumerConfig {
        @WithDefault(CONSUMER_CONCURRENCY_DEFAULT)
        fun concurrency(): Int

        @WithDefault(KAFKA_GROUP_ID_DEFAULT)
        fun groupId(): String

        @Pattern(regexp = "latest|earliest")
        @WithDefault(KAFKA_OFFSET_RESET_DEFAULT)
        fun offsetReset(): String

        fun topicDlq(): Optional<String>

        fun topicOut(): Optional<String>
    }

    interface KafkaProducerConfig {
        fun topicOut(): Optional<String>
    }

    /**
     * RabbitMQ-specific configuration.
     * IMPORTANT: default values are not applied here, but in [LemlineConfigSource].
     * Adding default values here would automatically create an entry in the configuration.
     */
    interface RabbitMQConfig {
        fun hostname(): Optional<String>
        fun port(): Optional<Int>
        fun username(): Optional<String>
        fun password(): Optional<String>
        fun sslEnabled(): Optional<Boolean>
        fun virtualHost(): Optional<String>

        fun workflows(): RabbitWorkflowsConfig
        fun ingestion(): RabbitIngestionConfig
    }

    interface RabbitWorkflowsConfig {

        @WithDefault(WORKFLOWS_TOPIC_DEFAULT)
        fun queue(): String
        fun consumer(): RabbitConsumerConfig
        fun producer(): RabbitProducerConfig
    }

    interface RabbitIngestionConfig {
        @WithDefault(RABBITMQ_VHOST_DEFAULT)
        fun virtualHost(): Optional<String>

        @WithDefault(INGESTION_TOPIC_DEFAULT)
        fun queue(): String
        fun consumer(): RabbitConsumerConfig
        fun producer(): RabbitProducerConfig
    }

    interface RabbitConsumerConfig {
        @WithDefault(CONSUMER_CONCURRENCY_DEFAULT)
        fun concurrency(): Int
        fun queueDlq(): Optional<String>
    }

    interface RabbitProducerConfig {
        fun queueOut(): Optional<String>
        fun exchangeName(): Optional<String>
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
         */
        @WithDefault("10s")
        fun every(): String

        /**
         * Maximum number of messages to process in one batch
         */
        @WithDefault("1000")
        @Min(1)
        fun batchSize(): Int

        /**
         * Initial delay before starting processing
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
         */
        @WithDefault("1h")
        fun every(): String

        /**
         * Age of messages to clean up
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
     */
    interface MetricsConfig {
        @WithDefault(METRICS_PORT_DEFAULT)
        fun port(): Int

        @WithDefault(METRICS_PATH_DEFAULT)
        fun path(): String
    }
}
