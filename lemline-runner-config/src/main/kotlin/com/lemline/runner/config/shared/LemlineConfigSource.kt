// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config.shared

import com.lemline.common.logger.logger
import com.lemline.runner.common.config.ANALYTICS_BACKEND_CLICKHOUSE
import com.lemline.runner.common.config.ANALYTICS_BACKEND_DEFAULT
import com.lemline.runner.common.config.ANALYTICS_BACKEND_POSTGRESQL
import com.lemline.runner.common.config.ANALYTICS_TYPE
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_ENABLED
import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_JWT_ISSUER
import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_JWT_JWKS_URL
import com.lemline.runner.common.config.GATEWAY_CORS_ENABLED
import com.lemline.runner.common.config.GATEWAY_CORS_HEADERS
import com.lemline.runner.common.config.GATEWAY_CORS_METHODS
import com.lemline.runner.common.config.GATEWAY_CORS_ORIGINS
import com.lemline.runner.common.config.GATEWAY_ENABLED
import com.lemline.runner.common.config.GATEWAY_GRPC_HOST
import com.lemline.runner.common.config.GATEWAY_GRPC_PORT
import com.lemline.runner.common.config.GATEWAY_TLS_CERTIFICATE
import com.lemline.runner.common.config.GATEWAY_TLS_CLIENT_AUTH
import com.lemline.runner.common.config.GATEWAY_TLS_ENABLED
import com.lemline.runner.common.config.GATEWAY_TLS_PRIVATE_KEY
import com.lemline.runner.common.config.GATEWAY_TLS_TRUST_STORE
import com.lemline.runner.common.config.GATEWAY_TLS_TRUST_STORE_PASSWORD
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.shared.LemlineConfigConstants.ANALYTICS_POSTGRES_BASELINE_ON_MIGRATE_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.ANALYTICS_POSTGRES_DATABASE_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.ANALYTICS_POSTGRES_MIGRATE_AT_START_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.CLOUDEVENTS_TOPIC_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.COMMANDS_TOPIC_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.CONSUMER_CONCURRENCY_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.EVENTS_TOPIC_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_AUTHENTICATION_ENABLED_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_CORS_ENABLED_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_CORS_HEADERS_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_CORS_METHODS_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_CORS_ORIGINS_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_ENABLED_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_GRPC_HOST_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_GRPC_PORT_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_TLS_CLIENT_AUTH_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_TLS_ENABLED_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.H2_DB_NAME_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.H2_PASSWORD_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.H2_USERNAME_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.IN_MEMORY_CONNECTOR
import com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_BROKERS_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_CLOUDEVENTS_GROUP_ID_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_CONNECTOR
import com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_DATABASE_GROUP_ID_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_LIFECYCLE_EVENTS_GROUP_ID_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_OFFSET_RESET_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_STRING_DESERIALIZER
import com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_STRING_SERIALIZER
import com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_WORKFLOWS_GROUP_ID_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.LIFECYCLE_EVENTS_TOPIC_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.MYSQL_DATABASE_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.MYSQL_HOST_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.MYSQL_PASSWORD_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.MYSQL_PORT_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.MYSQL_USERNAME_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_BATCH_SIZE_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_CONNECTOR
import com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_MAX_RETRIES_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_POLL_INTERVAL_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_VISIBILITY_TIMEOUT_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.POSTGRES_DATABASE_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.POSTGRES_HOST_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.POSTGRES_PASSWORD_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.POSTGRES_PORT_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.POSTGRES_USERNAME_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_CONNECTOR
import com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_HOST_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_PASSWORD_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_PORT_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_STRING_SERIALIZER
import com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_USER_DEFAULT
import com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_VHOST_DEFAULT
import io.smallrye.config.PropertiesConfigSource
import java.util.*

internal const val COMMANDS_IN_CHANNEL = "commands-in"
internal const val COMMANDS_OUT_CHANNEL = "commands-out"
internal const val EVENTS_IN_CHANNEL = "events-in"
internal const val EVENTS_OUT_CHANNEL = "events-out"
internal const val CLOUDEVENTS_IN_CHANNEL = "cloudevents-in"
internal const val CLOUDEVENTS_OUT_CHANNEL = "cloudevents-out"
internal const val LIFECYCLEEVENTS_IN_CHANNEL = "lifecycleevents-in"
internal const val LIFECYCLEEVENTS_OUT_CHANNEL = "lifecycleevents-out"

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
        COMMANDS_TOPIC_DEFAULT,
        COMMANDS_IN_CHANNEL,
        COMMANDS_OUT_CHANNEL,
        "lemline.messaging.commands.consumer.enabled",
        "lemline.messaging.commands.producer.enabled",
        "lemline.messaging.commands.consumer.concurrency",
        KAFKA_WORKFLOWS_GROUP_ID_DEFAULT
    ),
    EVENTS(
        "events",
        EVENTS_TOPIC_DEFAULT,
        EVENTS_IN_CHANNEL,
        EVENTS_OUT_CHANNEL,
        "lemline.messaging.events.consumer.enabled",
        "lemline.messaging.events.producer.enabled",
        "lemline.messaging.events.consumer.concurrency",
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

        private enum class GatewayAnalyticsBackend {
            POSTGRESQL,
            CLICKHOUSE,
        }

        private fun buildProperties(): Map<String, String> {
            val lemlineProps = mutableMapOf<String, String>()

            logger.info { "LemlineConfigSource.buildProperties() starting..." }
            logger.info { "ConfigPathHolder.configPath = ${ConfigPathHolder.configPath}" }

            // Load user properties from file
            ConfigPathHolder.configPath?.let { path ->
                ExtraFileConfigFactory().getConfig(path).properties.forEach { (name, value) ->
                    if (name.startsWith("lemline.")) {
                        lemlineProps[name] = value.split("#").first().trim()
                    }
                }
            }

            logger.info { "Lemline file properties:\n${lemlineProps.toPrint()}" }

            // Log lemline-related system properties before reading
            val lemlineSysProps = System.getProperties()
                .filter { (k, _) -> k.toString().startsWith("lemline.") }
                .map { (k, v) -> "$k=$v" }
            logger.info { "Lemline system properties: $lemlineSysProps" }

            // Override with system properties, as they have higher priority,
            // This includes properties defined in [LemlineApplication]
            System.getProperties().forEach { (key, value) ->
                if (key.toString().startsWith("lemline.")) {
                    lemlineProps[key.toString()] = value.toString()
                }
            }

            logger.info { "Lemline merged properties:\n${lemlineProps.toPrint()}" }

            // Generate and merge transformed properties
            val generatedProps = mutableMapOf<String, String>()
            generatedProps.putAll(generateDatabaseProperties(lemlineProps))
            generatedProps.putAll(generateAnalyticsDatabaseProperties(lemlineProps))
            generatedProps.putAll(generateMessagingProperties(lemlineProps))
            generatedProps.putAll(generateGatewayProperties(lemlineProps))

            logger.info { "Lemline generated properties:\n${generatedProps.toPrint()}" }

            return mutableMapOf<String, String>().apply {
                putAll(lemlineProps)
                putAll(generatedProps)
            }
        }

        private fun generateDatabaseProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()

            val usePostgres = props.keys.any { it.startsWith("lemline.database.postgresql.") }
            val useMysql = props.keys.any { it.startsWith("lemline.database.mysql.") }

            val type = props["lemline.database.type"]?.let { DatabaseType.fromConfigValue(it) } ?: run {
                when {
                    usePostgres && useMysql -> throw IllegalArgumentException(
                        "Both properties 'postgresql' and 'mysql' are defined. " +
                            "Explicitly set 'lemline.database.type' to '${DatabaseType.POSTGRESQL.configValue}' or '${DatabaseType.MYSQL.configValue}'."
                    )

                    usePostgres -> DatabaseType.POSTGRESQL
                    useMysql -> DatabaseType.MYSQL
                    else -> DatabaseType.H2
                }
            }
            generated["lemline.database.type"] = type.configValue

            when (type) {
                DatabaseType.POSTGRESQL -> {
                    val db = "lemline.database.postgresql"
                    val host = props["$db.host"] ?: POSTGRES_HOST_DEFAULT
                    val port = props["$db.port"] ?: POSTGRES_PORT_DEFAULT
                    val database = props["$db.database"] ?: POSTGRES_DATABASE_DEFAULT
                    val postgres = "quarkus.datasource.postgresql"
                    generated["$postgres.username"] = props["$db.username"] ?: POSTGRES_USERNAME_DEFAULT
                    generated["$postgres.password"] = props["$db.password"] ?: POSTGRES_PASSWORD_DEFAULT
                    generated["$postgres.jdbc.url"] = "jdbc:postgresql://$host:$port/$database"
                }

                DatabaseType.MYSQL -> {
                    val db = "lemline.database.mysql"
                    val host = props["$db.host"] ?: MYSQL_HOST_DEFAULT
                    val port = props["$db.port"] ?: MYSQL_PORT_DEFAULT
                    val database = props["$db.database"] ?: MYSQL_DATABASE_DEFAULT
                    val mysql = "quarkus.datasource.mysql"
                    generated["$mysql.username"] = props["$db.username"] ?: MYSQL_USERNAME_DEFAULT
                    generated["$mysql.password"] = props["$db.password"] ?: MYSQL_PASSWORD_DEFAULT
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
            val lifecycleConsumerEnabled = props["lemline.analytics.consumer.enabled"].toBoolean()
            val gatewayEnabled = props[GATEWAY_ENABLED]?.toBooleanStrictOrNull()
                ?: GATEWAY_ENABLED_DEFAULT.toBoolean()
            val gatewayAnalyticsBackend = resolveGatewayAnalyticsBackend(props, gatewayEnabled)

            val needsAnalyticsDatasource = lifecycleConsumerEnabled ||
                (gatewayEnabled && gatewayAnalyticsBackend == GatewayAnalyticsBackend.POSTGRESQL)

            if (!needsAnalyticsDatasource) return emptyMap()

            val generated = mutableMapOf<String, String>()
            val analytics = "lemline.analytics"
            val analyticsPostgresql = "$analytics.postgresql"

            val host = props["$analyticsPostgresql.host"] ?: POSTGRES_HOST_DEFAULT
            val port = props["$analyticsPostgresql.port"] ?: POSTGRES_PORT_DEFAULT
            val database = props["$analyticsPostgresql.database"] ?: ANALYTICS_POSTGRES_DATABASE_DEFAULT
            val username = props["$analyticsPostgresql.username"] ?: POSTGRES_USERNAME_DEFAULT
            val password = props["$analyticsPostgresql.password"] ?: POSTGRES_PASSWORD_DEFAULT
            // Backward-compatible fallback for previous key location under analytics.postgresql.*
            val migrateAtStart = props["$analytics.migrate-at-start"]
                ?: props["$analyticsPostgresql.migrate-at-start"]
                ?: ANALYTICS_POSTGRES_MIGRATE_AT_START_DEFAULT
            val baselineOnMigrate = props["$analytics.baseline-on-migrate"]
                ?: props["$analyticsPostgresql.baseline-on-migrate"]
                ?: ANALYTICS_POSTGRES_BASELINE_ON_MIGRATE_DEFAULT

            generated["quarkus.datasource.analytics.db-kind"] = "postgresql"
            generated["quarkus.datasource.analytics.username"] = username
            generated["quarkus.datasource.analytics.password"] = password
            generated["quarkus.datasource.analytics.jdbc.url"] = "jdbc:postgresql://$host:$port/$database"
            generated["quarkus.flyway.analytics.migrate-at-start"] = migrateAtStart
            generated["quarkus.flyway.analytics.baseline-on-migrate"] = baselineOnMigrate
            generated["quarkus.flyway.analytics.locations"] = "classpath:db/migration/analytics/postgresql"

            return generated
        }

        private fun resolveGatewayAnalyticsBackend(
            props: Map<String, String>,
            gatewayEnabled: Boolean
        ): GatewayAnalyticsBackend? {
            if (!gatewayEnabled) return null

            val rawType = props[ANALYTICS_TYPE] ?: ANALYTICS_BACKEND_DEFAULT
            return when (rawType.trim().lowercase(Locale.ROOT)) {
                ANALYTICS_BACKEND_POSTGRESQL -> GatewayAnalyticsBackend.POSTGRESQL
                ANALYTICS_BACKEND_CLICKHOUSE -> GatewayAnalyticsBackend.CLICKHOUSE
                else -> throw IllegalStateException(
                    "Unsupported analytics type '$rawType'. Supported values: " +
                        "'$ANALYTICS_BACKEND_POSTGRESQL', '$ANALYTICS_BACKEND_CLICKHOUSE'."
                )
            }
        }

        private fun generateGatewayProperties(props: Map<String, String>): Map<String, String> {
            val enabled = props[GATEWAY_ENABLED]?.toBooleanStrictOrNull()
                ?: GATEWAY_ENABLED_DEFAULT.toBoolean()
            val tlsEnabled = props[GATEWAY_TLS_ENABLED]?.toBooleanStrictOrNull()
                ?: GATEWAY_TLS_ENABLED_DEFAULT.toBoolean()
            val authenticationEnabled =
                props[GATEWAY_AUTHENTICATION_ENABLED]?.toBooleanStrictOrNull()
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

            generated["quarkus.grpc.server.host"] =
                props[GATEWAY_GRPC_HOST] ?: GATEWAY_GRPC_HOST_DEFAULT
            generated["quarkus.grpc.server.port"] =
                props[GATEWAY_GRPC_PORT] ?: GATEWAY_GRPC_PORT_DEFAULT
            generated["quarkus.grpc.server.plain-text"] = (!tlsEnabled).toString()
            generated["quarkus.grpc.server.enable-grpc-web"] = "true"

            val corsEnabled = props[GATEWAY_CORS_ENABLED]?.toBooleanStrictOrNull()
                ?: GATEWAY_CORS_ENABLED_DEFAULT.toBoolean()
            generated["quarkus.http.cors"] = corsEnabled.toString()
            if (corsEnabled) {
                generated["quarkus.http.cors.origins"] =
                    props[GATEWAY_CORS_ORIGINS] ?: GATEWAY_CORS_ORIGINS_DEFAULT
                generated["quarkus.http.cors.methods"] =
                    props[GATEWAY_CORS_METHODS] ?: GATEWAY_CORS_METHODS_DEFAULT
                generated["quarkus.http.cors.headers"] =
                    props[GATEWAY_CORS_HEADERS] ?: GATEWAY_CORS_HEADERS_DEFAULT
                generated["quarkus.http.cors.access-control-allow-credentials"] = "false"
            }

            if (tlsEnabled) {
                generated["quarkus.grpc.server.ssl.client-auth"] =
                    props[GATEWAY_TLS_CLIENT_AUTH]
                        ?: GATEWAY_TLS_CLIENT_AUTH_DEFAULT

                props[GATEWAY_TLS_CERTIFICATE]?.let {
                    generated["quarkus.grpc.server.ssl.certificate"] = it
                }
                props[GATEWAY_TLS_PRIVATE_KEY]?.let {
                    generated["quarkus.grpc.server.ssl.key"] = it
                }
                props[GATEWAY_TLS_TRUST_STORE]?.let {
                    generated["quarkus.grpc.server.ssl.trust-store"] = it
                }
                props[GATEWAY_TLS_TRUST_STORE_PASSWORD]?.let {
                    generated["quarkus.grpc.server.ssl.trust-store-password"] = it
                }
            } else {
                generated["quarkus.grpc.server.ssl.client-auth"] = "none"
            }

            return generated
        }

        private fun generateGatewayJwtProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            props[GATEWAY_AUTHENTICATION_JWT_ISSUER]?.let {
                generated["mp.jwt.verify.issuer"] = it
            }
            props[GATEWAY_AUTHENTICATION_JWT_JWKS_URL]?.let {
                generated["smallrye.jwt.verify.key.location"] = it
            }
            return generated
        }

        private fun generateMessagingProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            val useKafka = props.keys.any { it.startsWith("lemline.messaging.kafka.") }
            val useRabbit = props.keys.any { it.startsWith("lemline.messaging.rabbitmq.") }
            val usePgmq = props.keys.any { it.startsWith("lemline.messaging.pgmq.") }

            val messagingTypes = listOfNotNull(
                if (useKafka) MessagingType.KAFKA else null,
                if (useRabbit) MessagingType.RABBITMQ else null,
                if (usePgmq) MessagingType.PGMQ else null
            )

            val type = props["lemline.messaging.type"]?.let { MessagingType.fromConfigValue(it) } ?: run {
                when {
                    messagingTypes.size > 1 -> throw IllegalArgumentException(
                        "Multiple messaging types defined: ${messagingTypes.joinToString { it.configValue }}. " +
                            "Explicitly set 'lemline.messaging.type' to one of: ${MessagingType.KAFKA.configValue}, ${MessagingType.RABBITMQ.configValue}, ${MessagingType.PGMQ.configValue}."
                    )

                    useKafka -> MessagingType.KAFKA
                    useRabbit -> MessagingType.RABBITMQ
                    usePgmq -> MessagingType.PGMQ
                    else -> MessagingType.IN_MEMORY
                }
            }
            generated["lemline.messaging.type"] = type.configValue

            when (type) {
                MessagingType.KAFKA -> generated.configureKafka(props)
                MessagingType.RABBITMQ -> generated.configureRabbit(props)
                MessagingType.PGMQ -> generated.configurePgmq(props)
                MessagingType.IN_MEMORY -> generated.configureInMemory(props)
            }

            return generated
        }


        private fun MutableMap<String, String>.configureKafka(props: Map<String, String>) {
            val kafka = "lemline.messaging.kafka"
            // With Default values
            set("kafka.bootstrap.servers", props["$kafka.brokers"] ?: KAFKA_BROKERS_DEFAULT)
            // Optional values
            props["$kafka.security-protocol"]?.let { set("kafka.security.protocol", it) }
            props["$kafka.sasl-mechanism"]?.let { set("kafka.sasl.mechanism", it) }

            if (props.containsKey("$kafka.sasl-username") && props.containsKey("$kafka.sasl-password")) {
                set(
                    "kafka.sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                        "username=\"${props["$kafka.sasl-username"]}\" " +
                        "password=\"${props["$kafka.sasl-password"]}\";"
                )
                if (!containsKey("kafka.sasl.mechanism")) set("kafka.sasl.mechanism", "PLAIN")
            }

            // Log the enabled flags for debugging
            logger.info {
                "Kafka channel enabled flags: " +
                    "commands.consumer=${props["lemline.messaging.commands.consumer.enabled"]}, " +
                    "commands.producer=${props["lemline.messaging.commands.producer.enabled"]}, " +
                    "events.consumer=${props["lemline.messaging.events.consumer.enabled"]}, " +
                    "events.producer=${props["lemline.messaging.events.producer.enabled"]}"
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
            val type = "lemline.messaging.kafka.${topicType.type}"
            val topic = props["$type.topic"] ?: topicType.defaultTopicName

            if (props[topicType.consumerEnabled].toBoolean()) {
                val consumer = "$type.consumer"
                val incoming = "mp.messaging.incoming.${topicType.incomingChannel}"
                val topicDLQ = props["$consumer.topic-dlq"] ?: "$topic.dlq"
                set("$incoming.connector", KAFKA_CONNECTOR)
                set("$incoming.topic", topic)
                set("$incoming.group.id", props["$consumer.group-id"] ?: topicType.consumerGroupDefault)
                set("$incoming.auto.offset.reset", props["$consumer.offset-reset"] ?: KAFKA_OFFSET_RESET_DEFAULT)
                set("$incoming.value.deserializer", KAFKA_STRING_DESERIALIZER)
                set("$incoming.failure-strategy", "dead-letter-queue")
                set("$incoming.dead-letter-queue.topic", topicDLQ)
                // set the consumer concurrency
                set(topicType.consumerConcurrency, props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT)
            }

            if (props[topicType.producerEnabled].toBoolean()) {
                val producer = "$type.producer"
                val outgoing = "mp.messaging.outgoing.${topicType.outgoingChannel}"
                set("$outgoing.connector", KAFKA_CONNECTOR)
                set("$outgoing.topic", props["$producer.topic-out"] ?: topic)
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
            val type = "lemline.messaging.kafka.cloudevents"
            val topic = props["$type.topic"] ?: CLOUDEVENTS_TOPIC_DEFAULT

            // Consumer configuration for listen tasks
            if (props["lemline.messaging.cloudevents.consumer.enabled"].toBoolean()) {
                val consumer = "$type.consumer"
                val incoming = "mp.messaging.incoming.$CLOUDEVENTS_IN_CHANNEL"
                val topicDLQ = props["$consumer.topic-dlq"] ?: "$topic.dlq"
                set("$incoming.connector", KAFKA_CONNECTOR)
                set("$incoming.topic", topic)
                set("$incoming.group.id", props["$consumer.group-id"] ?: KAFKA_CLOUDEVENTS_GROUP_ID_DEFAULT)
                set("$incoming.auto.offset.reset", props["$consumer.offset-reset"] ?: KAFKA_OFFSET_RESET_DEFAULT)
                set("$incoming.value.deserializer", KAFKA_STRING_DESERIALIZER)
                set("$incoming.failure-strategy", "dead-letter-queue")
                set("$incoming.dead-letter-queue.topic", topicDLQ)
                set(
                    "lemline.messaging.cloudevents.consumer.concurrency",
                    props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            // Producer configuration for emit tasks
            if (props["lemline.messaging.cloudevents.producer.enabled"].toBoolean()) {
                val producer = "$type.producer"
                val outgoing = "mp.messaging.outgoing.$CLOUDEVENTS_OUT_CHANNEL"
                set("$outgoing.connector", KAFKA_CONNECTOR)
                set("$outgoing.topic", props["$producer.topic-out"] ?: topic)
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
            val type = "lemline.messaging.kafka.lifecycleevents"
            val topic = props["$type.topic"] ?: LIFECYCLE_EVENTS_TOPIC_DEFAULT

            if (props["lemline.analytics.consumer.enabled"].toBoolean()) {
                val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
                val topicDLQ = "$topic.dlq"
                set("$incoming.connector", KAFKA_CONNECTOR)
                set("$incoming.topic", topic)
                set("$incoming.broadcast", "true")
                set("$incoming.group.id", KAFKA_LIFECYCLE_EVENTS_GROUP_ID_DEFAULT)
                set("$incoming.auto.offset.reset", KAFKA_OFFSET_RESET_DEFAULT)
                set("$incoming.value.deserializer", KAFKA_STRING_DESERIALIZER)
                set("$incoming.failure-strategy", "dead-letter-queue")
                set("$incoming.dead-letter-queue.topic", topicDLQ)
                set(
                    "lemline.analytics.consumer.concurrency",
                    props["lemline.analytics.consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            if (props["lemline.messaging.lifecycleevents.producer.enabled"].toBoolean()) {
                val producer = "$type.producer"
                val outgoing = "mp.messaging.outgoing.$LIFECYCLEEVENTS_OUT_CHANNEL"
                set("$outgoing.connector", KAFKA_CONNECTOR)
                set("$outgoing.topic", props["$producer.topic-out"] ?: topic)
                set("$outgoing.value.serializer", KAFKA_STRING_SERIALIZER)
                set("$outgoing.acks", "all")
            }
        }

        private fun MutableMap<String, String>.configureRabbit(props: Map<String, String>) {
            val rabbit = "lemline.messaging.rabbitmq"
            // Values with Default
            set("rabbitmq-host", props["$rabbit.hostname"] ?: RABBITMQ_HOST_DEFAULT)
            set("rabbitmq-port", props["$rabbit.port"] ?: RABBITMQ_PORT_DEFAULT)
            set("rabbitmq-username", props["$rabbit.username"] ?: RABBITMQ_USER_DEFAULT)
            set("rabbitmq-password", props["$rabbit.password"] ?: RABBITMQ_PASSWORD_DEFAULT)
            set("rabbitmq-virtual-host", props["$rabbit.virtual-host"] ?: RABBITMQ_VHOST_DEFAULT)
            // Optional values
            props["$rabbit.ssl-enabled"]?.let { set("rabbitmq-ssl", it) }

            configureRabbitQueue(props, TopicType.COMMANDS)
            configureRabbitQueue(props, TopicType.EVENTS)
            configureRabbitCloudEventsQueue(props)
            configureRabbitLifecycleEventsQueue(props)
        }

        private fun MutableMap<String, String>.configureRabbitQueue(
            props: Map<String, String>,
            topicType: TopicType
        ) {
            val type = "lemline.messaging.rabbitmq.${topicType.type}"
            val queue = props["$type.queue"] ?: topicType.defaultTopicName

            if (props[topicType.consumerEnabled].toBoolean()) {
                val consumer = "$type.consumer"
                val incoming = "mp.messaging.incoming.${topicType.incomingChannel}"
                val queueDLQ = props["$consumer.queue-dlq"] ?: "$queue.dlq"
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
                set(topicType.consumerConcurrency, props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT)
            }

            if (props[topicType.producerEnabled].toBoolean()) {
                val producer = "$type.producer"
                val outgoing = "mp.messaging.outgoing.${topicType.outgoingChannel}"
                set("$outgoing.connector", RABBITMQ_CONNECTOR)
                set("$outgoing.queue.name", props["$producer.queue-out"] ?: queue)
                set("$outgoing.serializer", RABBITMQ_STRING_SERIALIZER)
                set("$outgoing.delivery-mode", "persistent")
                props["$producer.exchange-name"]?.let { set("$outgoing.exchange.name", it) }
            }
        }

        /**
         * Configures the CloudEvents RabbitMQ queue.
         * - Consumer: Receives CloudEvents from external sources for listen tasks
         * - Producer: Emits CloudEvents from emit tasks
         */
        private fun MutableMap<String, String>.configureRabbitCloudEventsQueue(props: Map<String, String>) {
            val type = "lemline.messaging.rabbitmq.cloudevents"
            val queue = props["$type.queue"] ?: CLOUDEVENTS_TOPIC_DEFAULT

            // Consumer configuration for listen tasks
            if (props["lemline.messaging.cloudevents.consumer.enabled"].toBoolean()) {
                val consumer = "$type.consumer"
                val incoming = "mp.messaging.incoming.$CLOUDEVENTS_IN_CHANNEL"
                val queueDLQ = props["$consumer.queue-dlq"] ?: "$queue.dlq"
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
                    "lemline.messaging.cloudevents.consumer.concurrency",
                    props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            // Producer configuration for emit tasks
            if (props["lemline.messaging.cloudevents.producer.enabled"].toBoolean()) {
                val producer = "$type.producer"
                val outgoing = "mp.messaging.outgoing.$CLOUDEVENTS_OUT_CHANNEL"
                set("$outgoing.connector", RABBITMQ_CONNECTOR)
                set("$outgoing.queue.name", props["$producer.queue-out"] ?: queue)
                set("$outgoing.serializer", RABBITMQ_STRING_SERIALIZER)
                set("$outgoing.delivery-mode", "persistent")
                props["$producer.exchange-name"]?.let { set("$outgoing.exchange.name", it) }
            }
        }

        /**
         * Configures the Lifecycle Events RabbitMQ queue.
         * - Producer: Emits workflow and task lifecycle events
         * - Consumer: Analytics ingestion from the same lifecycle events destination
         */
        private fun MutableMap<String, String>.configureRabbitLifecycleEventsQueue(props: Map<String, String>) {
            val type = "lemline.messaging.rabbitmq.lifecycleevents"
            val queue = props["$type.queue"] ?: LIFECYCLE_EVENTS_TOPIC_DEFAULT

            if (props["lemline.analytics.consumer.enabled"].toBoolean()) {
                val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
                val queueDLQ = "$queue.dlq"
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
                props["$type.producer.exchange-name"]?.let { exchange ->
                    set("$incoming.exchange.name", exchange)
                }
                set(
                    "lemline.analytics.consumer.concurrency",
                    props["lemline.analytics.consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            if (props["lemline.messaging.lifecycleevents.producer.enabled"].toBoolean()) {
                val producer = "$type.producer"
                val outgoing = "mp.messaging.outgoing.$LIFECYCLEEVENTS_OUT_CHANNEL"
                set("$outgoing.connector", RABBITMQ_CONNECTOR)
                set("$outgoing.queue.name", props["$producer.queue-out"] ?: queue)
                set("$outgoing.serializer", RABBITMQ_STRING_SERIALIZER)
                set("$outgoing.delivery-mode", "persistent")
                props["$producer.exchange-name"]?.let { set("$outgoing.exchange.name", it) }
            }
        }

        /**
         * Configures PGMQ (PostgreSQL Message Queue) channels.
         * Uses PostgreSQL as a message broker via the PGMQ extension.
         */
        private fun MutableMap<String, String>.configurePgmq(props: Map<String, String>) {
            val pgmq = "lemline.messaging.pgmq"
            // PostgreSQL connection settings (reuses database config or can be overridden)
            val host = props["$pgmq.host"] ?: POSTGRES_HOST_DEFAULT
            val port = props["$pgmq.port"] ?: POSTGRES_PORT_DEFAULT
            val database = props["$pgmq.database"] ?: POSTGRES_DATABASE_DEFAULT
            val username = props["$pgmq.username"] ?: POSTGRES_USERNAME_DEFAULT
            val password = props["$pgmq.password"] ?: POSTGRES_PASSWORD_DEFAULT

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
            val type = "lemline.messaging.pgmq.${topicType.type}"
            val queue = props["$type.queue"] ?: topicType.defaultTopicName

            if (props[topicType.consumerEnabled].toBoolean()) {
                val consumer = "$type.consumer"
                val incoming = "mp.messaging.incoming.${topicType.incomingChannel}"
                val queueDLQ = props["$consumer.queue-dlq"] ?: "$queue.dlq"

                set("$incoming.connector", PGMQ_CONNECTOR)
                set("$incoming.queue", queue)
                set("$incoming.host", host)
                set("$incoming.port", port)
                set("$incoming.database", database)
                set("$incoming.username", username)
                set("$incoming.password", password)
                set(
                    "$incoming.visibility-timeout",
                    props["$consumer.visibility-timeout"] ?: PGMQ_VISIBILITY_TIMEOUT_DEFAULT
                )
                set("$incoming.poll-interval", props["$consumer.poll-interval"] ?: PGMQ_POLL_INTERVAL_DEFAULT)
                set("$incoming.batch-size", props["$consumer.batch-size"] ?: PGMQ_BATCH_SIZE_DEFAULT)
                set("$incoming.max-retries", props["$consumer.max-retries"] ?: PGMQ_MAX_RETRIES_DEFAULT)
                set("$incoming.dead-letter-queue", queueDLQ)
                set("$incoming.auto-create-queue", "false")
                // Set consumer concurrency
                set(topicType.consumerConcurrency, props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT)
            }

            if (props[topicType.producerEnabled].toBoolean()) {
                val producer = "$type.producer"
                val outgoing = "mp.messaging.outgoing.${topicType.outgoingChannel}"

                set("$outgoing.connector", PGMQ_CONNECTOR)
                set("$outgoing.queue", props["$producer.queue-out"] ?: queue)
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
            val type = "lemline.messaging.pgmq.cloudevents"
            val queue = props["$type.queue"] ?: CLOUDEVENTS_TOPIC_DEFAULT

            // Consumer configuration for listen tasks
            if (props["lemline.messaging.cloudevents.consumer.enabled"].toBoolean()) {
                val consumer = "$type.consumer"
                val incoming = "mp.messaging.incoming.$CLOUDEVENTS_IN_CHANNEL"
                val queueDLQ = props["$consumer.queue-dlq"] ?: "$queue.dlq"

                set("$incoming.connector", PGMQ_CONNECTOR)
                set("$incoming.queue", queue)
                set("$incoming.host", host)
                set("$incoming.port", port)
                set("$incoming.database", database)
                set("$incoming.username", username)
                set("$incoming.password", password)
                set(
                    "$incoming.visibility-timeout",
                    props["$consumer.visibility-timeout"] ?: PGMQ_VISIBILITY_TIMEOUT_DEFAULT
                )
                set("$incoming.poll-interval", props["$consumer.poll-interval"] ?: PGMQ_POLL_INTERVAL_DEFAULT)
                set("$incoming.batch-size", props["$consumer.batch-size"] ?: PGMQ_BATCH_SIZE_DEFAULT)
                set("$incoming.max-retries", props["$consumer.max-retries"] ?: PGMQ_MAX_RETRIES_DEFAULT)
                set("$incoming.dead-letter-queue", queueDLQ)
                set("$incoming.auto-create-queue", "false")
                set(
                    "lemline.messaging.cloudevents.consumer.concurrency",
                    props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            // Producer configuration for emit tasks
            if (props["lemline.messaging.cloudevents.producer.enabled"].toBoolean()) {
                val producer = "$type.producer"
                val outgoing = "mp.messaging.outgoing.$CLOUDEVENTS_OUT_CHANNEL"

                set("$outgoing.connector", PGMQ_CONNECTOR)
                set("$outgoing.queue", props["$producer.queue-out"] ?: queue)
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
            val type = "lemline.messaging.pgmq.lifecycleevents"
            val queue = props["$type.queue"] ?: LIFECYCLE_EVENTS_TOPIC_DEFAULT

            if (props["lemline.analytics.consumer.enabled"].toBoolean()) {
                val consumer = "$type.consumer"
                val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
                val queueDLQ = props["$consumer.queue-dlq"] ?: "$queue.dlq"

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
                    props["$consumer.visibility-timeout"] ?: PGMQ_VISIBILITY_TIMEOUT_DEFAULT
                )
                set("$incoming.poll-interval", props["$consumer.poll-interval"] ?: PGMQ_POLL_INTERVAL_DEFAULT)
                set("$incoming.batch-size", props["$consumer.batch-size"] ?: PGMQ_BATCH_SIZE_DEFAULT)
                set("$incoming.max-retries", props["$consumer.max-retries"] ?: PGMQ_MAX_RETRIES_DEFAULT)
                set("$incoming.dead-letter-queue", queueDLQ)
                set("$incoming.auto-create-queue", "false")
                set(
                    "lemline.analytics.consumer.concurrency",
                    props["lemline.analytics.consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT
                )
            }

            if (props["lemline.messaging.lifecycleevents.producer.enabled"].toBoolean()) {
                val producer = "$type.producer"
                val outgoing = "mp.messaging.outgoing.$LIFECYCLEEVENTS_OUT_CHANNEL"

                set("$outgoing.connector", PGMQ_CONNECTOR)
                set("$outgoing.queue", props["$producer.queue-out"] ?: queue)
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
            if (props["lemline.analytics.consumer.enabled"].toBoolean()) {
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
        private fun Map<String, String>.toPrint() = toSortedMap().map {
            "\t${it.key}=${it.value}" +
                mapOf("system" to System.getProperty(it.key), "env" to System.getenv(it.key))
                    .filter { it.value != null }
                    .toList()
                    .let { list ->
                        if (list.isEmpty()) "" else list.joinToString(", ", " (", ")") { "${it.first}=${it.second}" }
                    }
        }.joinToString("\n")
    }

}
