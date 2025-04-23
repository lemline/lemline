// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.config

import io.quarkus.runtime.annotations.ConfigRoot
import io.smallrye.config.ConfigMapping
import java.time.Duration
import java.util.*

// Define interfaces mirroring the structure in application.yaml under 'lemline:'

@ConfigMapping(prefix = "lemline")
@ConfigRoot
interface LemlineConfiguration {
    fun database(): DatabaseConfig
    fun messaging(): MessagingConfig
    fun wait(): WaitConfig
    fun retry(): RetryConfig
}

@ConfigMapping(prefix = "lemline.database")
interface DatabaseConfig {
    fun type(): String // "postgresql" or "mysql"

    // Common ORM and Migration settings
    fun migrateAtStart(): Optional<Boolean> // Use Optional for properties with framework defaults we might not want to force
    fun baselineOnMigrate(): Optional<Boolean>
    fun showSql(): Optional<Boolean>
    fun formatSql(): Optional<Boolean>
    fun generation(): Optional<String>

    // DB Specific settings
    fun postgresql(): PostgreSQLConfig
    fun mysql(): MySQLConfig
}

@ConfigMapping(prefix = "lemline.database.postgresql")
interface PostgreSQLConfig {
    fun host(): String
    fun port(): Int
    fun username(): String
    fun password(): String
    fun name(): String
}

@ConfigMapping(prefix = "lemline.database.mysql")
interface MySQLConfig {
    fun host(): String
    fun port(): Int
    fun username(): String
    fun password(): String
    fun name(): String
}

@ConfigMapping(prefix = "lemline.messaging")
interface MessagingConfig {
    fun type(): String // "kafka" or "rabbitmq"

    // Broker Specific settings
    fun kafka(): KafkaConfig
    fun rabbitmq(): RabbitMQConfig
}

@ConfigMapping(prefix = "lemline.messaging.kafka")
interface KafkaConfig {
    fun brokers(): String
    fun topicIn(): String
    fun topicOut(): String
    fun topicDlq(): String
    fun groupId(): String
    fun offsetReset(): String // "earliest" or "latest"

    // Optional security settings
    fun securityProtocol(): Optional<String>
    fun saslMechanism(): Optional<String>
    fun saslUsername(): Optional<String>
    fun saslPassword(): Optional<String>
}

@ConfigMapping(prefix = "lemline.messaging.rabbitmq")
interface RabbitMQConfig {
    fun hostname(): String
    fun port(): Int
    fun username(): String
    fun password(): String
    fun virtualHost(): String
    fun queueIn(): String
    fun queueOut(): String
    fun exchangeName(): Optional<String>
    fun sslEnabled(): Optional<Boolean>
}


// Configuration for specific Lemline services (Wait, Retry)
@ConfigMapping(prefix = "lemline.wait")
interface WaitConfig {
    fun outbox(): OutboxConfig
    fun cleanup(): CleanupConfig
}

@ConfigMapping(prefix = "lemline.retry")
interface RetryConfig {
    fun outbox(): OutboxConfig
    fun cleanup(): CleanupConfig
}

@ConfigMapping(prefix = "lemline.outbox")
interface OutboxConfig {
    fun every(): Duration
    fun batchSize(): Int
    fun initialDelay(): Duration
    fun maxAttempts(): Int
}

@ConfigMapping(prefix = "lemline.cleanup")
interface CleanupConfig {
    fun every(): Duration
    fun after(): Duration
    fun batchSize(): Int
}
