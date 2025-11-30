// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.config.LemlineConfigConstants.COMMANDS_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.CONSUMER_CONCURRENCY_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.DB_BASELINE_ON_MIGRATE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.DB_MIGRATE_AT_START_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.EVENTS_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_BROKERS_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_DATABASE_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_OFFSET_RESET_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_WORKFLOWS_GROUP_ID_DEFAULT
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
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.util.*

const val DATABASE_TYPE = "lemline.database.type"
const val MIGRATE_AT_START = "lemline.database.migrate-at-start"
const val MESSAGING_TYPE = "lemline.messaging.type"

const val COMMANDS_PRODUCER_ENABLED = "lemline.messaging.commands.producer.enabled"
const val COMMANDS_CONSUMER_ENABLED = "lemline.messaging.commands.consumer.enabled"
const val COMMANDS_CONSUMER_CONCURRENCY = "lemline.messaging.commands.consumer.concurrency"

const val EVENTS_PRODUCER_ENABLED = "lemline.messaging.events.producer.enabled"
const val EVENTS_CONSUMER_ENABLED = "lemline.messaging.events.consumer.enabled"
const val EVENTS_CONSUMER_CONCURRENCY = "lemline.messaging.events.consumer.concurrency"

const val CLOUDEVENTS_PRODUCER_ENABLED = "lemline.messaging.cloudevents.producer.enabled"

const val ORCHESTRATOR_MODE = "lemline.orchestrator.mode"

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
    fun orchestrator(): OrchestratorConfig
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
         * Messaging type.
         * If not provided, it will be set in [LemlineConfigSource]
         */
        @Pattern(regexp = "in-memory|kafka|rabbitmq")
        fun type(): String

        fun commands(): Optional<ChannelConfig>

        fun events(): Optional<ChannelConfig>

        fun cloudevents(): Optional<CloudEventsChannelConfig>

        /**
         * Optional Kafka configuration
         */
        fun kafka(): Optional<KafkaConfig>

        /**
         * Optional RabbitMQ configuration
         */
        fun rabbitmq(): Optional<RabbitMQConfig>
    }

    interface ChannelConfig {
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
     * CloudEvents channel configuration (producer-only).
     * Used for emitting CloudEvents to external consumers.
     */
    interface CloudEventsChannelConfig {
        fun producer(): ProducerConfig
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
        fun database(): KafkaIngestionConfig
    }

    interface KafkaWorkflowsConfig {
        @WithDefault(COMMANDS_TOPIC_DEFAULT)
        fun topic(): String
        fun consumer(): KafkaConsumerWorkflowsConfig
        fun producer(): KafkaProducerConfig
    }

    interface KafkaIngestionConfig {
        @WithDefault(EVENTS_TOPIC_DEFAULT)
        fun topic(): String
        fun consumer(): KafkaConsumerDatabaseConfig
        fun producer(): KafkaProducerConfig
    }

    interface KafkaConsumerWorkflowsConfig {
        @WithDefault(CONSUMER_CONCURRENCY_DEFAULT)
        fun concurrency(): Int

        @WithDefault(KAFKA_WORKFLOWS_GROUP_ID_DEFAULT)
        fun groupId(): String

        @Pattern(regexp = "latest|earliest")
        @WithDefault(KAFKA_OFFSET_RESET_DEFAULT)
        fun offsetReset(): String

        fun topicDlq(): Optional<String>

        fun topicOut(): Optional<String>
    }

    interface KafkaConsumerDatabaseConfig {
        @WithDefault(CONSUMER_CONCURRENCY_DEFAULT)
        fun concurrency(): Int

        @WithDefault(KAFKA_DATABASE_GROUP_ID_DEFAULT)
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
        fun database(): RabbitIngestionConfig
    }

    interface RabbitWorkflowsConfig {
        @WithDefault(RABBITMQ_VHOST_DEFAULT)
        fun virtualHost(): Optional<String>

        @WithDefault(COMMANDS_TOPIC_DEFAULT)
        fun queue(): String
        fun consumer(): RabbitConsumerConfig
        fun producer(): RabbitProducerConfig
    }

    interface RabbitIngestionConfig {
        @WithDefault(RABBITMQ_VHOST_DEFAULT)
        fun virtualHost(): Optional<String>

        @WithDefault(EVENTS_TOPIC_DEFAULT)
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
        fun wait(): ProcessOutboxConfig
        fun retry(): ProcessOutboxConfig
        fun schedule(): ProcessOutboxConfig
        fun parent(): CleanupOutboxConfig
        fun fork(): CleanupOutboxConfig
    }

    /**
     * Process and cleanup configuration.
     */
    interface ProcessOutboxConfig {
        fun enabled(): Optional<Boolean>
        fun outbox(): OutboxProcessingConfig
        fun cleanup(): OutboxCleanupConfig
    }

    /**
     * Only cleanup configuration.
     */
    interface CleanupOutboxConfig {
        fun enabled(): Optional<Boolean>
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

    /**
     * Orchestrator configuration.
     * Controls workflow execution behavior.
     */
    interface OrchestratorConfig {
        /**
         * Step execution mode:
         * - ACTION: Batches control flow nodes, emits message only for action tasks (default)
         * - ALL: Emits a message for every task including control flow nodes
         *
         * Use ALL mode for end-to-end testing to generate more broker messages.
         */
        @WithDefault("action")
        fun mode(): OrchestratorMode
    }

    /**
     * Orchestrator execution mode.
     */
    enum class OrchestratorMode {
        /** Batches control flow nodes, emits message only for action tasks (call, run, emit, etc.) */
        ACTION,
        /** Emits a message for every task including control flow nodes */
        ALL
    }
}
