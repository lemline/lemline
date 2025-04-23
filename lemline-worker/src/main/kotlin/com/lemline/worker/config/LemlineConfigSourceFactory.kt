// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.config

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.common.warn
import io.smallrye.config.ConfigSourceContext
import io.smallrye.config.ConfigSourceFactory
import io.smallrye.config.PropertiesConfigSource
import java.util.*
import org.eclipse.microprofile.config.spi.ConfigSource

class LemlineConfigSourceFactory : ConfigSourceFactory {

    private val log = logger()
    private val configOrdinal = 275

    /**
     * Retrieves configuration sources based on the provided context.
     */
    override fun getConfigSources(context: ConfigSourceContext): Iterable<ConfigSource> {
        log.debug { "LemlineConfigSourceFactory executing..." }

        // Collect all properties from the context that start with "lemline."
        val lemlineProps = mutableMapOf<String, String>()
        for (name in context.iterateNames()) {
            if (name.startsWith("lemline.")) {
                // Retrieve the value for the property and add it to the map
                context.getValue(name)?.value?.let { lemlineProps[name] = it }
            }
        }
        log.debug { "Lemline properties found: $lemlineProps" }

        // Generate framework-specific properties based on the collected lemline properties
        val generatedProps = generateFrameworkProperties(lemlineProps)

        // Return the appropriate ConfigSource based on whether generatedProps is empty or not
        return when (generatedProps.isEmpty()) {
            true -> emptyList<ConfigSource>().also {
                log.info { "No framework properties were generated." }
            }

            false -> listOf(
                // Create a ConfigSource with the generated properties and a specific ordinal
                PropertiesConfigSource(generatedProps, "LemlineGeneratedConfigSource", configOrdinal)
            ).also {
                log.debug {
                    "Generated properties:\n${
                        generatedProps.map { "${it.key}=${it.value}" }.joinToString("\n")
                    }"
                }
            }
        }
    }

    /**
     * Generates Quarkus-specific properties based on the provided Lemline properties.
     */
    private fun generateFrameworkProperties(lemlineProps: Map<String, String>): Map<String, String> {
        if (lemlineProps.isEmpty()) return emptyMap()

        val props = mutableMapOf<String, String>()

        fun getProp(key: String): String? = lemlineProps[key]
        fun requireProp(key: String): String = getProp(key)
            ?: throw NoSuchElementException("Required lemline property not found in context: $key")

        try {
            val dbTypeKey = "lemline.database.type"

            val dbType = getProp(dbTypeKey) ?: "h2" // default to H2 if not specified
            props["quarkus.datasource.db-kind"] = dbType

            when (dbType) {
                "postgresql" -> {
                    val prefix = "lemline.database.postgresql"
                    val pgHost = requireProp("$prefix.host")
                    val pgPort = requireProp("$prefix.port")
                    val pgDbName = requireProp("$prefix.name")
                    props["quarkus.datasource.jdbc.url"] = "jdbc:postgresql://$pgHost:$pgPort/$pgDbName"
                    props["quarkus.datasource.username"] = requireProp("$prefix.username")
                    props["quarkus.datasource.password"] = requireProp("$prefix.password")
                    props["quarkus.flyway.locations"] = "classpath:db/migration/postgresql"
                }

                "mysql" -> {
                    val prefix = "lemline.database.mysql"
                    val mysqlHost = requireProp("$prefix.host")
                    val mysqlPort = requireProp("$prefix.port")
                    val mysqlDbName = requireProp("$prefix.name")
                    props["quarkus.datasource.jdbc.url"] =
                        "jdbc:mysql://$mysqlHost:$mysqlPort/$mysqlDbName?useSSL=false&allowPublicKeyRetrieval=true"
                    props["quarkus.datasource.username"] = requireProp("$prefix.username")
                    props["quarkus.datasource.password"] = requireProp("$prefix.password")
                    props["quarkus.flyway.locations"] = "classpath:db/migration/mysql"
                }

                "h2" -> {
                    val prefix = "lemline.database.h2"
                    val h2DbName = getProp("$prefix.name") ?: "testdb"
                    props["quarkus.datasource.jdbc.url"] = "jdbc:h2:mem:$h2DbName;DB_CLOSE_DELAY=-1"
                    props["quarkus.datasource.username"] = getProp("$prefix.username") ?: "sa"
                    props["quarkus.datasource.password"] = getProp("$prefix.password") ?: ""
                }

                else -> log.warn { "Unsupported lemline.database.type: '$dbType'. DB properties not generated." }
            }
        } catch (e: NoSuchElementException) {
            log.warn(e) { "Incomplete database configuration: ${e.message}" }
        } catch (e: Exception) {
            log.error(e) { "Error processing database configuration" }
        }

        try {
            val msgTypeKey = "lemline.messaging.type"
            if (lemlineProps.containsKey(msgTypeKey)) {
                val msgType = requireProp(msgTypeKey).trim().lowercase()
                val incoming = "mp.messaging.incoming.workflows-in"
                val outgoing = "mp.messaging.outgoing.workflows-out"
                props["$outgoing.merge"] = "true"

                when (msgType) {
                    "kafka" -> {
                        val prefix = "lemline.messaging.kafka"
                        // server
                        props["kafka.bootstrap.servers"] = requireProp("$prefix.brokers")

                        // incoming
                        props["$incoming.connector"] = "smallrye-kafka"
                        props["$incoming.topic"] = requireProp("$prefix.topic-in")
                        props["$incoming.group.id"] = requireProp("$prefix.group-id")
                        props["$incoming.auto.offset.reset"] = requireProp("$prefix.offset-reset")
                        props["$incoming.failure-strategy"] = "dead-letter-queue"
                        props["$incoming.dead-letter-queue.topic"] = requireProp("$prefix.topic-dlq")

                        // outgoing
                        props["$outgoing.connector"] = "smallrye-kafka"
                        props["$outgoing.topic"] = getProp("$prefix.topic-out") ?: requireProp("$prefix.topic-in")

                        // optional properties - only set if a value exists and is not a comment placeholder
                        getProp("$prefix.security-protocol")?.takeIf { !it.startsWith("#") }
                            ?.let { props["kafka.security.protocol"] = it }
                        getProp("$prefix.sasl-mechanism")?.takeIf { !it.startsWith("#") }
                            ?.let { props["kafka.sasl.mechanism"] = it }

                        val saslUser = getProp("$prefix.sasl-username")?.takeIf { !it.startsWith("#") }
                        val saslPass = getProp("$prefix.sasl-password")?.takeIf { !it.startsWith("#") }

                        if (saslUser != null && saslPass != null) {
                            props["kafka.sasl.jaas.config"] =
                                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$saslUser\" password=\"$saslPass\";"
                            // Set the default mechanism to PLAIN if SASL user/pass are provided, but the mechanism isn't explicitly set
                            if (!props.containsKey("kafka.sasl.mechanism")) {
                                props["kafka.sasl.mechanism"] = "PLAIN"
                            }
                        }
                    }

                    "rabbitmq" -> {
                        val prefix = "lemline.messaging.rabbitmq"
                        // server
                        props["rabbitmq-host"] = requireProp("$prefix.hostname")
                        props["rabbitmq-port"] = requireProp("$prefix.port")
                        props["rabbitmq-username"] = requireProp("$prefix.username")
                        props["rabbitmq-password"] = requireProp("$prefix.password")

                        // incoming
                        props["$incoming.connector"] = "smallrye-rabbitmq"
                        props["$incoming.queue.name"] = requireProp("$prefix.queue-in")
                        props["$incoming.queue.durable"] = "true"
                        props["$incoming.auto-ack"] = "false"
                        props["$incoming.deserializer"] = "java.lang.String"
                        props["$incoming.queue.arguments.x-dead-letter-exchange"] = "dlx"
                        props["$incoming.queue.arguments.x-dead-letter-routing-key"] =
                            requireProp("$prefix.queue-in") + "-dlq"

                        // outgoing
                        props["$outgoing.connector"] = "smallrye-rabbitmq"
                        props["$outgoing.queue.name"] = getProp("$prefix.queue-out") ?: requireProp("$prefix.queue-in")
                        props["$outgoing.serializer"] = "java.lang.String"
                    }

                    else -> log.warn { "Unsupported $msgTypeKey: '$msgType'. Messaging properties not generated." }
                }
            }
        } catch (e: NoSuchElementException) {
            log.warn(e) { "Incomplete messaging configuration: ${e.message}" }
        } catch (e: Exception) {
            log.error(e) { "Error processing messaging configuration" }
        }

        return Collections.unmodifiableMap(props)
    }
}
