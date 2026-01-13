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
const val CLOUDEVENTS_CONSUMER_ENABLED = "lemline.messaging.cloudevents.consumer.enabled"
const val CLOUDEVENTS_CONSUMER_CONCURRENCY = "lemline.messaging.cloudevents.consumer.concurrency"

const val LIFECYCLE_EVENTS_PRODUCER_ENABLED = "lemline.messaging.lifecycleevents.producer.enabled"

const val DATABASE_ENABLED = "lemline.database.enabled"

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
    fun definitions(): Optional<DefinitionsConfig>

    /**
     * Database configuration mapping.
     */
    interface DatabaseConfig {

        /**
         * Whether database is enabled.
         * Set to false for commands that don't need database (e.g., --help, --version).
         */
        @WithDefault("true")
        fun enabled(): Boolean

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
        @Pattern(regexp = "in-memory|kafka|rabbitmq|pgmq")
        fun type(): String

        fun commands(): Optional<ChannelConfig>

        fun events(): Optional<ChannelConfig>

        fun cloudevents(): Optional<CloudEventsChannelConfig>

        fun lifecycleevents(): Optional<LifecycleEventsChannelConfig>

        /**
         * Optional Kafka configuration
         */
        fun kafka(): Optional<KafkaConfig>

        /**
         * Optional RabbitMQ configuration
         */
        fun rabbitmq(): Optional<RabbitMQConfig>

        /**
         * Optional PGMQ (PostgreSQL Message Queue) configuration
         */
        fun pgmq(): Optional<PgmqConfig>
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
     * CloudEvents channel configuration.
     * Used for emitting CloudEvents to external systems and consuming CloudEvents for listen tasks.
     */
    interface CloudEventsChannelConfig {
        fun producer(): ProducerConfig
        fun consumer(): ConsumerConfig
    }

    /**
     * Lifecycle events channel configuration.
     * Used for emitting workflow and task lifecycle events as CloudEvents to external systems.
     * This is a producer-only channel - lifecycle events are for external consumption.
     */
    interface LifecycleEventsChannelConfig {
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

        fun commands(): KafkaCommandsConfig
        fun events(): KafkaEventsConfig
        fun lifecycleevents(): Optional<KafkaLifecycleEventsConfig>
    }

    /**
     * Kafka lifecycle events configuration.
     * Producer-only configuration for emitting lifecycle events.
     */
    interface KafkaLifecycleEventsConfig {
        @WithDefault(LemlineConfigConstants.LIFECYCLE_EVENTS_TOPIC_DEFAULT)
        fun topic(): String
        fun producer(): KafkaProducerConfig
    }

    interface KafkaCommandsConfig {
        @WithDefault(COMMANDS_TOPIC_DEFAULT)
        fun topic(): String
        fun consumer(): KafkaConsumerWorkflowsConfig
        fun producer(): KafkaProducerConfig
    }

    interface KafkaEventsConfig {
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

        fun commands(): RabbitCommandsConfig
        fun events(): RabbitEventsConfig
        fun lifecycleevents(): Optional<RabbitLifecycleEventsConfig>
    }

    /**
     * RabbitMQ lifecycle events configuration.
     * Producer-only configuration for emitting lifecycle events.
     */
    interface RabbitLifecycleEventsConfig {
        @WithDefault(LemlineConfigConstants.LIFECYCLE_EVENTS_TOPIC_DEFAULT)
        fun queue(): String
        fun producer(): RabbitProducerConfig
    }

    interface RabbitCommandsConfig {
        @WithDefault(RABBITMQ_VHOST_DEFAULT)
        fun virtualHost(): Optional<String>

        @WithDefault(COMMANDS_TOPIC_DEFAULT)
        fun queue(): String
        fun consumer(): RabbitConsumerConfig
        fun producer(): RabbitProducerConfig
    }

    interface RabbitEventsConfig {
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

    /**
     * PGMQ (PostgreSQL Message Queue) configuration.
     * Uses PostgreSQL as a message broker via the PGMQ extension.
     */
    interface PgmqConfig {
        @WithDefault(POSTGRES_HOST_DEFAULT)
        fun host(): String

        @WithDefault(POSTGRES_PORT_DEFAULT)
        fun port(): Int

        @WithDefault(POSTGRES_NAME_DEFAULT)
        fun database(): String

        @WithDefault(POSTGRES_USERNAME_DEFAULT)
        fun username(): String

        @WithDefault(POSTGRES_PASSWORD_DEFAULT)
        fun password(): Optional<String>

        fun commands(): PgmqChannelConfig
        fun events(): PgmqChannelConfig
        fun cloudevents(): Optional<PgmqChannelConfig>
        fun lifecycleevents(): Optional<PgmqLifecycleEventsConfig>
    }

    /**
     * PGMQ channel configuration.
     */
    interface PgmqChannelConfig {
        @WithDefault(COMMANDS_TOPIC_DEFAULT)
        fun queue(): String

        fun consumer(): PgmqConsumerConfig
        fun producer(): PgmqProducerConfig
    }

    /**
     * PGMQ lifecycle events configuration.
     * Producer-only configuration for emitting lifecycle events.
     */
    interface PgmqLifecycleEventsConfig {
        @WithDefault(LemlineConfigConstants.LIFECYCLE_EVENTS_TOPIC_DEFAULT)
        fun queue(): String
        fun producer(): PgmqProducerConfig
    }

    /**
     * PGMQ consumer configuration.
     */
    interface PgmqConsumerConfig {
        @WithDefault(CONSUMER_CONCURRENCY_DEFAULT)
        fun concurrency(): Int

        @WithDefault(LemlineConfigConstants.PGMQ_VISIBILITY_TIMEOUT_DEFAULT)
        fun visibilityTimeout(): Int

        @WithDefault(LemlineConfigConstants.PGMQ_POLL_INTERVAL_DEFAULT)
        fun pollInterval(): Long

        @WithDefault(LemlineConfigConstants.PGMQ_BATCH_SIZE_DEFAULT)
        fun batchSize(): Int

        @WithDefault(LemlineConfigConstants.PGMQ_MAX_RETRIES_DEFAULT)
        fun maxRetries(): Int

        fun queueDlq(): Optional<String>
    }

    /**
     * PGMQ producer configuration.
     */
    interface PgmqProducerConfig {
        fun queueOut(): Optional<String>
    }

    interface OutboxConfig {
        fun enabled(): Optional<Boolean>
        fun wait(): Optional<ProcessOutboxConfig>
        fun retry(): Optional<ProcessOutboxConfig>
        fun schedule(): Optional<ProcessOutboxConfig>
        fun listener(): Optional<ProcessOutboxConfig>
        fun parent(): Optional<CleanupOutboxConfig>
        fun fork(): Optional<CleanupOutboxConfig>
    }

    interface ProcessOutboxConfig {
        @WithDefault("true")
        fun enabled(): Boolean

        fun outbox(): OutboxProcessingConfig
        fun cleanup(): OutboxCleanupConfig
    }

    interface CleanupOutboxConfig {
        @WithDefault("true")
        fun enabled(): Boolean

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
         * Maximum random delay before first scheduled poll (0 to this value).
         * Desynchronizes workers to prevent thundering herd on database.
         */
        @WithDefault("3s")
        fun initialJitter(): String

        /**
         * Base delay for exponential backoff on failed messages.
         * Each retry doubles: retry-delay * 2^(attempt-1) with ±20% jitter.
         */
        @WithDefault("30s")
        fun retryDelay(): String

        /**
         * Maximum number of processing attempts
         * Default: 5
         */
        @WithDefault("5")
        @Min(1)
        fun maxAttempts(): Int

        val every get() = every().toDuration()
        val batchSize get() = batchSize()
        val initialJitter get() = initialJitter().toDuration()
        val retryDelay get() = retryDelay().toDuration()
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
         * Maximum random delay before first scheduled cleanup (0 to this value).
         * Desynchronizes workers to prevent thundering herd on database.
         */
        @WithDefault("3s")
        fun initialJitter(): String

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
        val initialJitter get() = initialJitter().toDuration()
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

    /**
     * Definitions management configuration.
     */
    interface DefinitionsConfig {
        fun cache(): Optional<DefinitionsCacheConfig>
    }

    /**
     * Definition cache configuration.
     * Controls behavior of the definition cache sync process.
     */
    interface DefinitionsCacheConfig {
        /**
         * Interval for syncing definitions from database to cache.
         * Default: 10s
         */
        @WithDefault("10s")
        fun syncEvery(): String

        val syncEvery get() = syncEvery().toDuration()
    }
}
