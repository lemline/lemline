// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.config

import com.lemline.worker.config.LemlineConfigConstants.DEFAULT_MSG_TYPE
import io.quarkus.runtime.annotations.ConfigRoot
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.time.Duration
import java.util.*

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
@ConfigMapping(prefix = "lemline")
@ConfigRoot
interface LemlineConfiguration {
    fun database(): DatabaseConfig
    fun messaging(): MessagingConfig
    fun wait(): WaitConfig
    fun retry(): RetryConfig
}

/**
 * Database configuration mapping.
 * Supports multiple database types with type-safe configuration.
 *
 * Configuration Example:
 * ```yaml
 * lemline:
 *   database:
 *     type: postgresql
 *     migrateAtStart: true
 *     postgresql:
 *       host: localhost
 *       port: 5432
 * ```
 */
@ConfigMapping(prefix = "lemline.database")
interface DatabaseConfig {
    /**
     * Database type. Must be one of: postgresql, mysql, h2
     * Default: h2
     */
    @WithDefault(LemlineConfigConstants.DEFAULT_DB_TYPE)
    @Pattern(regexp = "postgresql|mysql|h2")
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
     * Whether to show SQL in logs
     * Default: false
     */
    @WithDefault("false")
    fun showSql(): Boolean

    /**
     * Whether to format SQL in logs
     * Default: false
     */
    @WithDefault("false")
    fun formatSql(): Boolean

    /**
     * Database generation strategy
     * Default: none
     */
    @WithDefault("none")
    fun generation(): String

    // DB-Specific settings
    fun postgresql(): PostgreSQLConfig
    fun mysql(): MySQLConfig
}

/**
 * PostgresSQL-specific configuration.
 * Required when database.type is "postgresql".
 */
@ConfigMapping(prefix = "lemline.database.postgresql")
interface PostgreSQLConfig {
    fun host(): String

    @Min(LemlineConfigConstants.MIN_PORT)
    @Max(LemlineConfigConstants.MAX_PORT)
    fun port(): Int

    fun username(): String

    @WithName("password")
    fun getPassword(): String

    fun name(): String
}

/**
 * MySQL-specific configuration.
 * Required when database.type is "mysql".
 */
@ConfigMapping(prefix = "lemline.database.mysql")
interface MySQLConfig {
    fun host(): String

    @Min(LemlineConfigConstants.MIN_PORT)
    @Max(LemlineConfigConstants.MAX_PORT)
    fun port(): Int

    fun username(): String

    @WithName("password")
    fun getPassword(): String

    fun name(): String
}

/**
 * Messaging configuration mapping.
 * Supports multiple messaging systems with type-safe configuration.
 *
 * Configuration Example:
 * ```yaml
 * lemline:
 *   messaging:
 *     type: kafka
 *     kafka:
 *       brokers: localhost:9092
 *       topic: workflows-in
 * ```
 */
@ConfigMapping(prefix = "lemline.messaging")
interface MessagingConfig {
    /**
     * Messaging type. Must be one of: in-memory, kafka, rabbitmq
     * Default: in-memory
     */
    @WithDefault(DEFAULT_MSG_TYPE)
    @Pattern(regexp = "in-memory|kafka|rabbitmq")
    fun type(): String

    // Broker Specific settings
    fun kafka(): KafkaConfig
    fun rabbitmq(): RabbitMQConfig
}

/**
 * Kafka-specific configuration.
 * Required when messaging.type is "kafka".
 */
@ConfigMapping(prefix = "lemline.messaging.kafka")
interface KafkaConfig {
    fun brokers(): String
    fun topic(): String
    fun topicOut(): String
    fun topicDlq(): String
    fun groupId(): String

    /**
     * Offset reset strategy. Must be one of: earliest, latest
     */
    @Pattern(regexp = "earliest|latest")
    fun offsetReset(): String

    // Optional security settings
    fun securityProtocol(): Optional<String>
    fun saslMechanism(): Optional<String>
    fun saslUsername(): Optional<String>

    @WithName("saslPassword")
    fun getSaslPassword(): Optional<String>
}

/**
 * RabbitMQ-specific configuration.
 * Required when messaging.type is "rabbitmq".
 */
@ConfigMapping(prefix = "lemline.messaging.rabbitmq")
interface RabbitMQConfig {
    fun hostname(): String

    @Min(LemlineConfigConstants.MIN_PORT)
    @Max(LemlineConfigConstants.MAX_PORT)
    fun port(): Int

    fun username(): String

    @WithName("password")
    fun getPassword(): String

    fun virtualHost(): String
    fun queue(): String
    fun queueOut(): String
    fun exchangeName(): Optional<String>
    fun sslEnabled(): Optional<Boolean>
}

/**
 * Wait service configuration.
 * Controls the behavior of the wait message processing.
 */
@ConfigMapping(prefix = "lemline.wait")
interface WaitConfig {
    fun outbox(): OutboxConfig
    fun cleanup(): CleanupConfig
}

/**
 * Retry service configuration.
 * Controls the behavior of the retry message processing.
 */
@ConfigMapping(prefix = "lemline.retry")
interface RetryConfig {
    fun outbox(): OutboxConfig
    fun cleanup(): CleanupConfig
}

/**
 * Outbox pattern configuration.
 * Controls the behavior of message processing in the outbox pattern.
 */
@ConfigMapping(prefix = "lemline.outbox")
interface OutboxConfig {
    /**
     * Processing interval
     * Default: 1 second
     */
    @WithDefault("PT1S")
    fun every(): Duration

    /**
     * Maximum number of messages to process in one batch
     * Default: 100
     */
    @WithDefault("100")
    @Min(LemlineConfigConstants.MIN_BATCH_SIZE)
    @Max(LemlineConfigConstants.MAX_BATCH_SIZE)
    fun batchSize(): Int

    /**
     * Initial delay before starting processing
     * Default: 1 second
     */
    @WithDefault("PT1S")
    fun initialDelay(): Duration

    /**
     * Maximum number of processing attempts
     * Default: 3
     */
    @WithDefault("3")
    @Min(1)
    fun maxAttempts(): Int
}

/**
 * Cleanup configuration.
 * Controls the behavior of message cleanup in the outbox pattern.
 */
@ConfigMapping(prefix = "lemline.cleanup")
interface CleanupConfig {
    /**
     * Cleanup interval
     * Default: 1 hour
     */
    @WithDefault("PT1H")
    fun every(): Duration

    /**
     * Age of messages to clean up
     * Default: 7 days
     */
    @WithDefault("P7D")
    fun after(): Duration

    /**
     * Maximum number of messages to clean up in one batch
     * Default: 1000
     */
    @WithDefault("1000")
    @Min(LemlineConfigConstants.MIN_BATCH_SIZE)
    @Max(LemlineConfigConstants.MAX_BATCH_SIZE)
    fun batchSize(): Int
}
