// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.config

import com.lemline.worker.messaging.WORKFLOW_IN
import com.lemline.worker.messaging.WORKFLOW_OUT
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

    companion object {
        // Configuration source
        const val CONFIG_ORDINAL = 275
        const val CONFIG_SOURCE_NAME = "LemlineConfigSource"

        // Database types
        const val DB_TYPE_IN_MEMORY = "in-memory"
        const val DB_TYPE_POSTGRESQL = "postgresql"
        const val DB_TYPE_MYSQL = "mysql"
        val SUPPORTED_DB_TYPES = setOf(DB_TYPE_IN_MEMORY, DB_TYPE_POSTGRESQL, DB_TYPE_MYSQL)

        // H2 Default values
        const val DEFAULT_H2_DB_NAME = "lemline"
        const val DEFAULT_H2_USERNAME = "sa"
        const val DEFAULT_H2_PASSWORD = ""

        // Messaging types
        const val MSG_TYPE_IN_MEMORY = "in-memory"
        const val MSG_TYPE_KAFKA = "kafka"
        const val MSG_TYPE_RABBITMQ = "rabbitmq"
        val SUPPORTED_MSG_TYPES = setOf(MSG_TYPE_IN_MEMORY, MSG_TYPE_KAFKA, MSG_TYPE_RABBITMQ)

        // Messaging connectors
        const val IN_MEMORY_CONNECTOR = "smallrye-in-memory"
        const val KAFKA_CONNECTOR = "smallrye-kafka"
        const val RABBITMQ_CONNECTOR = "smallrye-rabbitmq"

        // Validation
        const val MIN_PORT = 1L
        const val MAX_PORT = 65535L
        const val MIN_BATCH_SIZE = 1L
        const val MAX_BATCH_SIZE = 10000L
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


        // DB-Specific settings
        fun postgresql(): PostgreSQLConfig
        fun mysql(): MySQLConfig

        companion object {
            fun toQuarkusProperties(config: DatabaseConfig): Map<String, String> {
                val props = mutableMapOf<String, String>()

                // Set the database type
                props["quarkus.datasource.db-kind"] = when (config.type()) {
                    DB_TYPE_IN_MEMORY -> "h2"
                    DB_TYPE_POSTGRESQL -> "postgresql"
                    DB_TYPE_MYSQL -> "mysql"
                    else -> throw IllegalArgumentException("Unsupported database type: ${config.type()}")
                }

                // Configure specific database type
                when (config.type()) {
                    DB_TYPE_IN_MEMORY -> {
                        props["quarkus.flyway.locations"] = "classpath:db/migration/h2"
                        props["quarkus.datasource.jdbc.url"] = "jdbc:h2:mem:${DEFAULT_H2_DB_NAME};DB_CLOSE_DELAY=-1"
                        props["quarkus.datasource.username"] = DEFAULT_H2_USERNAME
                        props["quarkus.datasource.password"] = DEFAULT_H2_PASSWORD
                    }

                    DB_TYPE_POSTGRESQL -> {
                        val pgConfig = config.postgresql()
                        props["quarkus.flyway.locations"] = "classpath:db/migration/postgresql"
                        props["quarkus.datasource.jdbc.url"] =
                            "jdbc:postgresql://${pgConfig.host()}:${pgConfig.port()}/${pgConfig.name()}"
                        props["quarkus.datasource.username"] = pgConfig.username()
                        props["quarkus.datasource.password"] = pgConfig.getPassword()
                    }

                    DB_TYPE_MYSQL -> {
                        val mysqlConfig = config.mysql()
                        props["quarkus.flyway.locations"] = "classpath:db/migration/mysql"
                        props["quarkus.datasource.jdbc.url"] =
                            "jdbc:mysql://${mysqlConfig.host()}:${mysqlConfig.port()}/${mysqlConfig.name()}?useSSL=false&allowPublicKeyRetrieval=true"
                        props["quarkus.datasource.username"] = mysqlConfig.username()
                        props["quarkus.datasource.password"] = mysqlConfig.getPassword()
                    }
                }

                props["quarkus.flyway.baseline-on-migrate"] = config.baselineOnMigrate().toString()
                // Note: migrate-at-start is managed by FlywayMigration

                return props
            }
        }
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
        @Pattern(regexp = "in-memory|kafka|rabbitmq")
        fun type(): String

        // Broker Specific settings
        fun kafka(): KafkaConfig
        fun rabbitmq(): RabbitMQConfig

        companion object {
            fun toQuarkusProperties(
                config: MessagingConfig
            ): Map<String, String> {
                val props = mutableMapOf<String, String>()
                val incoming = "mp.messaging.incoming.$WORKFLOW_IN"
                val outgoing = "mp.messaging.outgoing.$WORKFLOW_OUT"
                props["$outgoing.merge"] = "true"

                when (config.type()) {
                    MSG_TYPE_IN_MEMORY -> {
                        props["$incoming.connector"] = IN_MEMORY_CONNECTOR
                        props["$outgoing.connector"] = IN_MEMORY_CONNECTOR
                    }

                    MSG_TYPE_KAFKA -> {
                        val kafkaConfig = config.kafka()
                        // Server configuration
                        props["kafka.bootstrap.servers"] = kafkaConfig.brokers()

                        // Incoming channel
                        props["$incoming.connector"] = KAFKA_CONNECTOR
                        props["$incoming.topic"] = kafkaConfig.topic()
                        props["$incoming.group.id"] = kafkaConfig.groupId()
                        props["$incoming.auto.offset.reset"] = kafkaConfig.offsetReset()
                        props["$incoming.failure-strategy"] = "dead-letter-queue"
                        props["$incoming.dead-letter-queue.topic"] =
                            kafkaConfig.topicDlq().orElse("${kafkaConfig.topic()}-dlq")

                        // Outgoing channel
                        props["$outgoing.connector"] = KAFKA_CONNECTOR
                        props["$outgoing.topic"] = kafkaConfig.topicOut().orElse(kafkaConfig.topic())

                        // Security settings
                        kafkaConfig.securityProtocol().ifPresent { props["kafka.security.protocol"] = it }
                        kafkaConfig.saslMechanism().ifPresent { props["kafka.sasl.mechanism"] = it }

                        if (kafkaConfig.saslUsername().isPresent && kafkaConfig.getSaslPassword().isPresent) {
                            props["kafka.sasl.jaas.config"] =
                                "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                                    "username=\"${kafkaConfig.saslUsername().get()}\" " +
                                    "password=\"${kafkaConfig.getSaslPassword().get()}\";"

                            if (!props.containsKey("kafka.sasl.mechanism")) {
                                props["kafka.sasl.mechanism"] = "PLAIN"
                            }
                        }
                    }

                    MSG_TYPE_RABBITMQ -> {
                        val rabbitConfig = config.rabbitmq()
                        // Server configuration
                        props["rabbitmq-host"] = rabbitConfig.hostname()
                        props["rabbitmq-port"] = rabbitConfig.port().toString()
                        props["rabbitmq-username"] = rabbitConfig.username()
                        props["rabbitmq-password"] = rabbitConfig.getPassword()
                        rabbitConfig.virtualHost().let { props["rabbitmq-virtual-host"] = it }

                        // Incoming channel
                        props["$incoming.connector"] = RABBITMQ_CONNECTOR
                        props["$incoming.queue.name"] = rabbitConfig.queue()
                        props["$incoming.queue.durable"] = "true"
                        props["$incoming.auto-ack"] = "false"
                        props["$incoming.deserializer"] = "java.lang.String"
                        props["$incoming.queue.arguments.x-dead-letter-exchange"] = "dlx"
                        props["$incoming.queue.arguments.x-dead-letter-routing-key"] =
                            rabbitConfig.queueDlq().orElse("${rabbitConfig.queue()}-dlq")

                        // Outgoing channel
                        props["$outgoing.connector"] = RABBITMQ_CONNECTOR
                        props["$outgoing.queue.name"] = rabbitConfig.queueOut().orElse(rabbitConfig.queue())
                        props["$outgoing.serializer"] = "java.lang.String"

                        // Optional settings
                        rabbitConfig.exchangeName().ifPresent { props["$outgoing.exchange.name"] = it }
                        rabbitConfig.sslEnabled().ifPresent { props["rabbitmq-ssl"] = it.toString() }
                    }
                }

                return props
            }
        }
    }

    /**
     * Kafka-specific configuration.
     * Required when messaging.type is "kafka".
     */
    @ConfigMapping(prefix = "lemline.messaging.kafka")
    interface KafkaConfig {
        fun brokers(): String
        fun topic(): String
        fun groupId(): String
        fun topicDlq(): Optional<String>
        fun topicOut(): Optional<String>

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
        fun queueDlq(): Optional<String>
        fun queueOut(): Optional<String>
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
}
