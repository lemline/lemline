// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.logger.logger
import com.lemline.runner.cli.config.ConfigPathHolder
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.LemlineConfigConstants.CLOUDEVENTS_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.COMMANDS_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.CONSUMER_CONCURRENCY_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.EVENTS_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.H2_DB_NAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.H2_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.H2_USERNAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.IN_MEMORY_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_BROKERS_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_CLOUDEVENTS_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_DATABASE_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_OFFSET_RESET_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_STRING_DESERIALIZER
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_STRING_SERIALIZER
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_WORKFLOWS_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.LIFECYCLE_EVENTS_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.METRICS_PATH_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.METRICS_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_DATABASE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_USERNAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_BATCH_SIZE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_MAX_RETRIES_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_POLL_INTERVAL_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_VISIBILITY_TIMEOUT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_DATABASE_DEFAULT
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
import com.lemline.runner.messaging.cloudevents.CLOUDEVENTS_IN_CHANNEL
import com.lemline.runner.messaging.cloudevents.CLOUDEVENTS_OUT_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_OUT_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_OUT_CHANNEL
import io.smallrye.config.PropertiesConfigSource

/**
 * Channel name for lifecycle events output.
 * Lifecycle events are producer-only (no consumer) for external observability.
 */
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
        COMMANDS_CONSUMER_ENABLED,
        COMMANDS_PRODUCER_ENABLED,
        COMMANDS_CONSUMER_CONCURRENCY,
        KAFKA_WORKFLOWS_GROUP_ID_DEFAULT
    ),
    EVENTS(
        "events",
        EVENTS_TOPIC_DEFAULT,
        EVENTS_IN_CHANNEL,
        EVENTS_OUT_CHANNEL,
        EVENTS_CONSUMER_ENABLED,
        EVENTS_PRODUCER_ENABLED,
        EVENTS_CONSUMER_CONCURRENCY,
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
            generatedProps.putAll(generateMessagingProperties(lemlineProps))
            generatedProps.putAll(generateMetricsProperties(lemlineProps))

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

            val type = props[DATABASE_TYPE]?.let { DatabaseType.fromConfigValue(it) } ?: run {
                when {
                    usePostgres && useMysql -> throw IllegalArgumentException(
                        "Both properties 'postgresql' and 'mysql' are defined. " +
                            "Explicitly set '$DATABASE_TYPE' to '${DatabaseType.POSTGRESQL.configValue}' or '${DatabaseType.MYSQL.configValue}'."
                    )

                    usePostgres -> DatabaseType.POSTGRESQL
                    useMysql -> DatabaseType.MYSQL
                    else -> DatabaseType.H2
                }
            }
            generated[DATABASE_TYPE] = type.configValue

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

            val type = props[MESSAGING_TYPE]?.let { MessagingType.fromConfigValue(it) } ?: run {
                when {
                    messagingTypes.size > 1 -> throw IllegalArgumentException(
                        "Multiple messaging types defined: ${messagingTypes.joinToString { it.configValue }}. " +
                            "Explicitly set '$MESSAGING_TYPE' to one of: ${MessagingType.KAFKA.configValue}, ${MessagingType.RABBITMQ.configValue}, ${MessagingType.PGMQ.configValue}."
                    )

                    useKafka -> MessagingType.KAFKA
                    useRabbit -> MessagingType.RABBITMQ
                    usePgmq -> MessagingType.PGMQ
                    else -> MessagingType.IN_MEMORY
                }
            }
            generated[MESSAGING_TYPE] = type.configValue

            when (type) {
                MessagingType.KAFKA -> generated.configureKafka(props)
                MessagingType.RABBITMQ -> generated.configureRabbit(props)
                MessagingType.PGMQ -> generated.configurePgmq(props)
                MessagingType.IN_MEMORY -> generated.configureInMemory()
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
                    "commands.consumer=${props[COMMANDS_CONSUMER_ENABLED]}, " +
                    "commands.producer=${props[COMMANDS_PRODUCER_ENABLED]}, " +
                    "events.consumer=${props[EVENTS_CONSUMER_ENABLED]}, " +
                    "events.producer=${props[EVENTS_PRODUCER_ENABLED]}"
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
            if (props[CLOUDEVENTS_CONSUMER_ENABLED].toBoolean()) {
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
                set(CLOUDEVENTS_CONSUMER_CONCURRENCY, props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT)
            }

            // Producer configuration for emit tasks
            if (props[CLOUDEVENTS_PRODUCER_ENABLED].toBoolean()) {
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
         * Producer-only: Emits workflow and task lifecycle events for external observability.
         */
        private fun MutableMap<String, String>.configureKafkaLifecycleEventsTopic(props: Map<String, String>) {
            val type = "lemline.messaging.kafka.lifecycleevents"
            val topic = props["$type.topic"] ?: LIFECYCLE_EVENTS_TOPIC_DEFAULT

            // Producer configuration for lifecycle events (producer-only, no consumer)
            if (props[LIFECYCLE_EVENTS_PRODUCER_ENABLED].toBoolean()) {
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
            if (props[CLOUDEVENTS_CONSUMER_ENABLED].toBoolean()) {
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
                set(CLOUDEVENTS_CONSUMER_CONCURRENCY, props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT)
            }

            // Producer configuration for emit tasks
            if (props[CLOUDEVENTS_PRODUCER_ENABLED].toBoolean()) {
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
         * Producer-only: Emits workflow and task lifecycle events for external observability.
         */
        private fun MutableMap<String, String>.configureRabbitLifecycleEventsQueue(props: Map<String, String>) {
            val type = "lemline.messaging.rabbitmq.lifecycleevents"
            val queue = props["$type.queue"] ?: LIFECYCLE_EVENTS_TOPIC_DEFAULT

            // Producer configuration for lifecycle events (producer-only, no consumer)
            if (props[LIFECYCLE_EVENTS_PRODUCER_ENABLED].toBoolean()) {
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
            if (props[CLOUDEVENTS_CONSUMER_ENABLED].toBoolean()) {
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
                set(CLOUDEVENTS_CONSUMER_CONCURRENCY, props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT)
            }

            // Producer configuration for emit tasks
            if (props[CLOUDEVENTS_PRODUCER_ENABLED].toBoolean()) {
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
         * Producer-only: Emits workflow and task lifecycle events for external observability.
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

            // Producer configuration for lifecycle events (producer-only, no consumer)
            if (props[LIFECYCLE_EVENTS_PRODUCER_ENABLED].toBoolean()) {
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

        private fun MutableMap<String, String>.configureInMemory() {
            set("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.connector", IN_MEMORY_CONNECTOR)
            set("mp.messaging.outgoing.$COMMANDS_OUT_CHANNEL.connector", IN_MEMORY_CONNECTOR)

            set("mp.messaging.incoming.$EVENTS_IN_CHANNEL.connector", IN_MEMORY_CONNECTOR)
            set("mp.messaging.outgoing.$EVENTS_OUT_CHANNEL.connector", IN_MEMORY_CONNECTOR)

            // CloudEvents channels (consumer for listen tasks, producer for emit tasks)
            set("mp.messaging.incoming.$CLOUDEVENTS_IN_CHANNEL.connector", IN_MEMORY_CONNECTOR)
            set("mp.messaging.outgoing.$CLOUDEVENTS_OUT_CHANNEL.connector", IN_MEMORY_CONNECTOR)

            // Lifecycle events channel (producer-only for external observability)
            set("mp.messaging.outgoing.$LIFECYCLEEVENTS_OUT_CHANNEL.connector", IN_MEMORY_CONNECTOR)
        }

        private fun generateMetricsProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            val prefix = "lemline.metrics"
            val port = props["$prefix.port"] ?: METRICS_PORT_DEFAULT
            val path = props["$prefix.path"] ?: METRICS_PATH_DEFAULT

            // apply quarkus properties
            generated["quarkus.http.port"] = port
            generated["quarkus.http.ssl-port"] = port
            generated["quarkus.micrometer.export.prometheus.path"] = path

            return generated
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
