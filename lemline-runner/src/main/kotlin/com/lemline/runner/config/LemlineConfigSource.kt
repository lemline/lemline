package com.lemline.runner.config

import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.runner.LemlineApplication
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
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
import com.lemline.runner.config.LemlineConfigConstants.MYSQL_USER_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_NAME_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_USER_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_QUEUE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_STRING_SERIALIZER
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_USER_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_VHOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.WORKFLOWS_TOPIC_DEFAULT
import com.lemline.runner.instances.WORKFLOW_IN
import com.lemline.runner.instances.WORKFLOW_OUT
import io.smallrye.config.PropertiesConfigSource

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

            // System property overrides
            lemlineProps[CONSUMER_ENABLED] =
                System.getProperty(CONSUMER_ENABLED) ?: lemlineProps[CONSUMER_ENABLED] ?: "false"
            lemlineProps[PRODUCER_ENABLED] =
                System.getProperty(PRODUCER_ENABLED) ?: lemlineProps[PRODUCER_ENABLED] ?: "false"

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

            // if the type is explicitly set, validate we have a matching database configuration
            when (props[DATABASE_TYPE]) {
                DB_TYPE_POSTGRESQL -> require(usePostgres) { "Property 'postgresql' is not defined. Please specify it." }
                DB_TYPE_MYSQL -> require(useMysql) { "Property 'mysql' is not defined. Please specify it." }
            }

            val type = props[DATABASE_TYPE] ?: run {
                when {
                    usePostgres && useMysql -> throw IllegalArgumentException("Both properties 'postgresql' and 'mysql' are defined. Explicitly set '$DATABASE_TYPE' to '$DB_TYPE_POSTGRESQL' or '$DB_TYPE_MYSQL'.")
                    usePostgres -> DB_TYPE_POSTGRESQL
                    useMysql -> DB_TYPE_MYSQL
                    else -> DB_TYPE_IN_MEMORY
                }
            }
            generated[DATABASE_TYPE] = type

            if (usePostgres) {
                val prefix = "lemline.database.postgresql"
                val host = props["$prefix.host"] ?: POSTGRES_HOST_DEFAULT
                val port = props["$prefix.port"] ?: POSTGRES_PORT_DEFAULT
                val name = props["$prefix.name"] ?: POSTGRES_NAME_DEFAULT
                val username = props["$prefix.username"] ?: POSTGRES_USER_DEFAULT
                val password = props["$prefix.password"] ?: POSTGRES_PASSWORD_DEFAULT

                // apply defaults
                "$prefix.host".let { if (props[it] == null) generated[it] = host }
                "$prefix.port".let { if (props[it] == null) generated[it] = port }
                "$prefix.name".let { if (props[it] == null) generated[it] = name }
                "$prefix.username".let { if (props[it] == null) generated[it] = username }
                "$prefix.password".let { if (props[it] == null) generated[it] = password }

                // apply quarkus properties
                if (type == DB_TYPE_POSTGRESQL) {
                    generated["quarkus.datasource.postgresql.username"] = username
                    generated["quarkus.datasource.postgresql.password"] = password
                    generated["quarkus.datasource.postgresql.jdbc.url"] = "jdbc:postgresql://$host:$port/$name"
                }
            }

            if (useMysql) {
                val prefix = "lemline.database.mysql"
                val host = props["$prefix.host"] ?: MYSQL_HOST_DEFAULT
                val port = props["$prefix.port"] ?: MYSQL_PORT_DEFAULT
                val name = props["$prefix.name"] ?: MYSQL_NAME_DEFAULT
                val username = props["$prefix.username"] ?: MYSQL_USER_DEFAULT
                val password = props["$prefix.password"] ?: MYSQL_PASSWORD_DEFAULT

                // apply defaults
                "$prefix.host".let { if (props[it] == null) generated[it] = host }
                "$prefix.port".let { if (props[it] == null) generated[it] = port }
                "$prefix.name".let { if (props[it] == null) generated[it] = name }
                "$prefix.username".let { if (props[it] == null) generated[it] = username }
                "$prefix.password".let { if (props[it] == null) generated[it] = password }

                // apply quarkus properties
                if (type == DB_TYPE_MYSQL) {
                    generated["quarkus.datasource.mysql.username"] = username
                    generated["quarkus.datasource.mysql.password"] = password
                    generated["quarkus.datasource.mysql.jdbc.url"] = "jdbc:mysql://$host:$port/$name" +
                        "?useSSL=false" +
                        "&allowPublicKeyRetrieval=true" +
                        "&sessionVariables=sql_mode='STRICT_ALL_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ZERO_DATE,NO_ZERO_IN_DATE,NO_ENGINE_SUBSTITUTION'" +
                        "&continueBatchOnError=false"
                }
            }

            // apply quarkus properties
            if (type == DB_TYPE_IN_MEMORY) {
                generated["quarkus.datasource.username"] = LemlineConfigConstants.H2_USERNAME_DEFAULT
                generated["quarkus.datasource.password"] = LemlineConfigConstants.H2_PASSWORD_DEFAULT
                generated["quarkus.datasource.jdbc.url"] =
                    "jdbc:h2:mem:${LemlineConfigConstants.H2_DB_NAME_DEFAULT};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            }

            return generated
        }

        private fun generateMessagingProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            val useKafka = props.keys.any { it.startsWith("lemline.messaging.kafka.") }
            val useRabbit = props.keys.any { it.startsWith("lemline.messaging.rabbitmq.") }

            when (props[MESSAGING_TYPE]) {
                MSG_TYPE_KAFKA -> require(useKafka) { "Property 'kafka' is not defined. Please specify it." }
                DB_TYPE_MYSQL -> require(useRabbit) { "Property 'rabbitmq' is not defined. Please specify it." }
            }

            val type = props[MESSAGING_TYPE] ?: run {
                when {
                    useKafka && useRabbit -> throw IllegalArgumentException("Both properties 'kafka' and 'rabbitmq' are defined. Explicitly set '$MESSAGING_TYPE' to '$MSG_TYPE_KAFKA' or '$MSG_TYPE_RABBITMQ'.")
                    useKafka -> MSG_TYPE_KAFKA
                    useRabbit -> MSG_TYPE_RABBITMQ
                    else -> MSG_TYPE_IN_MEMORY
                }
            }
            generated[MESSAGING_TYPE] = type

            val incoming = "mp.messaging.incoming.$WORKFLOW_IN"
            val outgoing = "mp.messaging.outgoing.$WORKFLOW_OUT"
            generated["$outgoing.merge"] = "true"

            val consumerEnabled = props[CONSUMER_ENABLED].toBoolean()
            val producerEnabled = props[PRODUCER_ENABLED].toBoolean()

            if (useKafka) {
                val prefix = "lemline.messaging.kafka"
                val brokers = props["$prefix.brokers"] ?: KAFKA_BROKERS_DEFAULT

                val useWorkflows = props.keys.any { it.startsWith("$prefix.workflows") }
                val useIngestion = props.keys.any { it.startsWith("$prefix.ingestion") }

                if (useWorkflows) {

                }

                if (useIngestion) {

                }

                val topic = props["$prefix.topic"] ?: WORKFLOWS_TOPIC_DEFAULT
                val groupId = props["$prefix.group-id"] ?: KAFKA_GROUP_ID_DEFAULT
                val offsetReset = props["$prefix.offset-reset"] ?: KAFKA_OFFSET_RESET_DEFAULT
                val topicDLQ = props["$prefix.topic-dlq"] ?: "$topic-dlq"
                val topicOut = props["$prefix.topic-out"] ?: topic

                // apply defaults
                "$prefix.brokers".let { if (props[it] == null) generated[it] = brokers }
                "$prefix.topic".let { if (props[it] == null) generated[it] = topic }
                "$prefix.group-id".let { if (props[it] == null) generated[it] = groupId }
                "$prefix.offset-reset".let { if (props[it] == null) generated[it] = offsetReset }
                "$prefix.topic-dlq".let { if (props[it] == null) generated[it] = topicDLQ }

                // apply smallrye messaging properties
                if (type == MSG_TYPE_KAFKA) {
                    generated["kafka.bootstrap.servers"] = brokers

                    if (consumerEnabled) {
                        generated["$incoming.connector"] = KAFKA_CONNECTOR
                        generated["$incoming.topic"] = topic
                        generated["$incoming.group.id"] = groupId
                        generated["$incoming.auto.offset.reset"] = offsetReset
                        generated["$incoming.failure-strategy"] = "dead-letter-queue"
                        generated["$incoming.dead-letter-queue.topic"] = topicDLQ
                        generated["$incoming.value.deserializer"] = KAFKA_STRING_DESERIALIZER
                    }

                    if (producerEnabled) {
                        generated["$outgoing.connector"] = KAFKA_CONNECTOR
                        generated["$outgoing.topic"] = topicOut
                        generated["$outgoing.value.serializer"] = KAFKA_STRING_SERIALIZER
                    }

                    props["$prefix.security-protocol"]?.let { generated["kafka.security.protocol"] = it }
                    props["$prefix.sasl-mechanism"]?.let { generated["kafka.sasl.mechanism"] = it }

                    if (props.containsKey("$prefix.sasl-username") && props.containsKey("$prefix.sasl-password")) {
                        generated["kafka.sasl.jaas.config"] =
                            "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                                "username=\"${props["$prefix.sasl-username"]}\" " +
                                "password=\"${props["$prefix.sasl-password"]}\";"
                        if (!generated.containsKey("kafka.sasl.mechanism")) {
                            generated["kafka.sasl.mechanism"] = "PLAIN"
                        }
                    }
                }
            }

            if (useRabbit) {
                val prefix = "lemline.messaging.rabbitmq"
                val hostname = props["$prefix.hostname"] ?: RABBITMQ_HOST_DEFAULT
                val port = props["$prefix.port"] ?: RABBITMQ_PORT_DEFAULT
                val username = props["$prefix.username"] ?: RABBITMQ_USER_DEFAULT
                val password = props["$prefix.password"] ?: RABBITMQ_PASSWORD_DEFAULT
                val virtualHost = props["$prefix.virtual-host"] ?: RABBITMQ_VHOST_DEFAULT
                val queue = props["$prefix.queue"] ?: RABBITMQ_QUEUE_DEFAULT
                val queueDLQ = props["$prefix.queue-dlq"] ?: "$queue-dlq"
                val queueOut = props["$prefix.queue-out"] ?: queue

                // apply defaults
                "$prefix.hostname".let { if (props[it] == null) generated[it] = hostname }
                "$prefix.port".let { if (props[it] == null) generated[it] = port }
                "$prefix.username".let { if (props[it] == null) generated[it] = username }
                "$prefix.password".let { if (props[it] == null) generated[it] = password }
                "$prefix.virtual-host".let { if (props[it] == null) generated[it] = virtualHost }
                "$prefix.queue".let { if (props[it] == null) generated[it] = queue }

                // apply smallrye messaging properties
                if (type == MSG_TYPE_RABBITMQ) {
                    generated["rabbitmq-host"] = hostname
                    generated["rabbitmq-port"] = port
                    generated["rabbitmq-username"] = username
                    generated["rabbitmq-password"] = password
                    generated["rabbitmq-virtual-host"] = virtualHost

                    if (consumerEnabled) {
                        generated["$incoming.connector"] = RABBITMQ_CONNECTOR
                        generated["$incoming.queue.name"] = queue
                        generated["$incoming.queue.durable"] = "true"
                        generated["$incoming.auto-ack"] = "false"
                        generated["$incoming.deserializer"] = RABBITMQ_STRING_SERIALIZER
                        generated["$incoming.queue.arguments.x-dead-letter-exchange"] = "dlx"
                        generated["$incoming.queue.arguments.x-dead-letter-routing-key"] = queueDLQ
                    }

                    if (producerEnabled) {
                        generated["$outgoing.connector"] = RABBITMQ_CONNECTOR
                        generated["$outgoing.queue.name"] = queueOut
                        generated["$outgoing.serializer"] = RABBITMQ_STRING_SERIALIZER
                    }

                    props["$prefix.exchange-name"]?.let { generated["$outgoing.exchange.name"] = it }
                    props["$prefix.ssl-enabled"]?.let { generated["rabbitmq-ssl"] = it }
                }
            }

            if (type == MSG_TYPE_IN_MEMORY) {
                generated["$incoming.connector"] = IN_MEMORY_CONNECTOR
                generated["$outgoing.connector"] = IN_MEMORY_CONNECTOR
            }

            return generated
        }

        private fun generateMetricsProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            val prefix = "lemline.metrics"
            val port = props["$prefix.port"] ?: METRICS_PORT_DEFAULT
            val path = props["$prefix.path"] ?: METRICS_PATH_DEFAULT

            // apply defaults
            "$prefix.port".let { if (props[it] == null) generated[it] = port }
            "$prefix.path".let { if (props[it] == null) generated[it] = path }

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
