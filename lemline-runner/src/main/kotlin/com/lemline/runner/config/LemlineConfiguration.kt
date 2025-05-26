// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import com.lemline.runner.config.LemlineConfigConstants.IN_MEMORY_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_KAFKA
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_RABBITMQ
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_CONNECTOR
import com.lemline.runner.messaging.WORKFLOW_IN
import com.lemline.runner.messaging.WORKFLOW_OUT
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.util.*

const val PRODUCER_ENABLED = "lemline.messaging.producer.enabled"
const val CONSUMER_ENABLED = "lemline.messaging.consumer.enabled"
const val LEMLINE_DATABASE_TYPE = "lemline.database.type"
const val LEMLINE_MESSAGING_TYPE = "lemline.messaging.type"

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
    fun config(): Optional<String>
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
        @WithDefault(DB_TYPE_IN_MEMORY)
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

                when (config.type()) {
                    DB_TYPE_IN_MEMORY -> {
                        props["quarkus.datasource.username"] = LemlineConfigConstants.DEFAULT_H2_USERNAME
                        props["quarkus.datasource.password"] = LemlineConfigConstants.DEFAULT_H2_PASSWORD
                        props["quarkus.datasource.jdbc.url"] =
                            "jdbc:h2:mem:${LemlineConfigConstants.DEFAULT_H2_DB_NAME};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
                    }

                    DB_TYPE_POSTGRESQL -> {
                        val pgConfig = config.postgresql()
                        props["quarkus.datasource.postgresql.username"] = pgConfig.username()
                        props["quarkus.datasource.postgresql.password"] = pgConfig.getPassword()
                        props["quarkus.datasource.postgresql.jdbc.url"] =
                            "jdbc:postgresql://${pgConfig.host()}:${pgConfig.port()}/${pgConfig.name()}"
                    }

                    DB_TYPE_MYSQL -> {
                        val mysqlConfig = config.mysql()
                        props["quarkus.datasource.mysql.username"] = mysqlConfig.username()
                        props["quarkus.datasource.mysql.password"] = mysqlConfig.getPassword()
                        props["quarkus.datasource.mysql.jdbc.url"] =
                            "jdbc:mysql://${mysqlConfig.host()}:${mysqlConfig.port()}/${mysqlConfig.name()}" +
                                "?useSSL=false" +
                                "&allowPublicKeyRetrieval=true" +
                                "&sessionVariables=sql_mode='STRICT_ALL_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ZERO_DATE,NO_ZERO_IN_DATE,NO_ENGINE_SUBSTITUTION'" +
                                "&continueBatchOnError=false"
                    }
                }

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

        // Producer settings
        fun producer(): ProducerConfig

        // Consumer settings
        fun consumer(): ConsumerConfig

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
            val logger = logger()

            fun toQuarkusProperties(
                config: MessagingConfig
            ): Map<String, String> {
                val props = mutableMapOf<String, String>()
                val incoming = "mp.messaging.incoming.$WORKFLOW_IN"
                val outgoing = "mp.messaging.outgoing.$WORKFLOW_OUT"
                props["$outgoing.merge"] = "true"

                // set the messaging type (only if the app is on the consumer profile)
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
                        if (config.consumer().enabled()) {
                            logger.info { "✅ Consumer Kafka enabled" }
                            props["$incoming.connector"] = KAFKA_CONNECTOR
                            props["$incoming.topic"] = kafkaConfig.topic()
                            props["$incoming.group.id"] = kafkaConfig.groupId()
                            props["$incoming.auto.offset.reset"] = kafkaConfig.offsetReset()
                            props["$incoming.failure-strategy"] = "dead-letter-queue"
                            props["$incoming.dead-letter-queue.topic"] =
                                kafkaConfig.topicDlq().orElse("${kafkaConfig.topic()}-dlq")
                            props["$incoming.value.deserializer"] =
                                "org.apache.kafka.common.serialization.StringDeserializer"
                        } else {
                            logger.info { "❌ Consumer Kafka disabled" }
                        }
                        // Outgoing channel
                        if (config.producer().enabled()) {
                            logger.info { "✅ Producer Kafka enabled" }
                            props["$outgoing.connector"] = KAFKA_CONNECTOR
                            props["$outgoing.topic"] = kafkaConfig.topicOut().orElse(kafkaConfig.topic())
                            props["$outgoing.value.serializer"] =
                                "org.apache.kafka.common.serialization.StringSerializer"
                        } else {
                            logger.info { "❌ Producer Kafka disabled" }
                        }

                        // Other settings
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
                        if (config.consumer().enabled()) {
                            logger.info { "✅ Consumer RabbitMQ enabled" }
                            props["$incoming.connector"] = RABBITMQ_CONNECTOR
                            props["$incoming.queue.name"] = rabbitConfig.queue()
                            props["$incoming.queue.durable"] = "true"
                            props["$incoming.auto-ack"] = "false"
                            props["$incoming.deserializer"] = "java.lang.String"
                            props["$incoming.queue.arguments.x-dead-letter-exchange"] = "dlx"
                            props["$incoming.queue.arguments.x-dead-letter-routing-key"] =
                                rabbitConfig.queueDlq().orElse("${rabbitConfig.queue()}-dlq")
                        } else {
                            logger.info { "❌ Consumer RabbitMQ disabled" }
                        }
                        // Outgoing channel
                        if (config.producer().enabled()) {
                            logger.info { "✅ Producer RabbitMQ enabled" }
                            props["$outgoing.connector"] = RABBITMQ_CONNECTOR
                            props["$outgoing.queue.name"] = rabbitConfig.queueOut().orElse(rabbitConfig.queue())
                            props["$outgoing.serializer"] = "java.lang.String"
                        } else {
                            logger.info { "❌ Producer RabbitMQ disabled" }
                        }

                        // Other settings
                        rabbitConfig.exchangeName().ifPresent { props["$outgoing.exchange.name"] = it }
                        rabbitConfig.sslEnabled().ifPresent { props["rabbitmq-ssl"] = it.toString() }
                    }
                }

                return props
            }
        }
    }

    interface ProducerConfig {
        @WithDefault("false")
        fun enabled(): Boolean
    }

    interface ConsumerConfig {
        @WithDefault("false")
        fun enabled(): Boolean
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
