// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.messaging.WORKFLOW_IN
import com.lemline.runner.messaging.WORKFLOW_OUT
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
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
interface LemlineConfiguration {
    fun database(): DatabaseConfig
    fun messaging(): MessagingConfig
    fun wait(): WaitConfig
    fun retry(): RetryConfig

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
    interface DatabaseConfig {
        /**
         * Database type. Must be one of: in-memory, postgresql, mysql
         */
        @Pattern(regexp = "in-memory|postgresql|mysql")
        @WithDefault("in-memory")
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
                    LemlineConfigConstants.DB_TYPE_IN_MEMORY -> "h2"
                    LemlineConfigConstants.DB_TYPE_POSTGRESQL -> "postgresql"
                    LemlineConfigConstants.DB_TYPE_MYSQL -> "mysql"
                    else -> throw IllegalArgumentException("Unsupported database type: ${config.type()}")
                }

                // Configure specific database type
                when (config.type()) {
                    LemlineConfigConstants.DB_TYPE_IN_MEMORY -> {
                        props["quarkus.flyway.locations"] = "classpath:db/migration/h2"
                        props["quarkus.datasource.jdbc.url"] =
                            "jdbc:h2:mem:${LemlineConfigConstants.DEFAULT_H2_DB_NAME};DB_CLOSE_DELAY=-1"
                        props["quarkus.datasource.username"] = LemlineConfigConstants.DEFAULT_H2_USERNAME
                        props["quarkus.datasource.password"] = LemlineConfigConstants.DEFAULT_H2_PASSWORD
                    }

                    LemlineConfigConstants.DB_TYPE_POSTGRESQL -> {
                        val pgConfig = config.postgresql()
                        props["quarkus.flyway.locations"] = "classpath:db/migration/postgresql"
                        props["quarkus.datasource.jdbc.url"] =
                            "jdbc:postgresql://${pgConfig.host()}:${pgConfig.port()}/${pgConfig.name()}"
                        props["quarkus.datasource.username"] = pgConfig.username()
                        props["quarkus.datasource.password"] = pgConfig.getPassword()
                    }

                    LemlineConfigConstants.DB_TYPE_MYSQL -> {
                        val mysqlConfig = config.mysql()
                        props["quarkus.flyway.locations"] = "classpath:db/migration/mysql"
                        props["quarkus.datasource.jdbc.url"] =
                            "jdbc:mysql://${mysqlConfig.host()}:${mysqlConfig.port()}/${mysqlConfig.name()}?useSSL=false&allowPublicKeyRetrieval=true"
                        props["quarkus.datasource.username"] = mysqlConfig.username()
                        props["quarkus.datasource.password"] = mysqlConfig.getPassword()
                    }
                }
                props["quarkus.flyway.baseline-on-migrate"] = config.baselineOnMigrate().toString()
                props["quarkus.flyway.migrate-at-start"] = config.migrateAtStart().toString()

                return props
            }
        }
    }

    /**
     * PostgresSQL-specific configuration.
     * Required when database.type is "postgresql".
     */
    interface PostgreSQLConfig {
        @WithDefault("localhost")
        fun host(): String

        @WithDefault("5432")
        @Min(1)
        fun port(): Int

        @WithDefault("postgres")
        fun username(): String

        @WithDefault("postgres")
        @WithName("password")
        fun getPassword(): String

        @WithDefault("lemline")
        fun name(): String
    }

    /**
     * MySQL-specific configuration.
     * Required when database.type is "mysql".
     */
    interface MySQLConfig {
        @WithDefault("localhost")
        fun host(): String

        @WithDefault("3306")
        @Min(1)
        fun port(): Int

        @WithDefault("mysql")
        fun username(): String

        @WithDefault("mysql")
        @WithName("password")
        fun getPassword(): String

        @WithDefault("lemline")
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
    interface MessagingConfig {
        /**
         * Messaging type. Must be one of: in-memory, kafka, rabbitmq
         * Default: in-memory
         */
        @WithDefault("in-memory")
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
                    LemlineConfigConstants.MSG_TYPE_IN_MEMORY -> {
                        props["$incoming.connector"] = LemlineConfigConstants.IN_MEMORY_CONNECTOR
                        props["$outgoing.connector"] = LemlineConfigConstants.IN_MEMORY_CONNECTOR
                    }

                    LemlineConfigConstants.MSG_TYPE_KAFKA -> {
                        val kafkaConfig = config.kafka()
                        // Server configuration
                        props["kafka.bootstrap.servers"] = kafkaConfig.brokers()

                        // Incoming channel
                        props["$incoming.connector"] = LemlineConfigConstants.KAFKA_CONNECTOR
                        props["$incoming.topic"] = kafkaConfig.topic()
                        props["$incoming.group.id"] = kafkaConfig.groupId()
                        props["$incoming.auto.offset.reset"] = kafkaConfig.offsetReset()
                        props["$incoming.failure-strategy"] = "dead-letter-queue"
                        props["$incoming.dead-letter-queue.topic"] =
                            kafkaConfig.topicDlq().orElse("${kafkaConfig.topic()}-dlq")

                        // Outgoing channel
                        props["$outgoing.connector"] = LemlineConfigConstants.KAFKA_CONNECTOR
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

                    LemlineConfigConstants.MSG_TYPE_RABBITMQ -> {
                        val rabbitConfig = config.rabbitmq()
                        // Server configuration
                        props["rabbitmq-host"] = rabbitConfig.hostname()
                        props["rabbitmq-port"] = rabbitConfig.port().toString()
                        props["rabbitmq-username"] = rabbitConfig.username()
                        props["rabbitmq-password"] = rabbitConfig.getPassword()
                        rabbitConfig.virtualHost().let { props["rabbitmq-virtual-host"] = it }

                        // Incoming channel
                        props["$incoming.connector"] = LemlineConfigConstants.RABBITMQ_CONNECTOR
                        props["$incoming.queue.name"] = rabbitConfig.queue()
                        props["$incoming.queue.durable"] = "true"
                        props["$incoming.auto-ack"] = "false"
                        props["$incoming.deserializer"] = "java.lang.String"
                        props["$incoming.queue.arguments.x-dead-letter-exchange"] = "dlx"
                        props["$incoming.queue.arguments.x-dead-letter-routing-key"] =
                            rabbitConfig.queueDlq().orElse("${rabbitConfig.queue()}-dlq")

                        // Outgoing channel
                        props["$outgoing.connector"] = LemlineConfigConstants.RABBITMQ_CONNECTOR
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
    interface KafkaConfig {
        @WithDefault("localhost:9092")
        fun brokers(): String

        @WithDefault("lemline")
        fun topic(): String

        @WithDefault("group-1")
        fun groupId(): String

        @WithDefault("earliest")
        @Pattern(regexp = "earliest|latest")
        fun offsetReset(): String

        // Optional settings
        fun topicDlq(): Optional<String>
        fun topicOut(): Optional<String>
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
    interface RabbitMQConfig {
        @WithDefault("localhost")
        fun hostname(): String

        @WithDefault("5672")
        fun port(): Int

        @WithDefault("guest")
        fun username(): String

        @WithDefault("guest")
        @WithName("password")
        fun getPassword(): String

        @WithDefault("/")
        fun virtualHost(): String

        @WithDefault("lemline")
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
    interface WaitConfig {
        fun outbox(): OutboxConfig
        fun cleanup(): CleanupConfig
    }

    /**
     * Retry service configuration.
     * Controls the behavior of the retry message processing.
     */
    interface RetryConfig {
        fun outbox(): OutboxConfig
        fun cleanup(): CleanupConfig
    }

    /**
     * Outbox pattern configuration.
     * Controls the behavior of message processing in the outbox pattern.
     */
    interface OutboxConfig {
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
    }

    /**
     * Cleanup configuration.
     * Controls the behavior of message cleanup in the outbox pattern.
     */
    interface CleanupConfig {
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
    }
}
