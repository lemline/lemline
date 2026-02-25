// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.logger.logger
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_DATABASE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_HOST
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PASSWORD
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PORT
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_USERNAME
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE
import com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL
import com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_DATABASE
import com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_HOST
import com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_PASSWORD
import com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_PORT
import com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_USERNAME
import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES
import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_DATABASE
import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_HOST
import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PASSWORD
import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PORT
import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_USERNAME
import com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE
import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_JWT_ISSUER
import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_JWT_JWKS_URL
import com.lemline.runner.common.config.LEMLINE_GATEWAY_CORS_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_CORS_HEADERS
import com.lemline.runner.common.config.LEMLINE_GATEWAY_CORS_METHODS
import com.lemline.runner.common.config.LEMLINE_GATEWAY_CORS_ORIGINS
import com.lemline.runner.common.config.LEMLINE_GATEWAY_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_GRPC_HOST
import com.lemline.runner.common.config.LEMLINE_GATEWAY_GRPC_PORT
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_CERTIFICATE
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_CLIENT_AUTH
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_PRIVATE_KEY
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_TRUST_STORE
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_TRUST_STORE_PASSWORD
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_BROKERS
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_CONSUMER_GROUP_ID
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_CONSUMER_OFFSET_RESET
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_CONSUMER_TOPIC_DLQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_PRODUCER_TOPIC_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_TOPIC
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_GROUP_ID
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_OFFSET_RESET
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_TOPIC_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_COMMANDS_PRODUCER_TOPIC_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_COMMANDS_TOPIC
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_EVENTS_CONSUMER_GROUP_ID
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_EVENTS_CONSUMER_OFFSET_RESET
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_EVENTS_CONSUMER_TOPIC_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_EVENTS_PRODUCER_TOPIC_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_EVENTS_TOPIC
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_CONSUMER_GROUP_ID
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_CONSUMER_OFFSET_RESET
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_CONSUMER_TOPIC_DLQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_PRODUCER_TOPIC_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_TOPIC
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_SASL_MECHANISM
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_SASL_PASSWORD
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_SASL_USERNAME
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_SECURITY_PROTOCOL
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_BATCH_SIZE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_MAX_RETRIES
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_POLL_INTERVAL
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_QUEUE_DLQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_VISIBILITY_TIMEOUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_PRODUCER_QUEUE_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_BATCH_SIZE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_MAX_RETRIES
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_POLL_INTERVAL
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_QUEUE_DLQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_VISIBILITY_TIMEOUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_COMMANDS_PRODUCER_QUEUE_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_COMMANDS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_DATABASE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_BATCH_SIZE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_MAX_RETRIES
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_POLL_INTERVAL
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_QUEUE_DLQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_VISIBILITY_TIMEOUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_EVENTS_PRODUCER_QUEUE_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_EVENTS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_HOST
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_BATCH_SIZE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_MAX_RETRIES
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_POLL_INTERVAL
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_QUEUE_DLQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_VISIBILITY_TIMEOUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_PRODUCER_QUEUE_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_PASSWORD
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_PORT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_USERNAME
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_CLOUDEVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_CLOUDEVENTS_CONSUMER_QUEUE_DLQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_CLOUDEVENTS_PRODUCER_EXCHANGE_NAME
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_CLOUDEVENTS_PRODUCER_QUEUE_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_CLOUDEVENTS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_COMMANDS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_COMMANDS_CONSUMER_QUEUE_DLQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_COMMANDS_PRODUCER_EXCHANGE_NAME
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_COMMANDS_PRODUCER_QUEUE_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_COMMANDS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_EVENTS_CONSUMER_QUEUE_DLQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_EVENTS_PRODUCER_EXCHANGE_NAME
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_EVENTS_PRODUCER_QUEUE_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_EVENTS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_HOSTNAME
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_CONSUMER_QUEUE_DLQ
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_PRODUCER_EXCHANGE_NAME
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_PRODUCER_QUEUE_OUT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_PASSWORD
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_PORT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_SSL_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_USERNAME
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_VIRTUAL_HOST
import com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.common.config.LEMLINE_PREFIX
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.common.messaging.LIFECYCLEEVENTS_IN_CHANNEL
import com.lemline.runner.common.messaging.LIFECYCLEEVENTS_OUT_CHANNEL
import com.lemline.runner.config.LemlineConfigConstants.ANALYTICS_POSTGRES_BASELINE_ON_MIGRATE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.ANALYTICS_POSTGRES_DATABASE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.CLOUDEVENTS_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.CONSUMER_CONCURRENCY_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_AUTHENTICATION_ENABLED_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_CORS_ENABLED_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_CORS_EXPOSED_HEADERS_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_CORS_HEADERS_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_CORS_METHODS_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_CORS_ORIGINS_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_ENABLED_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_GRPC_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_GRPC_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_TLS_CLIENT_AUTH_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.GATEWAY_TLS_ENABLED_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.H2_DB_NAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.H2_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.H2_USERNAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.IN_MEMORY_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_BROKERS_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_CLOUDEVENTS_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_DATABASE_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_LIFECYCLE_EVENTS_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_OFFSET_RESET_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_STRING_DESERIALIZER
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_STRING_SERIALIZER
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_WORKFLOWS_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.LIFECYCLE_EVENTS_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_DATABASE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_USERNAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_BATCH_SIZE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_MAX_RETRIES_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_POLL_INTERVAL_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_VISIBILITY_TIMEOUT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_DATABASE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_USERNAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_STRING_SERIALIZER
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_USER_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_VHOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.WORKFLOW_COMMANDS_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.WORKFLOW_EVENTS_TOPIC_DEFAULT
import io.smallrye.config.PropertiesConfigSource

internal const val COMMANDS_IN_CHANNEL = "commands-in"
internal const val COMMANDS_OUT_CHANNEL = "commands-out"
internal const val EVENTS_IN_CHANNEL = "events-in"
internal const val EVENTS_OUT_CHANNEL = "events-out"
internal const val CLOUDEVENTS_IN_CHANNEL = "cloudevents-in"
internal const val CLOUDEVENTS_OUT_CHANNEL = "cloudevents-out"

enum class TopicType(
    val type: String,
    val defaultTopicName: String,
    val incomingChannel: String,
    val outgoingChannel: String,
    val consumerEnabled: String,
    val producerEnabled: String,
    val consumerConcurrency: String,
    val consumerGroupDefault: String,
) {
    // Note: 'type' values must match LemlineConfiguration.KafkaConfig interface method names
    // (commands, events) for config validation to pass
    COMMANDS(
        "commands",
        WORKFLOW_COMMANDS_TOPIC_DEFAULT,
        COMMANDS_IN_CHANNEL,
        COMMANDS_OUT_CHANNEL,
        LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_COMMANDS_CONSUMER_CONCURRENCY,
        KAFKA_WORKFLOWS_GROUP_ID_DEFAULT
    ),
    EVENTS(
        "events",
        WORKFLOW_EVENTS_TOPIC_DEFAULT,
        EVENTS_IN_CHANNEL,
        EVENTS_OUT_CHANNEL,
        LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_EVENTS_CONSUMER_CONCURRENCY,
        KAFKA_DATABASE_GROUP_ID_DEFAULT
    );
}

class LemlineConfigSource : PropertiesConfigSource(
    buildProperties(),
    LemlineConfigConstants.CONFIG_SOURCE_NAME,
    LemlineConfigConstants.CONFIG_ORDINAL
) {
    companion object {
        private val logger = logger()
        private const val ANALYTICS_H2_DATASOURCE_NAME = "analytics"
        private const val ANALYTICS_POSTGRES_DATASOURCE_NAME = "analytics-postgresql"

        private fun buildProperties(): Map<String, String> {
            val lemlineProps = mutableMapOf<String, String>()

            logger.info { "LemlineConfigSource.buildProperties() starting..." }
            logger.info { "ConfigPathHolder.configPath = ${ConfigPathHolder.configPath}" }

            // Load user properties from file
            ConfigPathHolder.configPath?.let { path ->
                ExtraFileConfigFactory().getConfig(path).properties.forEach { (name, value) ->
                    if (name.startsWith(LEMLINE_PREFIX)) {
                        lemlineProps[name] = value.replaceFirst(Regex("""\s+#.*$"""), "").trim()
                    }
                }
            }

            logger.info { "Lemline file properties:\n${lemlineProps.toPrint()}" }

            // Log lemline-related system properties before reading
            val lemlineSysProps = System.getProperties()
                .filter { (k, _) -> k.toString().startsWith(LEMLINE_PREFIX) }
                .map { (k, v) -> "$k=${v.toString().maskIfSensitive(k.toString())}" }
            logger.info { "Lemline system properties: $lemlineSysProps" }

            // Override with system properties, as they have higher priority,
            // This includes properties defined in [LemlineApplication]
            System.getProperties().forEach { (key, value) ->
                if (key.toString().startsWith(LEMLINE_PREFIX)) {
                    lemlineProps[key.toString()] = value.toString()
                }
            }

            logger.info { "Lemline merged properties:\n${lemlineProps.toPrint()}" }

            // Generate and merge transformed properties
            val generatedProps = mutableMapOf<String, String>()
            generatedProps.putAll(generateWorkflowDatabaseProperties(lemlineProps))
            generatedProps.putAll(generateAnalyticsDatabaseProperties(lemlineProps))
            generatedProps.putAll(generateMessagingProperties(lemlineProps))
            generatedProps.putAll(generateGatewayProperties(lemlineProps))

            logger.info { "Lemline generated properties:\n${generatedProps.toPrint()}" }

            return mutableMapOf<String, String>().apply {
                putAll(lemlineProps)
                putAll(generatedProps)
            }
        }

        private fun generateWorkflowDatabaseProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()

            val usePostgres = props.keys.any { it.startsWith("$LEMLINE_DATABASE_POSTGRES.") }
            val useMysql = props.keys.any { it.startsWith("$LEMLINE_DATABASE_MYSQL.") }

            val type = props[LEMLINE_DATABASE_TYPE]?.let { DatabaseType.fromConfigValue(it) } ?: run {
                when {
                    usePostgres && useMysql -> throw IllegalArgumentException(
                        "Both properties 'postgresql' and 'mysql' are defined. " +
                            "Explicitly set '$LEMLINE_DATABASE_TYPE' to '${DatabaseType.POSTGRESQL.configValue}' or '${DatabaseType.MYSQL.configValue}'."
                    )

                    usePostgres -> DatabaseType.POSTGRESQL
                    useMysql -> DatabaseType.MYSQL
                    else -> DatabaseType.H2
                }
            }
            generated[LEMLINE_DATABASE_TYPE] = type.configValue

            when (type) {
                DatabaseType.POSTGRESQL -> {
                    val host = props[LEMLINE_DATABASE_POSTGRES_HOST] ?: POSTGRES_HOST_DEFAULT
                    val port = props[LEMLINE_DATABASE_POSTGRES_PORT] ?: POSTGRES_PORT_DEFAULT
                    val database = props[LEMLINE_DATABASE_POSTGRES_DATABASE] ?: POSTGRES_DATABASE_DEFAULT
                    val username = props[LEMLINE_DATABASE_POSTGRES_USERNAME] ?: POSTGRES_USERNAME_DEFAULT
                    val password = props[LEMLINE_DATABASE_POSTGRES_PASSWORD] ?: POSTGRES_PASSWORD_DEFAULT
                    val postgres = "quarkus.datasource.postgresql"
                    generated["$postgres.username"] = username
                    generated["$postgres.password"] = password
                    generated["$postgres.jdbc.url"] = "jdbc:postgresql://$host:$port/$database"
                }

                DatabaseType.MYSQL -> {
                    val host = props[LEMLINE_DATABASE_MYSQL_HOST] ?: MYSQL_HOST_DEFAULT
                    val port = props[LEMLINE_DATABASE_MYSQL_PORT] ?: MYSQL_PORT_DEFAULT
                    val database = props[LEMLINE_DATABASE_MYSQL_DATABASE] ?: MYSQL_DATABASE_DEFAULT
                    val username = props[LEMLINE_DATABASE_MYSQL_USERNAME] ?: MYSQL_USERNAME_DEFAULT
                    val password = props[LEMLINE_DATABASE_MYSQL_PASSWORD] ?: MYSQL_PASSWORD_DEFAULT
                    val mysql = "quarkus.datasource.mysql"
                    generated["$mysql.username"] = username
                    generated["$mysql.password"] = password
                    generated["$mysql.jdbc.url"] = "jdbc:mysql://$host:$port/$database" +
                        "?useSSL=false" +
                        "&allowPublicKeyRetrieval=true" +
                        "&sessionVariables=sql_mode='STRICT_ALL_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ZERO_DATE,NO_ZERO_IN_DATE,NO_ENGINE_SUBSTITUTION'" +
                        "&continueBatchOnError=false"
                }

                DatabaseType.H2 -> {
                    val h2 = "quarkus.datasource" // <- default datasource
                    generated["$h2.username"] = H2_USERNAME_DEFAULT
                    generated["$h2.password"] = H2_PASSWORD_DEFAULT
                    generated["$h2.jdbc.url"] =
                        "jdbc:h2:mem:$H2_DB_NAME_DEFAULT;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                }
            }

            return generated
        }

        private fun generateAnalyticsDatabaseProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            val lifecycleConsumerEnabled = props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED].toBoolean()
            val gatewayEnabled = props[LEMLINE_GATEWAY_ENABLED]?.toBooleanStrictOrNull()
                ?: GATEWAY_ENABLED_DEFAULT.toBoolean()

            val needsAnalyticsDatasource = lifecycleConsumerEnabled || gatewayEnabled
            if (!needsAnalyticsDatasource) return emptyMap()

            val hasPostgresqlProps = props.keys.any { it.startsWith("$LEMLINE_ANALYTICS_POSTGRES.") }
            val type = props[LEMLINE_ANALYTICS_TYPE]?.let { AnalyticsType.fromConfigValue(it) } ?: run {
                if (hasPostgresqlProps) AnalyticsType.POSTGRESQL else AnalyticsType.H2
            }
            generated[LEMLINE_ANALYTICS_TYPE] = type.configValue

            if (type == AnalyticsType.H2) return generated

            val host = props[LEMLINE_ANALYTICS_POSTGRES_HOST] ?: POSTGRES_HOST_DEFAULT
            val port = props[LEMLINE_ANALYTICS_POSTGRES_PORT] ?: POSTGRES_PORT_DEFAULT
            val database = props[LEMLINE_ANALYTICS_POSTGRES_DATABASE] ?: ANALYTICS_POSTGRES_DATABASE_DEFAULT
            val username = props[LEMLINE_ANALYTICS_POSTGRES_USERNAME] ?: POSTGRES_USERNAME_DEFAULT
            val password = props[LEMLINE_ANALYTICS_POSTGRES_PASSWORD] ?: POSTGRES_PASSWORD_DEFAULT
            val baselineOnMigrate = props[LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE]
                ?: ANALYTICS_POSTGRES_BASELINE_ON_MIGRATE_DEFAULT
            val datasource = "quarkus.datasource.$ANALYTICS_POSTGRES_DATASOURCE_NAME"
            val flyway = "quarkus.flyway.$ANALYTICS_POSTGRES_DATASOURCE_NAME"

            generated["$datasource.username"] = username
            generated["$datasource.password"] = password
            generated["$datasource.jdbc.url"] = "jdbc:postgresql://$host:$port/$database"
            generated["$flyway.baseline-on-migrate"] = baselineOnMigrate
            generated["$flyway.locations"] = "classpath:db/migration/analytics/postgresql"

            return generated
        }

        private fun generateGatewayProperties(props: Map<String, String>): Map<String, String> {
            val enabled = props[LEMLINE_GATEWAY_ENABLED]?.toBooleanStrictOrNull()
                ?: GATEWAY_ENABLED_DEFAULT.toBoolean()
            val tlsEnabled = props[LEMLINE_GATEWAY_TLS_ENABLED]?.toBooleanStrictOrNull()
                ?: GATEWAY_TLS_ENABLED_DEFAULT.toBoolean()
            val authenticationEnabled = props[LEMLINE_GATEWAY_AUTHENTICATION_ENABLED]?.toBooleanStrictOrNull()
                ?: GATEWAY_AUTHENTICATION_ENABLED_DEFAULT.toBoolean()

            val generatedProps = mutableMapOf<String, String>()
            generatedProps.putAll(generateGatewayGrpcProperties(props, enabled, tlsEnabled))
            if (enabled && authenticationEnabled) {
                generatedProps.putAll(generateGatewayJwtProperties(props))
            }
            return generatedProps
        }

        private fun generateGatewayGrpcProperties(
            props: Map<String, String>,
            enabled: Boolean,
            tlsEnabled: Boolean
        ): Map<String, String> {
            val generated = mutableMapOf<String, String>()

            if (!enabled) {
                generated["quarkus.grpc.server.port"] = "0"
                return generated
            }

            val gatewayPort = props[LEMLINE_GATEWAY_GRPC_PORT] ?: GATEWAY_GRPC_PORT_DEFAULT
            generated["quarkus.grpc.server.host"] = props[LEMLINE_GATEWAY_GRPC_HOST] ?: GATEWAY_GRPC_HOST_DEFAULT
            generated["quarkus.grpc.server.port"] = gatewayPort
            generated["quarkus.grpc.server.plain-text"] = (!tlsEnabled).toString()
            generated["quarkus.grpc.server.use-separate-server"] = "false"
            // gRPC-Web runs through the HTTP server, so bind the active transport port.
            if (tlsEnabled) {
                generated["quarkus.http.ssl-port"] = gatewayPort
            } else {
                generated["quarkus.http.port"] = gatewayPort
            }

            val corsEnabled = props[LEMLINE_GATEWAY_CORS_ENABLED]?.toBooleanStrictOrNull()
                ?: GATEWAY_CORS_ENABLED_DEFAULT.toBoolean()
            generated["quarkus.http.cors.enabled"] = corsEnabled.toString()
            if (corsEnabled) {
                generated["quarkus.http.cors.origins"] =
                    props[LEMLINE_GATEWAY_CORS_ORIGINS] ?: GATEWAY_CORS_ORIGINS_DEFAULT
                generated["quarkus.http.cors.methods"] =
                    props[LEMLINE_GATEWAY_CORS_METHODS] ?: GATEWAY_CORS_METHODS_DEFAULT
                generated["quarkus.http.cors.headers"] =
                    props[LEMLINE_GATEWAY_CORS_HEADERS] ?: GATEWAY_CORS_HEADERS_DEFAULT
                generated["quarkus.http.cors.exposed-headers"] = GATEWAY_CORS_EXPOSED_HEADERS_DEFAULT
                generated["quarkus.http.cors.access-control-allow-credentials"] = "false"
            }

            if (tlsEnabled) {
                // Keep gRPC SSL config for deployments using a separate gRPC server.
                generated["quarkus.grpc.server.ssl.client-auth"] =
                    props[LEMLINE_GATEWAY_TLS_CLIENT_AUTH] ?: GATEWAY_TLS_CLIENT_AUTH_DEFAULT

                props[LEMLINE_GATEWAY_TLS_CERTIFICATE]?.let {
                    generated["quarkus.grpc.server.ssl.certificate"] = it
                }
                props[LEMLINE_GATEWAY_TLS_PRIVATE_KEY]?.let {
                    generated["quarkus.grpc.server.ssl.key"] = it
                }
                props[LEMLINE_GATEWAY_TLS_TRUST_STORE]?.let {
                    generated["quarkus.grpc.server.ssl.trust-store"] = it
                }
                props[LEMLINE_GATEWAY_TLS_TRUST_STORE_PASSWORD]?.let {
                    generated["quarkus.grpc.server.ssl.trust-store-password"] = it
                }

                // With use-separate-server=false, TLS is terminated by the main HTTP server.
                props[LEMLINE_GATEWAY_TLS_CERTIFICATE]?.let {
                    generated["quarkus.http.ssl.certificate.files"] = it
                }
                props[LEMLINE_GATEWAY_TLS_PRIVATE_KEY]?.let {
                    generated["quarkus.http.ssl.certificate.key-files"] = it
                }
                generated["quarkus.http.http2"] = "true"
            } else {
                generated["quarkus.grpc.server.ssl.client-auth"] = "none"
            }

            return generated
        }

        private fun generateGatewayJwtProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            props[LEMLINE_GATEWAY_AUTHENTICATION_JWT_ISSUER]?.let {
                generated["mp.jwt.verify.issuer"] = it
            }
            props[LEMLINE_GATEWAY_AUTHENTICATION_JWT_JWKS_URL]?.let {
                generated["smallrye.jwt.verify.key.location"] = it
            }
            return generated
        }

        private fun generateMessagingProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            val useKafka = props.keys.any { it.startsWith("$LEMLINE_MESSAGING_KAFKA.") }
            val useRabbit = props.keys.any { it.startsWith("$LEMLINE_MESSAGING_RABBITMQ.") }
            val usePgmq = props.keys.any { it.startsWith("$LEMLINE_MESSAGING_PGMQ.") }

            val messagingTypes = listOfNotNull(
                if (useKafka) MessagingType.KAFKA else null,
                if (useRabbit) MessagingType.RABBITMQ else null,
                if (usePgmq) MessagingType.PGMQ else null
            )

            val type = props[LEMLINE_MESSAGING_TYPE]?.let { MessagingType.fromConfigValue(it) } ?: run {
                when {
                    messagingTypes.size > 1 -> throw IllegalArgumentException(
                        "Multiple messaging types defined: ${messagingTypes.joinToString { it.configValue }}. " +
                            "Explicitly set '$LEMLINE_MESSAGING_TYPE' to one of: ${MessagingType.KAFKA.configValue}, ${MessagingType.RABBITMQ.configValue}, ${MessagingType.PGMQ.configValue}."
                    )

                    useKafka -> MessagingType.KAFKA
                    useRabbit -> MessagingType.RABBITMQ
                    usePgmq -> MessagingType.PGMQ
                    else -> MessagingType.IN_MEMORY
                }
            }
            generated[LEMLINE_MESSAGING_TYPE] = type.configValue

            when (type) {
                MessagingType.KAFKA -> generated.configureKafka(props)
                MessagingType.RABBITMQ -> generated.configureRabbit(props)
                MessagingType.PGMQ -> generated.configurePgmq(props)
                MessagingType.IN_MEMORY -> generated.configureInMemory(props)
            }

            return generated
        }


        private fun MutableMap<String, String>.configureKafka(props: Map<String, String>) {
            // With Default values
            set("kafka.bootstrap.servers", props[LEMLINE_MESSAGING_KAFKA_BROKERS] ?: KAFKA_BROKERS_DEFAULT)
            // Optional values
            props[LEMLINE_MESSAGING_KAFKA_SECURITY_PROTOCOL]?.let { set("kafka.security.protocol", it) }
            props[LEMLINE_MESSAGING_KAFKA_SASL_MECHANISM]?.let { set("kafka.sasl.mechanism", it) }

            if (props.containsKey(LEMLINE_MESSAGING_KAFKA_SASL_USERNAME) &&
                props.containsKey(LEMLINE_MESSAGING_KAFKA_SASL_PASSWORD)
            ) {
                val saslUsername = props[LEMLINE_MESSAGING_KAFKA_SASL_USERNAME]!!
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                val saslPassword = props[LEMLINE_MESSAGING_KAFKA_SASL_PASSWORD]!!
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                set(
                    "kafka.sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                        "username=\"$saslUsername\" " +
                        "password=\"$saslPassword\";"
                )
                if (!containsKey("kafka.sasl.mechanism")) set("kafka.sasl.mechanism", "PLAIN")
            }

            // Log the enabled flags for debugging
            logger.info {
                "Kafka channel enabled flags: " +
                    "commands.consumer=${props[LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED]}, " +
                    "commands.producer=${props[LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED]}, " +
                    "events.consumer=${props[LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED]}, " +
                    "events.producer=${props[LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED]}"
            }

            configureKafkaTopic(props, TopicType.COMMANDS)
            configureKafkaTopic(props, TopicType.EVENTS)
            configureKafkaCloudEventsTopic(props)
            configureKafkaLifecycleEventsTopic(props)
        }

        private fun MutableMap<String, String>.configureKafkaTopic(
            props: Map<String, String>,
            topicType: TopicType
        ) {
            val topic = when (topicType) {
                TopicType.COMMANDS -> props[LEMLINE_MESSAGING_KAFKA_COMMANDS_TOPIC]
                TopicType.EVENTS -> props[LEMLINE_MESSAGING_KAFKA_EVENTS_TOPIC]
            } ?: topicType.defaultTopicName

            if (props[topicType.consumerEnabled].toBoolean()) {
                val incoming = "mp.messaging.incoming.${topicType.incomingChannel}"
                val topicDLQ = when (topicType) {
                    TopicType.COMMANDS -> props[LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_TOPIC_OUT]
                    TopicType.EVENTS -> props[LEMLINE_MESSAGING_KAFKA_EVENTS_CONSUMER_TOPIC_OUT]
                } ?: "$topic.dlq"
                set("$incoming.connector", KAFKA_CONNECTOR)
                set("$incoming.topic", topic)
                set(
                    "$incoming.group.id",
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_GROUP_ID]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_KAFKA_EVENTS_CONSUMER_GROUP_ID]
                    } ?: topicType.consumerGroupDefault
                )
                set(
                    "$incoming.auto.offset.reset",
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_OFFSET_RESET]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_KAFKA_EVENTS_CONSUMER_OFFSET_RESET]
                    } ?: KAFKA_OFFSET_RESET_DEFAULT
                )
                set("$incoming.value.deserializer", KAFKA_STRING_DESERIALIZER)
                set("$incoming.failure-strategy", "dead-letter-queue")
                set("$incoming.dead-letter-queue.topic", topicDLQ)
                // set the consumer concurrency
                set(
                    topicType.consumerConcurrency,
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_CONCURRENCY]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_KAFKA_EVENTS_CONSUMER_CONCURRENCY]
                    } ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            if (props[topicType.producerEnabled].toBoolean()) {
                val outgoing = "mp.messaging.outgoing.${topicType.outgoingChannel}"
                set("$outgoing.connector", KAFKA_CONNECTOR)
                set(
                    "$outgoing.topic",
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_KAFKA_COMMANDS_PRODUCER_TOPIC_OUT]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_KAFKA_EVENTS_PRODUCER_TOPIC_OUT]
                    } ?: topic
                )
                set("$outgoing.value.serializer", KAFKA_STRING_SERIALIZER)
                set("$outgoing.acks", "all")
            }
        }

        /**
         * Configures the CloudEvents Kafka topic.
         * - Consumer: Receives CloudEvents from external sources for listen tasks
         * - Producer: Emits CloudEvents from emit tasks
         */
        private fun MutableMap<String, String>.configureKafkaCloudEventsTopic(props: Map<String, String>) {
            val topic = props[LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_TOPIC] ?: CLOUDEVENTS_TOPIC_DEFAULT

            // Consumer configuration for listen tasks
            if (props[LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED].toBoolean()) {
                val incoming = "mp.messaging.incoming.$CLOUDEVENTS_IN_CHANNEL"
                val topicDLQ = props[LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_CONSUMER_TOPIC_DLQ] ?: "$topic.dlq"
                set("$incoming.connector", KAFKA_CONNECTOR)
                set("$incoming.topic", topic)
                set(
                    "$incoming.group.id",
                    props[LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_CONSUMER_GROUP_ID] ?: KAFKA_CLOUDEVENTS_GROUP_ID_DEFAULT
                )
                set(
                    "$incoming.auto.offset.reset",
                    props[LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_CONSUMER_OFFSET_RESET] ?: KAFKA_OFFSET_RESET_DEFAULT
                )
                set("$incoming.value.deserializer", KAFKA_STRING_DESERIALIZER)
                set("$incoming.failure-strategy", "dead-letter-queue")
                set("$incoming.dead-letter-queue.topic", topicDLQ)
                set(
                    LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_CONCURRENCY,
                    props[LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_CONSUMER_CONCURRENCY] ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            // Producer configuration for emit tasks
            if (props[LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED].toBoolean()) {
                val outgoing = "mp.messaging.outgoing.$CLOUDEVENTS_OUT_CHANNEL"
                set("$outgoing.connector", KAFKA_CONNECTOR)
                set("$outgoing.topic", props[LEMLINE_MESSAGING_KAFKA_CLOUDEVENTS_PRODUCER_TOPIC_OUT] ?: topic)
                set("$outgoing.value.serializer", KAFKA_STRING_SERIALIZER)
                set("$outgoing.acks", "all")
            }
        }

        /**
         * Configures the Lifecycle Events Kafka topic.
         * - Producer: Emits workflow and task lifecycle events
         * - Consumer: Analytics ingestion from the same lifecycle events stream
         */
        private fun MutableMap<String, String>.configureKafkaLifecycleEventsTopic(props: Map<String, String>) {
            val topic = props[LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_TOPIC] ?: LIFECYCLE_EVENTS_TOPIC_DEFAULT

            if (props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED].toBoolean()) {
                val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
                val topicDLQ = props[LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_CONSUMER_TOPIC_DLQ] ?: "$topic.dlq"
                set("$incoming.connector", KAFKA_CONNECTOR)
                set("$incoming.topic", topic)
                set("$incoming.broadcast", "true")
                set(
                    "$incoming.group.id",
                    props[LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_CONSUMER_GROUP_ID]
                        ?: KAFKA_LIFECYCLE_EVENTS_GROUP_ID_DEFAULT
                )
                set(
                    "$incoming.auto.offset.reset",
                    props[LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_CONSUMER_OFFSET_RESET]
                        ?: KAFKA_OFFSET_RESET_DEFAULT
                )
                set("$incoming.value.deserializer", KAFKA_STRING_DESERIALIZER)
                set("$incoming.failure-strategy", "dead-letter-queue")
                set("$incoming.dead-letter-queue.topic", topicDLQ)
                set(
                    LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY,
                    props[LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY]
                        ?: props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY]
                        ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            if (props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED].toBoolean()) {
                val outgoing = "mp.messaging.outgoing.$LIFECYCLEEVENTS_OUT_CHANNEL"
                set("$outgoing.connector", KAFKA_CONNECTOR)
                set("$outgoing.topic", props[LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_PRODUCER_TOPIC_OUT] ?: topic)
                set("$outgoing.value.serializer", KAFKA_STRING_SERIALIZER)
                set("$outgoing.acks", "all")
            }
        }

        private fun MutableMap<String, String>.configureRabbit(props: Map<String, String>) {
            // Values with Default
            set("rabbitmq-host", props[LEMLINE_MESSAGING_RABBITMQ_HOSTNAME] ?: RABBITMQ_HOST_DEFAULT)
            set("rabbitmq-port", props[LEMLINE_MESSAGING_RABBITMQ_PORT] ?: RABBITMQ_PORT_DEFAULT)
            set("rabbitmq-username", props[LEMLINE_MESSAGING_RABBITMQ_USERNAME] ?: RABBITMQ_USER_DEFAULT)
            set("rabbitmq-password", props[LEMLINE_MESSAGING_RABBITMQ_PASSWORD] ?: RABBITMQ_PASSWORD_DEFAULT)
            set("rabbitmq-virtual-host", props[LEMLINE_MESSAGING_RABBITMQ_VIRTUAL_HOST] ?: RABBITMQ_VHOST_DEFAULT)
            // Optional values
            props[LEMLINE_MESSAGING_RABBITMQ_SSL_ENABLED]?.let { set("rabbitmq-ssl", it) }

            configureRabbitQueue(props, TopicType.COMMANDS)
            configureRabbitQueue(props, TopicType.EVENTS)
            configureRabbitCloudEventsQueue(props)
            configureRabbitLifecycleEventsQueue(props)
        }

        private fun MutableMap<String, String>.configureRabbitQueue(
            props: Map<String, String>,
            topicType: TopicType
        ) {
            val queue = when (topicType) {
                TopicType.COMMANDS -> props[LEMLINE_MESSAGING_RABBITMQ_COMMANDS_QUEUE]
                TopicType.EVENTS -> props[LEMLINE_MESSAGING_RABBITMQ_EVENTS_QUEUE]
            } ?: topicType.defaultTopicName

            if (props[topicType.consumerEnabled].toBoolean()) {
                val incoming = "mp.messaging.incoming.${topicType.incomingChannel}"
                val queueDLQ = when (topicType) {
                    TopicType.COMMANDS -> props[LEMLINE_MESSAGING_RABBITMQ_COMMANDS_CONSUMER_QUEUE_DLQ]
                    TopicType.EVENTS -> props[LEMLINE_MESSAGING_RABBITMQ_EVENTS_CONSUMER_QUEUE_DLQ]
                } ?: "$queue.dlq"
                set("$incoming.connector", RABBITMQ_CONNECTOR)
                set("$incoming.queue.name", queue)
                set("$incoming.queue.durable", "true")
                set("$incoming.auto-ack", "false")
                set("$incoming.deserializer", RABBITMQ_STRING_SERIALIZER)
                set("$incoming.failure-strategy", "reject") // do not retry
                set("$incoming.auto-bind-dlq", "true")
                set("$incoming.dlx.declare", "true")
                set("$incoming.dead-letter-queue-name", queueDLQ)
                set("$incoming.dead-letter-exchange", "${topicType.incomingChannel}.dlx")
                set("$incoming.dead-letter-exchange-type", "direct")
                set("$incoming.dead-letter-routing-key", queueDLQ)
                // set the consumer concurrency
                set(
                    topicType.consumerConcurrency, when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_RABBITMQ_COMMANDS_CONSUMER_CONCURRENCY]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_RABBITMQ_EVENTS_CONSUMER_CONCURRENCY]
                    } ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            if (props[topicType.producerEnabled].toBoolean()) {
                val outgoing = "mp.messaging.outgoing.${topicType.outgoingChannel}"
                set("$outgoing.connector", RABBITMQ_CONNECTOR)
                set(
                    "$outgoing.queue.name",
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_RABBITMQ_COMMANDS_PRODUCER_QUEUE_OUT]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_RABBITMQ_EVENTS_PRODUCER_QUEUE_OUT]
                    } ?: queue
                )
                set("$outgoing.serializer", RABBITMQ_STRING_SERIALIZER)
                set("$outgoing.delivery-mode", "persistent")
                when (topicType) {
                    TopicType.COMMANDS -> props[LEMLINE_MESSAGING_RABBITMQ_COMMANDS_PRODUCER_EXCHANGE_NAME]
                    TopicType.EVENTS -> props[LEMLINE_MESSAGING_RABBITMQ_EVENTS_PRODUCER_EXCHANGE_NAME]
                }?.let { set("$outgoing.exchange.name", it) }
            }
        }

        /**
         * Configures the CloudEvents RabbitMQ queue.
         * - Consumer: Receives CloudEvents from external sources for listen tasks
         * - Producer: Emits CloudEvents from emit tasks
         */
        private fun MutableMap<String, String>.configureRabbitCloudEventsQueue(props: Map<String, String>) {
            val queue = props[LEMLINE_MESSAGING_RABBITMQ_CLOUDEVENTS_QUEUE] ?: CLOUDEVENTS_TOPIC_DEFAULT

            // Consumer configuration for listen tasks
            if (props[LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED].toBoolean()) {
                val incoming = "mp.messaging.incoming.$CLOUDEVENTS_IN_CHANNEL"
                val queueDLQ = props[LEMLINE_MESSAGING_RABBITMQ_CLOUDEVENTS_CONSUMER_QUEUE_DLQ]
                    ?: "$queue.dlq"
                set("$incoming.connector", RABBITMQ_CONNECTOR)
                set("$incoming.queue.name", queue)
                set("$incoming.queue.durable", "true")
                set("$incoming.auto-ack", "false")
                set("$incoming.deserializer", RABBITMQ_STRING_SERIALIZER)
                set("$incoming.failure-strategy", "reject")
                set("$incoming.auto-bind-dlq", "true")
                set("$incoming.dlx.declare", "true")
                set("$incoming.dead-letter-queue-name", queueDLQ)
                set("$incoming.dead-letter-exchange", "$CLOUDEVENTS_IN_CHANNEL.dlx")
                set("$incoming.dead-letter-exchange-type", "direct")
                set("$incoming.dead-letter-routing-key", queueDLQ)
                set(
                    LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_CONCURRENCY,
                    props[LEMLINE_MESSAGING_RABBITMQ_CLOUDEVENTS_CONSUMER_CONCURRENCY] ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            // Producer configuration for emit tasks
            if (props[LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED].toBoolean()) {
                val outgoing = "mp.messaging.outgoing.$CLOUDEVENTS_OUT_CHANNEL"
                set("$outgoing.connector", RABBITMQ_CONNECTOR)
                set("$outgoing.queue.name", props[LEMLINE_MESSAGING_RABBITMQ_CLOUDEVENTS_PRODUCER_QUEUE_OUT] ?: queue)
                set("$outgoing.serializer", RABBITMQ_STRING_SERIALIZER)
                set("$outgoing.delivery-mode", "persistent")
                props[LEMLINE_MESSAGING_RABBITMQ_CLOUDEVENTS_PRODUCER_EXCHANGE_NAME]?.let {
                    set("$outgoing.exchange.name", it)
                }
            }
        }

        /**
         * Configures the Lifecycle Events RabbitMQ queue.
         * - Producer: Emits workflow and task lifecycle events
         * - Consumer: Analytics ingestion from the same lifecycle events destination
         */
        private fun MutableMap<String, String>.configureRabbitLifecycleEventsQueue(props: Map<String, String>) {
            val queue = props[LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_QUEUE] ?: LIFECYCLE_EVENTS_TOPIC_DEFAULT

            if (props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED].toBoolean()) {
                val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
                val queueDLQ = props[LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_CONSUMER_QUEUE_DLQ] ?: "$queue.dlq"
                set("$incoming.connector", RABBITMQ_CONNECTOR)
                set("$incoming.queue.name", queue)
                set("$incoming.broadcast", "true")
                set("$incoming.queue.durable", "true")
                set("$incoming.auto-ack", "false")
                set("$incoming.deserializer", RABBITMQ_STRING_SERIALIZER)
                set("$incoming.failure-strategy", "reject")
                set("$incoming.auto-bind-dlq", "true")
                set("$incoming.dlx.declare", "true")
                set("$incoming.dead-letter-queue-name", queueDLQ)
                set("$incoming.dead-letter-exchange", "$LIFECYCLEEVENTS_IN_CHANNEL.dlx")
                set("$incoming.dead-letter-exchange-type", "direct")
                set("$incoming.dead-letter-routing-key", queueDLQ)
                props[LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_PRODUCER_EXCHANGE_NAME]?.let { exchange ->
                    set("$incoming.exchange.name", exchange)
                }
                set(
                    LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY,
                    props[LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY]
                        ?: props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY]
                        ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            if (props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED].toBoolean()) {
                val outgoing = "mp.messaging.outgoing.$LIFECYCLEEVENTS_OUT_CHANNEL"
                set("$outgoing.connector", RABBITMQ_CONNECTOR)
                set(
                    "$outgoing.queue.name",
                    props[LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_PRODUCER_QUEUE_OUT] ?: queue
                )
                set("$outgoing.serializer", RABBITMQ_STRING_SERIALIZER)
                set("$outgoing.delivery-mode", "persistent")
                props[LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_PRODUCER_EXCHANGE_NAME]?.let {
                    set("$outgoing.exchange.name", it)
                }
            }
        }

        /**
         * Configures PGMQ (PostgreSQL Message Queue) channels.
         * Uses PostgreSQL as a message broker via the PGMQ extension.
         */
        private fun MutableMap<String, String>.configurePgmq(props: Map<String, String>) {
            // PostgreSQL connection settings (reuses database config or can be overridden)
            val host = props[LEMLINE_MESSAGING_PGMQ_HOST] ?: POSTGRES_HOST_DEFAULT
            val port = props[LEMLINE_MESSAGING_PGMQ_PORT] ?: POSTGRES_PORT_DEFAULT
            val database = props[LEMLINE_MESSAGING_PGMQ_DATABASE] ?: POSTGRES_DATABASE_DEFAULT
            val username = props[LEMLINE_MESSAGING_PGMQ_USERNAME] ?: POSTGRES_USERNAME_DEFAULT
            val password = props[LEMLINE_MESSAGING_PGMQ_PASSWORD] ?: POSTGRES_PASSWORD_DEFAULT

            // Set global PGMQ connector defaults - channels can inherit these
            set("pgmq.host", host)
            set("pgmq.port", port)
            set("pgmq.database", database)
            set("pgmq.username", username)
            set("pgmq.password", password)

            configurePgmqQueue(props, TopicType.COMMANDS, host, port, database, username, password)
            configurePgmqQueue(props, TopicType.EVENTS, host, port, database, username, password)
            configurePgmqCloudEventsQueue(props, host, port, database, username, password)
            configurePgmqLifecycleEventsQueue(props, host, port, database, username, password)
        }

        private fun MutableMap<String, String>.configurePgmqQueue(
            props: Map<String, String>,
            topicType: TopicType,
            host: String,
            port: String,
            database: String,
            username: String,
            password: String
        ) {
            val queue = when (topicType) {
                TopicType.COMMANDS -> props[LEMLINE_MESSAGING_PGMQ_COMMANDS_QUEUE]
                TopicType.EVENTS -> props[LEMLINE_MESSAGING_PGMQ_EVENTS_QUEUE]
            } ?: topicType.defaultTopicName

            if (props[topicType.consumerEnabled].toBoolean()) {
                val queueDLQ = when (topicType) {
                    TopicType.COMMANDS -> props[LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_QUEUE_DLQ]
                    TopicType.EVENTS -> props[LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_QUEUE_DLQ]
                } ?: "$queue.dlq"
                val incoming = "mp.messaging.incoming.${topicType.incomingChannel}"

                set("$incoming.connector", PGMQ_CONNECTOR)
                set("$incoming.queue", queue)
                set("$incoming.host", host)
                set("$incoming.port", port)
                set("$incoming.database", database)
                set("$incoming.username", username)
                set("$incoming.password", password)
                set(
                    "$incoming.visibility-timeout",
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_VISIBILITY_TIMEOUT]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_VISIBILITY_TIMEOUT]
                    } ?: PGMQ_VISIBILITY_TIMEOUT_DEFAULT
                )
                set(
                    "$incoming.poll-interval",
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_POLL_INTERVAL]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_POLL_INTERVAL]
                    } ?: PGMQ_POLL_INTERVAL_DEFAULT
                )
                set(
                    "$incoming.batch-size",
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_BATCH_SIZE]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_BATCH_SIZE]
                    } ?: PGMQ_BATCH_SIZE_DEFAULT
                )
                set(
                    "$incoming.max-retries",
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_MAX_RETRIES]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_MAX_RETRIES]
                    } ?: PGMQ_MAX_RETRIES_DEFAULT
                )
                set("$incoming.dead-letter-queue", queueDLQ)
                set("$incoming.auto-create-queue", "false")
                // Set consumer concurrency
                set(
                    topicType.consumerConcurrency,
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_PGMQ_COMMANDS_CONSUMER_CONCURRENCY]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_PGMQ_EVENTS_CONSUMER_CONCURRENCY]
                    } ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            if (props[topicType.producerEnabled].toBoolean()) {
                val outgoing = "mp.messaging.outgoing.${topicType.outgoingChannel}"

                set("$outgoing.connector", PGMQ_CONNECTOR)
                set(
                    "$outgoing.queue",
                    when (topicType) {
                        TopicType.COMMANDS -> props[LEMLINE_MESSAGING_PGMQ_COMMANDS_PRODUCER_QUEUE_OUT]
                        TopicType.EVENTS -> props[LEMLINE_MESSAGING_PGMQ_EVENTS_PRODUCER_QUEUE_OUT]
                    } ?: queue
                )
                set("$outgoing.host", host)
                set("$outgoing.port", port)
                set("$outgoing.database", database)
                set("$outgoing.username", username)
                set("$outgoing.password", password)
                set("$outgoing.auto-create-queue", "false")
            }
        }

        /**
         * Configures the CloudEvents PGMQ queue.
         */
        private fun MutableMap<String, String>.configurePgmqCloudEventsQueue(
            props: Map<String, String>,
            host: String,
            port: String,
            database: String,
            username: String,
            password: String
        ) {
            val queue = props[LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_QUEUE] ?: CLOUDEVENTS_TOPIC_DEFAULT

            // Consumer configuration for listen tasks
            if (props[LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED].toBoolean()) {
                val incoming = "mp.messaging.incoming.$CLOUDEVENTS_IN_CHANNEL"
                val queueDLQ = props[LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_QUEUE_DLQ] ?: "$queue.dlq"

                set("$incoming.connector", PGMQ_CONNECTOR)
                set("$incoming.queue", queue)
                set("$incoming.host", host)
                set("$incoming.port", port)
                set("$incoming.database", database)
                set("$incoming.username", username)
                set("$incoming.password", password)
                set(
                    "$incoming.visibility-timeout",
                    props[LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_VISIBILITY_TIMEOUT]
                        ?: PGMQ_VISIBILITY_TIMEOUT_DEFAULT
                )
                set(
                    "$incoming.poll-interval",
                    props[LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_POLL_INTERVAL] ?: PGMQ_POLL_INTERVAL_DEFAULT
                )
                set(
                    "$incoming.batch-size",
                    props[LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_BATCH_SIZE] ?: PGMQ_BATCH_SIZE_DEFAULT
                )
                set(
                    "$incoming.max-retries",
                    props[LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_MAX_RETRIES] ?: PGMQ_MAX_RETRIES_DEFAULT
                )
                set("$incoming.dead-letter-queue", queueDLQ)
                set("$incoming.auto-create-queue", "false")
                set(
                    LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_CONCURRENCY,
                    props[LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_CONSUMER_CONCURRENCY] ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            // Producer configuration for emit tasks
            if (props[LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED].toBoolean()) {
                val outgoing = "mp.messaging.outgoing.$CLOUDEVENTS_OUT_CHANNEL"

                set("$outgoing.connector", PGMQ_CONNECTOR)
                set("$outgoing.queue", props[LEMLINE_MESSAGING_PGMQ_CLOUDEVENTS_PRODUCER_QUEUE_OUT] ?: queue)
                set("$outgoing.host", host)
                set("$outgoing.port", port)
                set("$outgoing.database", database)
                set("$outgoing.username", username)
                set("$outgoing.password", password)
                set("$outgoing.auto-create-queue", "false")
            }
        }

        /**
         * Configures the Lifecycle Events PGMQ queue.
         * - Producer: Emits workflow and task lifecycle events
         * - Consumer: Analytics ingestion from the same lifecycle events queue
         */
        private fun MutableMap<String, String>.configurePgmqLifecycleEventsQueue(
            props: Map<String, String>,
            host: String,
            port: String,
            database: String,
            username: String,
            password: String
        ) {
            val queue = props[LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_QUEUE] ?: LIFECYCLE_EVENTS_TOPIC_DEFAULT

            if (props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED].toBoolean()) {
                val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
                val queueDLQ = props[LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_QUEUE_DLQ] ?: "$queue.dlq"

                set("$incoming.connector", PGMQ_CONNECTOR)
                set("$incoming.queue", queue)
                set("$incoming.broadcast", "true")
                set("$incoming.host", host)
                set("$incoming.port", port)
                set("$incoming.database", database)
                set("$incoming.username", username)
                set("$incoming.password", password)
                set(
                    "$incoming.visibility-timeout",
                    props[LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_VISIBILITY_TIMEOUT]
                        ?: PGMQ_VISIBILITY_TIMEOUT_DEFAULT
                )
                set(
                    "$incoming.poll-interval",
                    props[LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_POLL_INTERVAL] ?: PGMQ_POLL_INTERVAL_DEFAULT
                )
                set(
                    "$incoming.batch-size",
                    props[LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_BATCH_SIZE] ?: PGMQ_BATCH_SIZE_DEFAULT
                )
                set(
                    "$incoming.max-retries",
                    props[LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_MAX_RETRIES] ?: PGMQ_MAX_RETRIES_DEFAULT
                )
                set("$incoming.dead-letter-queue", queueDLQ)
                set("$incoming.auto-create-queue", "false")
                set(
                    LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY,
                    props[LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY]
                        ?: props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY]
                        ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            if (props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED].toBoolean()) {
                val outgoing = "mp.messaging.outgoing.$LIFECYCLEEVENTS_OUT_CHANNEL"

                set("$outgoing.connector", PGMQ_CONNECTOR)
                set("$outgoing.queue", props[LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_PRODUCER_QUEUE_OUT] ?: queue)
                set("$outgoing.host", host)
                set("$outgoing.port", port)
                set("$outgoing.database", database)
                set("$outgoing.username", username)
                set("$outgoing.password", password)
                set("$outgoing.auto-create-queue", "false")
            }
        }

        private fun MutableMap<String, String>.configureInMemory(props: Map<String, String>) {
            set("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.connector", IN_MEMORY_CONNECTOR)
            set("mp.messaging.outgoing.$COMMANDS_OUT_CHANNEL.connector", IN_MEMORY_CONNECTOR)

            set("mp.messaging.incoming.$EVENTS_IN_CHANNEL.connector", IN_MEMORY_CONNECTOR)
            set("mp.messaging.outgoing.$EVENTS_OUT_CHANNEL.connector", IN_MEMORY_CONNECTOR)

            // CloudEvents channels (consumer for listen tasks, producer for emit tasks)
            set("mp.messaging.incoming.$CLOUDEVENTS_IN_CHANNEL.connector", IN_MEMORY_CONNECTOR)
            set("mp.messaging.outgoing.$CLOUDEVENTS_OUT_CHANNEL.connector", IN_MEMORY_CONNECTOR)

            // Lifecycle events producer channel is always configured for emission.
            // Consumer channel is created only when analytics ingestion is enabled.
            if (props[LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED].toBoolean()) {
                set("mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL.connector", IN_MEMORY_CONNECTOR)
                set("mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL.broadcast", "true")
            }
            set("mp.messaging.outgoing.$LIFECYCLEEVENTS_OUT_CHANNEL.connector", IN_MEMORY_CONNECTOR)
        }

        /**
         * Converts the map into a formatted string representation with each entry sorted
         * and displayed as key-value pairs. Additionally, for each key, system and environment
         * properties are checked and appended if they are available.
         *
         * The resulting string has entries separated by new lines, where each entry is displayed
         * as:
         * - `<key>=<value>` for the map entry.
         * - If applicable, a list of additional properties in the format
         *   `(system=<value>, env=<value>)` is appended.
         *
         * Example format of an individual entry:
         *   `<key>=<value> (system=<system_value>, env=<env_value>)`
         *   or simply `<key>=<value>` if no system or environment properties are found.
         */
        private val SENSITIVE_KEY_PATTERN = Regex("password|secret|sasl", RegexOption.IGNORE_CASE)

        private fun String.maskIfSensitive(key: String): String =
            if (SENSITIVE_KEY_PATTERN.containsMatchIn(key)) "****" else this

        private fun Map<String, String>.toPrint() = toSortedMap().map { (key, value) ->
            "\t$key=${value.maskIfSensitive(key)}" +
                mapOf("system" to System.getProperty(key), "env" to System.getenv(key))
                    .filterValues { it != null }
                    .toList()
                    .let { list ->
                        if (list.isEmpty()) "" else list.joinToString(
                            ", ",
                            " (",
                            ")"
                        ) { "${it.first}=${it.second.maskIfSensitive(key)}" }
                    }
        }.joinToString("\n")
    }

}
