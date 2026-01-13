// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.runner.common.config.MessagingType
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.sql.DriverManager
import java.util.*
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Validates broker connectivity during application startup.
 * Provides clear, actionable error messages when the broker is unreachable.
 */
@ApplicationScoped
class BrokerStartupValidator {
    private val logger = logger()

    @ConfigProperty(name = MESSAGING_TYPE)
    private lateinit var _messagingTypeConfig: String

    private val messagingType: MessagingType by lazy {
        MessagingType.fromConfigValue(_messagingTypeConfig)
    }

    @ConfigProperty(name = DATABASE_ENABLED, defaultValue = "true")
    var databaseEnabled: Boolean = true

    @Inject
    lateinit var config: LemlineConfiguration

    @Suppress("unused")
    fun onStart(@Observes @Priority(2) event: StartupEvent) {
        // Skip validation if database is disabled (e.g., for --help or --version)
        if (!databaseEnabled) return

        // Skip validation for in-memory messaging
        if (messagingType == MessagingType.IN_MEMORY) {
            logger.info { "Using in-memory messaging - skipping broker connectivity check." }
            return
        }

        verifyBrokerConnectivity()
    }

    private fun verifyBrokerConnectivity() {
        when (messagingType) {
            MessagingType.KAFKA -> verifyKafkaConnectivity()
            MessagingType.RABBITMQ -> verifyRabbitMQConnectivity()
            MessagingType.PGMQ -> verifyPgmqConnectivity()
            MessagingType.IN_MEMORY -> {} // Already handled above
        }
    }

    private fun verifyKafkaConnectivity() {
        val kafkaConfig = config.messaging().kafka().orElse(null)
            ?: throw BrokerStartupException(
                """
                |
                |═══════════════════════════════════════════════════════════════════════════════
                |  KAFKA CONFIGURATION MISSING
                |═══════════════════════════════════════════════════════════════════════════════
                |
                |  Messaging type is set to 'kafka' but no Kafka configuration found.
                |
                |  Add Kafka configuration to your .lemline.yaml:
                |
                |    lemline:
                |      messaging:
                |        type: kafka
                |        kafka:
                |          brokers: localhost:9092
                |
                |═══════════════════════════════════════════════════════════════════════════════
                """.trimMargin()
            )

        val brokers = kafkaConfig.brokers()
        val props = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
            put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000)
            put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000)

            // Add security settings if configured
            kafkaConfig.securityProtocol().ifPresent { put("security.protocol", it) }
            kafkaConfig.saslMechanism().ifPresent { put("sasl.mechanism", it) }
            kafkaConfig.saslUsername().ifPresent { username ->
                kafkaConfig.saslPassword().ifPresent { password ->
                    put(
                        "sasl.jaas.config",
                        "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                            "username=\"$username\" password=\"$password\";"
                    )
                }
            }
        }

        try {
            AdminClient.create(props).use { client ->
                // Try to list topics to verify connectivity
                client.listTopics().names().get(5, TimeUnit.SECONDS)
            }
            logger.info { "Kafka broker connectivity verified at $brokers." }
        } catch (e: Exception) {
            throw BrokerStartupException(
                """
                |
                |═══════════════════════════════════════════════════════════════════════════════
                |  KAFKA CONNECTION FAILED
                |═══════════════════════════════════════════════════════════════════════════════
                |
                |  Unable to connect to Kafka broker at: $brokers
                |
                |  Please verify:
                |    • Kafka is running and accessible
                |    • The broker address in .lemline.yaml is correct
                |    • Network/firewall allows connections to the broker
                |    • Security settings (if any) are correctly configured
                |
                |  Error: ${e.cause?.message ?: e.message}
                |
                |═══════════════════════════════════════════════════════════════════════════════
                """.trimMargin(),
                e
            )
        }
    }

    private fun verifyRabbitMQConnectivity() {
        val rabbitConfig = config.messaging().rabbitmq().orElse(null)
            ?: throw BrokerStartupException(
                """
                |
                |═══════════════════════════════════════════════════════════════════════════════
                |  RABBITMQ CONFIGURATION MISSING
                |═══════════════════════════════════════════════════════════════════════════════
                |
                |  Messaging type is set to 'rabbitmq' but no RabbitMQ configuration found.
                |
                |  Add RabbitMQ configuration to your .lemline.yaml:
                |
                |    lemline:
                |      messaging:
                |        type: rabbitmq
                |        rabbitmq:
                |          hostname: localhost
                |          port: 5672
                |          username: guest
                |          password: guest
                |
                |═══════════════════════════════════════════════════════════════════════════════
                """.trimMargin()
            )

        val hostname = rabbitConfig.hostname().orElse("localhost")
        val port = rabbitConfig.port().orElse(5672)

        try {
            val factory = com.rabbitmq.client.ConnectionFactory().apply {
                host = hostname
                this.port = port
                rabbitConfig.username().ifPresent { username = it }
                rabbitConfig.password().ifPresent { password = it }
                rabbitConfig.virtualHost().ifPresent { virtualHost = it }
                rabbitConfig.sslEnabled().ifPresent { if (it) useSslProtocol() }
                connectionTimeout = 5000
            }

            factory.newConnection().use { connection ->
                connection.createChannel().use { /* Just verify we can create a channel */ }
            }
            logger.info { "RabbitMQ broker connectivity verified at $hostname:$port." }
        } catch (e: Exception) {
            throw BrokerStartupException(
                """
                |
                |═══════════════════════════════════════════════════════════════════════════════
                |  RABBITMQ CONNECTION FAILED
                |═══════════════════════════════════════════════════════════════════════════════
                |
                |  Unable to connect to RabbitMQ at: $hostname:$port
                |
                |  Please verify:
                |    • RabbitMQ is running and accessible
                |    • The connection settings in .lemline.yaml are correct
                |    • Credentials are valid
                |    • Network/firewall allows connections to the broker
                |
                |  Error: ${e.message}
                |
                |═══════════════════════════════════════════════════════════════════════════════
                """.trimMargin(),
                e
            )
        }
    }

    private fun verifyPgmqConnectivity() {
        val pgmqConfig = config.messaging().pgmq().orElse(null)
            ?: throw BrokerStartupException(
                """
                |
                |═══════════════════════════════════════════════════════════════════════════════
                |  PGMQ CONFIGURATION MISSING
                |═══════════════════════════════════════════════════════════════════════════════
                |
                |  Messaging type is set to 'pgmq' but no PGMQ configuration found.
                |
                |  Add PGMQ configuration to your .lemline.yaml:
                |
                |    lemline:
                |      messaging:
                |        type: pgmq
                |        pgmq:
                |          host: localhost
                |          port: 5432
                |          database: lemline
                |          username: postgres
                |          password: postgres
                |
                |═══════════════════════════════════════════════════════════════════════════════
                """.trimMargin()
            )

        val host = pgmqConfig.host()
        val port = pgmqConfig.port()
        val database = pgmqConfig.database()
        val username = pgmqConfig.username()
        val password = pgmqConfig.password().orElse("")

        val jdbcUrl = "jdbc:postgresql://$host:$port/$database"

        try {
            DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
                conn.isValid(5) // 5 second timeout
            }
            logger.info { "PGMQ broker connectivity verified at $host:$port/$database." }
        } catch (e: Exception) {
            throw BrokerStartupException(
                """
                |
                |═══════════════════════════════════════════════════════════════════════════════
                |  PGMQ CONNECTION FAILED
                |═══════════════════════════════════════════════════════════════════════════════
                |
                |  Unable to connect to PostgreSQL (PGMQ) at: $host:$port/$database
                |
                |  Please verify:
                |    • PostgreSQL is running and accessible
                |    • The connection settings in .lemline.yaml are correct
                |    • The database exists and user has access
                |
                |  Error: ${e.message}
                |
                |═══════════════════════════════════════════════════════════════════════════════
                """.trimMargin(),
                e
            )
        }
    }
}

/**
 * Exception thrown when broker startup validation fails.
 * The message is designed to be user-friendly and actionable.
 */
class BrokerStartupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
