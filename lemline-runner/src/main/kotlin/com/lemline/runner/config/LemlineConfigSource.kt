// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.runner.LemlineApplication
import com.lemline.runner.config.LemlineConfigConstants.CONSUMER_CONCURRENCY_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import com.lemline.runner.config.LemlineConfigConstants.H2_DB_NAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.H2_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.H2_USERNAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.INGESTION_TOPIC_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.IN_MEMORY_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_BROKERS_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_OFFSET_RESET_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_STRING_DESERIALIZER
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_STRING_SERIALIZER
import com.lemline.runner.config.LemlineConfigConstants.METRICS_PATH_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.METRICS_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_KAFKA
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_RABBITMQ
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
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_STRING_SERIALIZER
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_USER_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_VHOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.WORKFLOWS_TOPIC_DEFAULT
import com.lemline.runner.ingestion.INGESTION_IN_CHANNEL
import com.lemline.runner.ingestion.INGESTION_OUT_CHANNEL
import com.lemline.runner.instances.WORKFLOWS_IN_CHANNEL
import com.lemline.runner.instances.WORKFLOWS_OUT_CHANNEL
import io.smallrye.config.PropertiesConfigSource

enum class TopicType(
    val config: String,
    val defaultTopicName: String,
    val incomingChannel: String,
    val outgoingChannel: String,
    val consumerEnabled: String,
    val producerEnabled: String,
    val consumerConcurrency: String
) {
    WORKFLOWS(
        "workflows",
        WORKFLOWS_TOPIC_DEFAULT,
        WORKFLOWS_IN_CHANNEL,
        WORKFLOWS_OUT_CHANNEL,
        WORKFLOWS_CONSUMER_ENABLED,
        WORKFLOWS_PRODUCER_ENABLED,
        WORKFLOWS_CONSUMER_CONCURRENCY
    ),
    INGESTION(
        "ingestion",
        INGESTION_TOPIC_DEFAULT,
        INGESTION_IN_CHANNEL,
        INGESTION_OUT_CHANNEL,
        INGESTION_CONSUMER_ENABLED,
        INGESTION_PRODUCER_ENABLED,
        INGESTION_CONSUMER_CONCURRENCY
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

            // Load user properties from file
            LemlineApplication.configPath?.let { path ->
                ExtraFileConfigFactory().getConfig(path).properties.forEach { (name, value) ->
                    if (name.startsWith("lemline.")) {
                        lemlineProps[name] = value.split("#").first().trim()
                    }
                }
            }

            logger.info { "Lemline user properties:\n${lemlineProps.toPrint()}" }

            // Override with system properties, as they have higher priority,
            // This includes properties defined in [LemlineApplication]
            System.getProperties().forEach { (key, value) ->
                if (key.toString().startsWith("lemline.")) {
                    lemlineProps[key.toString()] = value.toString()
                }
            }

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

            val type = props[DATABASE_TYPE] ?: run {
                when {
                    usePostgres && useMysql -> throw IllegalArgumentException("Both properties 'postgresql' and 'mysql' are defined. Explicitly set '$DATABASE_TYPE' to '$DB_TYPE_POSTGRESQL' or '$DB_TYPE_MYSQL'.")
                    usePostgres -> DB_TYPE_POSTGRESQL
                    useMysql -> DB_TYPE_MYSQL
                    else -> DB_TYPE_IN_MEMORY
                }
            }
            generated[DATABASE_TYPE] = type

            when (type) {
                DB_TYPE_POSTGRESQL -> {
                    val db = "lemline.database.postgresql"
                    val host = props["$db.host"] ?: POSTGRES_HOST_DEFAULT
                    val port = props["$db.port"] ?: POSTGRES_PORT_DEFAULT
                    val name = props["$db.name"] ?: POSTGRES_NAME_DEFAULT
                    val postgres = "quarkus.datasource.postgresql"
                    generated["$postgres.username"] = props["$db.username"] ?: POSTGRES_USERNAME_DEFAULT
                    generated["$postgres.password"] = props["$db.password"] ?: POSTGRES_PASSWORD_DEFAULT
                    generated["$postgres.jdbc.url"] = "jdbc:postgresql://$host:$port/$name"
                }

                DB_TYPE_MYSQL -> {
                    val db = "lemline.database.mysql"
                    val host = props["$db.host"] ?: MYSQL_HOST_DEFAULT
                    val port = props["$db.port"] ?: MYSQL_PORT_DEFAULT
                    val name = props["$db.name"] ?: MYSQL_NAME_DEFAULT
                    val mysql = "quarkus.datasource.mysql"
                    generated["$mysql.username"] = props["$db.username"] ?: MYSQL_USERNAME_DEFAULT
                    generated["$mysql.password"] = props["$db.password"] ?: MYSQL_PASSWORD_DEFAULT
                    generated["$mysql.jdbc.url"] = "jdbc:mysql://$host:$port/$name" +
                        "?useSSL=false" +
                        "&allowPublicKeyRetrieval=true" +
                        "&sessionVariables=sql_mode='STRICT_ALL_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ZERO_DATE,NO_ZERO_IN_DATE,NO_ENGINE_SUBSTITUTION'" +
                        "&continueBatchOnError=false"
                }

                DB_TYPE_IN_MEMORY -> {
                    val h2 = "quarkus.datasource" // <- default datasource
                    generated["$h2.username"] = H2_USERNAME_DEFAULT
                    generated["$h2.password"] = H2_PASSWORD_DEFAULT
                    generated["$h2.jdbc.url"] = "jdbc:h2:mem:$H2_DB_NAME_DEFAULT;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
                }
            }

            return generated
        }

        private fun generateMessagingProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            val useKafka = props.keys.any { it.startsWith("lemline.messaging.kafka.") }
            val useRabbit = props.keys.any { it.startsWith("lemline.messaging.rabbitmq.") }

            val type = props[MESSAGING_TYPE] ?: run {
                when {
                    useKafka && useRabbit -> throw IllegalArgumentException("Both properties 'kafka' and 'rabbitmq' are defined. Explicitly set '$MESSAGING_TYPE' to '$MSG_TYPE_KAFKA' or '$MSG_TYPE_RABBITMQ'.")
                    useKafka -> MSG_TYPE_KAFKA
                    useRabbit -> MSG_TYPE_RABBITMQ
                    else -> MSG_TYPE_IN_MEMORY
                }
            }
            generated[MESSAGING_TYPE] = type

            when (type) {
                MSG_TYPE_KAFKA -> generated.configureKafka(props)
                MSG_TYPE_RABBITMQ -> generated.configureRabbit(props)
                MSG_TYPE_IN_MEMORY -> generated.configureInMemory()
                else -> error("Unknown messaging type: $type")
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

            configureKafkaTopic(props, TopicType.WORKFLOWS)
            configureKafkaTopic(props, TopicType.INGESTION)
        }

        private fun MutableMap<String, String>.configureKafkaTopic(
            props: Map<String, String>,
            type: TopicType
        ) {
            val config = "lemline.messaging.kafka.${type.config}"
            val topic = props["$config.topic"] ?: type.defaultTopicName

            if (props[type.consumerEnabled].toBoolean()) {
                val consumer = "$config.consumer"
                val incoming = "mp.messaging.incoming.${type.incomingChannel}"
                set("$incoming.connector", KAFKA_CONNECTOR)
                set("$incoming.topic", topic)
                set("$incoming.group.id", props["$consumer.group-id"] ?: KAFKA_GROUP_ID_DEFAULT)
                set("$incoming.auto.offset.reset", props["$consumer.offset-reset"] ?: KAFKA_OFFSET_RESET_DEFAULT)
                set("$incoming.failure-strategy", "dead-letter-queue")
                set("$incoming.dead-letter-queue.topic", props["$consumer.topic-dlq"] ?: "$topic-dlq")
                set("$incoming.value.deserializer", KAFKA_STRING_DESERIALIZER)
                // set the consumer concurrency
                set(type.consumerConcurrency, props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT)
            }

            if (props[type.producerEnabled].toBoolean()) {
                val producer = "$config.producer"
                val outgoing = "mp.messaging.outgoing.${type.outgoingChannel}"
                set("$outgoing.connector", KAFKA_CONNECTOR)
                set("$outgoing.topic", props["$producer.topic-out"] ?: topic)
                set("$outgoing.value.serializer", KAFKA_STRING_SERIALIZER)
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

            configureRabbitQueue(props, TopicType.WORKFLOWS)
            configureRabbitQueue(props, TopicType.INGESTION)
        }

        private fun MutableMap<String, String>.configureRabbitQueue(
            props: Map<String, String>,
            type: TopicType
        ) {
            val channel = "lemline.messaging.kafka.${type.config}"
            val queue = props["$channel.queue"] ?: type.defaultTopicName

            if (props[type.consumerEnabled].toBoolean()) {
                val consumer = "$channel.consumer"
                val incoming = "mp.messaging.incoming.${type.incomingChannel}"
                set("$incoming.connector", RABBITMQ_CONNECTOR)
                set("$incoming.queue.name", queue)
                set("$incoming.queue.durable", "true")
                set("$incoming.auto-ack", "false")
                set("$incoming.deserializer", RABBITMQ_STRING_SERIALIZER)
                set("$incoming.queue.arguments.x-dead-letter-exchange", "dlx")
                set("$incoming.queue.arguments.x-dead-letter-routing-key", props["$consumer.queue-dlq"] ?: "$queue-dlq")
                // set the consumer concurrency
                set(type.consumerConcurrency, props["$consumer.concurrency"] ?: CONSUMER_CONCURRENCY_DEFAULT)
            }

            if (props[type.producerEnabled].toBoolean()) {
                val producer = "$channel.producer"
                val outgoing = "mp.messaging.outgoing.${type.outgoingChannel}"
                set("$outgoing.connector", RABBITMQ_CONNECTOR)
                set("$outgoing.queue.name", props["$producer.queue-out"] ?: queue)
                set("$outgoing.serializer", RABBITMQ_STRING_SERIALIZER)
                props["$producer.exchange-name"]?.let { set("$outgoing.exchange.name", it) }
            }
        }

        private fun MutableMap<String, String>.configureInMemory() {
            set("mp.messaging.incoming.$WORKFLOWS_IN_CHANNEL.connector", IN_MEMORY_CONNECTOR)
            set("mp.messaging.outgoing.$WORKFLOWS_OUT_CHANNEL.connector", IN_MEMORY_CONNECTOR)

            set("mp.messaging.incoming.$INGESTION_IN_CHANNEL.connector", IN_MEMORY_CONNECTOR)
            set("mp.messaging.outgoing.$INGESTION_OUT_CHANNEL.connector", IN_MEMORY_CONNECTOR)
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
