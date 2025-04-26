// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.config

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.worker.messaging.WORKFLOW_IN
import com.lemline.worker.messaging.WORKFLOW_OUT
import io.smallrye.config.ConfigSourceContext
import io.smallrye.config.ConfigSourceFactory
import io.smallrye.config.PropertiesConfigSource
import java.util.*
import org.eclipse.microprofile.config.spi.ConfigSource

/**
 * Custom configuration source factory for Lemline.
 * This class transforms Lemline-specific configuration into Quarkus-compatible properties.
 *
 * Configuration Transformation Process:
 * 1. Collects all properties starting with "lemline."
 * 2. Transforms them into framework-specific properties:
 *    - Database properties (quarkus.datasource.*)
 *    - Messaging properties (kafka.*, rabbitmq.*)
 * 3. Provides default values and validation
 *
 * Configuration Sources:
 * - Ordinal: 275 (higher than default sources)
 * - Priority: Takes precedence over application.properties
 * - Scope: Applies to all profiles
 *
 * Configuration Precedence:
 * - This ConfigSourceFactory adds a new configuration source with transformed properties
 * - The transformed properties take precedence over properties from sources with lower ordinals
 * - Properties from other sources (system properties, environment variables, etc.) are preserved
 * - Only properties that are transformed from lemline.* properties are overridden
 *
 * Configuration Timing:
 * - This factory runs during Quarkus's configuration phase
 * - Database configuration must be complete before Flyway runs
 * - To ensure proper timing:
 *   - Set quarkus.flyway.migrate-at-start=false if using custom database configuration
 *   - Configure database properties before Flyway initialization
 *   - Use quarkus.flyway.migrate-at-start=true only when database configuration is stable
 *
 * Database Initialization Order:
 * To ensure database properties are set before Flyway initialization:
 * 1. Configuration Profiles:
 *    - Create a 'db' profile with database configuration
 *    - Load it before the default profile: -Dquarkus.profile=db,default
 *    - Example application-db.properties:
 *      ```properties
 *      # Production/Development with custom configuration
 *      quarkus.flyway.migrate-at-start=false
 *      lemline.database.type=postgresql
 *      lemline.database.postgresql.host=localhost
 *      # ... other database properties
 *      ```
 *
 * 2. Manual Migration Control:
 *    - Disable automatic migration: quarkus.flyway.migrate-at-start=false
 *    - Create a StartupEvent observer to run migration after configuration:
 *    ```kotlin
 *    @ApplicationScoped
 *    class FlywayMigration {
 *        @Inject
 *        lateinit var flyway: Flyway
 *
 *        fun onStart(@Observes event: StartupEvent) {
 *            flyway.migrate()
 *        }
 *    }
 *    ```
 *
 * Schema Generation:
 * The quarkus.hibernate-orm.database.generation property controls Hibernate's schema generation:
 * - none: No schema generation (use with Flyway)
 * - create: Creates schema on startup, drops on shutdown
 * - drop-and-create: Drops and recreates schema on startup
 * - update: Updates schema if needed
 * - validate: Validates schema against entities
 *
 * Best Practices:
 * - Always use 'none' when using Flyway (including in tests)
 * - Never mix Flyway with Hibernate schema generation
 * - For tests with Flyway:
 *   - Use a separate test database
 *   - Configure Flyway to clean the database before tests
 *   - Example test configuration (application-test.properties):
 *     ```properties
 *     # Tests use direct configuration, so migrate-at-start can be true
 *     quarkus.hibernate-orm.database.generation=none
 *     quarkus.flyway.clean-disabled=false
 *     quarkus.flyway.migrate-at-start=true
 *     quarkus.datasource.jdbc.url=jdbc:h2:mem:testdb
 *     quarkus.datasource.username=sa
 *     quarkus.datasource.password=
 *     ```
 *   - The `TestFlywayMigration` class ensures migrations are automatically run for the `test` profile by observing the `StartupEvent`, complementing the `application-test.properties` settings.
 * - For production/development with custom configuration:
 *   - Use migrate-at-start=false
 *   - Manually trigger migration after configuration
 * - Never use 'update' in production
 * - Use 'validate' to check schema-entity alignment
 *
 * Error Handling:
 * - Graceful degradation for missing properties
 * - Detailed logging of configuration issues
 * - Default values for optional properties
 *
 * @see LemlineConfiguration for type-safe configuration mapping
 * @see https://quarkus.io/guides/config-reference for Quarkus configuration details
 */
class LemlineConfigSourceFactory : ConfigSourceFactory {

    private val log = logger()

    /**
     * Retrieves configuration sources based on the provided context.
     * This method is called during Quarkus startup to transform Lemline configuration.
     *
     * Process:
     * 1. Collects all lemline.* properties
     * 2. Generates framework-specific properties
     * 3. Creates a ConfigSource with the transformed properties
     *
     * @param context The configuration context containing all available properties
     * @return An iterable of ConfigSource instances
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
                PropertiesConfigSource(
                    generatedProps,
                    LemlineConfigConstants.CONFIG_SOURCE_NAME,
                    LemlineConfigConstants.CONFIG_ORDINAL
                )
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
     * This method handles the transformation of Lemline configuration into framework-specific properties.
     *
     * Supported Transformations:
     * 1. Database Configuration:
     *    - PostgreSQL, MySQL, H2 support
     *    - Connection properties
     *    - Migration settings
     *
     * 2. Messaging Configuration:
     *    - Kafka support
     *    - RabbitMQ support
     *    - Security settings
     *
     * Error Handling:
     * - Missing required properties throw NoSuchElementException
     * - Optional properties use default values
     * - Configuration issues are logged
     *
     * @param lemlineProps Map of Lemline-specific properties
     * @return Map of framework-specific properties
     */
    private fun generateFrameworkProperties(lemlineProps: Map<String, String>): Map<String, String> {
        if (lemlineProps.isEmpty()) return emptyMap()

        val props = mutableMapOf<String, String>()

        fun getProp(key: String): String? = lemlineProps[key]
        fun requireProp(key: String): String = getProp(key)
            ?: throw NoSuchElementException("Required lemline property not found in context: $key")

        try {
            val dbTypeKey = "lemline.database.type"
            val dbType = getProp(dbTypeKey) ?: LemlineConfigConstants.DEFAULT_DB_TYPE

            // Validate database type
            if (dbType !in LemlineConfigConstants.SUPPORTED_DB_TYPES) {
                throw IllegalArgumentException("Unsupported database type: $dbType. Supported types: ${LemlineConfigConstants.SUPPORTED_DB_TYPES}")
            }

            props["quarkus.datasource.db-kind"] = dbType
            props["quarkus.flyway.migrate-at-start"] = getProp("lemline.database.migrate-at-start") ?: "false"

            when (dbType) {
                LemlineConfigConstants.DB_TYPE_POSTGRESQL -> configurePostgreSQL(props, lemlineProps)
                LemlineConfigConstants.DB_TYPE_MYSQL -> configureMySQL(props, lemlineProps)
                LemlineConfigConstants.DB_TYPE_H2 -> configureH2(props, lemlineProps)
            }
        } catch (e: NoSuchElementException) {
            log.warn(e) { "Incomplete database configuration: ${e.message}" }
            throw e // Fail fast for required configuration
        } catch (e: Exception) {
            log.error(e) { "Error processing database configuration" }
            throw e
        }

        try {
            val msgTypeKey = "lemline.messaging.type"
            if (lemlineProps.containsKey(msgTypeKey)) {
                val msgType = requireProp(msgTypeKey).trim().lowercase()

                // Validate messaging type
                if (msgType !in LemlineConfigConstants.SUPPORTED_MSG_TYPES) {
                    throw IllegalArgumentException("Unsupported messaging type: $msgType. Supported types: ${LemlineConfigConstants.SUPPORTED_MSG_TYPES}")
                }

                when (msgType) {
                    LemlineConfigConstants.MSG_TYPE_KAFKA -> configureKafka(props, lemlineProps)
                    LemlineConfigConstants.MSG_TYPE_RABBITMQ -> configureRabbitMQ(props, lemlineProps)
                    LemlineConfigConstants.MSG_TYPE_IN_MEMORY -> configureInMemory(props, lemlineProps)
                }
            }
        } catch (e: NoSuchElementException) {
            log.warn(e) { "Incomplete messaging configuration: ${e.message}" }
            throw e // Fail fast for required configuration
        } catch (e: Exception) {
            log.error(e) { "Error processing messaging configuration" }
            throw e
        }

        return Collections.unmodifiableMap(props)
    }

    private fun configurePostgreSQL(props: MutableMap<String, String>, lemlineProps: Map<String, String>) {
        val prefix = "lemline.database.postgresql"
        val pgHost = requireProp("$prefix.host", lemlineProps)
        val pgPort = requireProp("$prefix.port", lemlineProps)
        val pgDbName = requireProp("$prefix.name", lemlineProps)
        // Validate port number
        validatePort(pgPort.toInt())
        // set values
        props["quarkus.flyway.locations"] = "classpath:db/migration/postgresql"
        props["quarkus.datasource.jdbc.url"] = "jdbc:postgresql://$pgHost:$pgPort/$pgDbName"
        props["quarkus.datasource.username"] = requireProp("$prefix.username", lemlineProps)
        props["quarkus.datasource.password"] = requireProp("$prefix.password", lemlineProps)
    }

    private fun configureMySQL(props: MutableMap<String, String>, lemlineProps: Map<String, String>) {
        val prefix = "lemline.database.mysql"
        val mysqlHost = requireProp("$prefix.host", lemlineProps)
        val mysqlPort = requireProp("$prefix.port", lemlineProps)
        val mysqlDbName = requireProp("$prefix.name", lemlineProps)
        // Validate port number
        validatePort(mysqlPort.toInt())
        // set values
        props["quarkus.flyway.locations"] = "classpath:db/migration/mysql"
        props["quarkus.datasource.jdbc.url"] =
            "jdbc:mysql://$mysqlHost:$mysqlPort/$mysqlDbName?useSSL=false&allowPublicKeyRetrieval=true"
        props["quarkus.datasource.username"] = requireProp("$prefix.username", lemlineProps)
        props["quarkus.datasource.password"] = requireProp("$prefix.password", lemlineProps)
    }

    private fun configureH2(props: MutableMap<String, String>, lemlineProps: Map<String, String>) {
        val prefix = "lemline.database.h2"
        val h2DbName = getProp("$prefix.name", lemlineProps)
            ?: LemlineConfigConstants.DEFAULT_H2_DB_NAME
        // set values
        props["quarkus.flyway.locations"] = "classpath:db/migration/h2"
        props["quarkus.datasource.jdbc.url"] = "jdbc:h2:mem:$h2DbName;DB_CLOSE_DELAY=-1"
        props["quarkus.datasource.username"] = getProp("$prefix.username", lemlineProps)
            ?: LemlineConfigConstants.DEFAULT_H2_USERNAME
        props["quarkus.datasource.password"] = getProp("$prefix.password", lemlineProps)
            ?: LemlineConfigConstants.DEFAULT_H2_PASSWORD
    }

    private fun configureKafka(props: MutableMap<String, String>, lemlineProps: Map<String, String>) {
        val prefix = "lemline.messaging.kafka"
        val incoming = "mp.messaging.incoming.$WORKFLOW_IN"
        val outgoing = "mp.messaging.outgoing.$WORKFLOW_OUT"

        // server
        props["kafka.bootstrap.servers"] = requireProp("$prefix.brokers", lemlineProps)

        // incoming
        props["$incoming.connector"] = LemlineConfigConstants.KAFKA_CONNECTOR
        props["$incoming.topic"] = requireProp("$prefix.topic", lemlineProps)
        props["$incoming.group.id"] = requireProp("$prefix.group-id", lemlineProps)
        props["$incoming.auto.offset.reset"] = requireProp("$prefix.offset-reset", lemlineProps)
        props["$incoming.failure-strategy"] = "dead-letter-queue"
        props["$incoming.dead-letter-queue.topic"] = requireProp("$prefix.topic-dlq", lemlineProps)

        // outgoing
        props["$outgoing.connector"] = LemlineConfigConstants.KAFKA_CONNECTOR
        props["$outgoing.topic"] =
            getProp("$prefix.topic-out", lemlineProps) ?: requireProp("$prefix.topic", lemlineProps)
        props["$outgoing.merge"] = "true"

        // optional security settings
        configureKafkaSecurity(props, lemlineProps, prefix)
    }

    private fun configureKafkaSecurity(
        props: MutableMap<String, String>,
        lemlineProps: Map<String, String>,
        prefix: String
    ) {
        fun getProp(key: String): String? = lemlineProps[key]?.takeIf { !it.startsWith("#") }

        getProp("$prefix.security-protocol")?.let { props["kafka.security.protocol"] = it }
        getProp("$prefix.sasl-mechanism")?.let { props["kafka.sasl.mechanism"] = it }

        val saslUser = getProp("$prefix.sasl-username")
        val saslPass = getProp("$prefix.sasl-password")

        if (saslUser != null && saslPass != null) {
            props["kafka.sasl.jaas.config"] =
                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$saslUser\" password=\"$saslPass\";"
            // Set the default mechanism to PLAIN if SASL user/pass are provided, but the mechanism isn't explicitly set
            if (!props.containsKey("kafka.sasl.mechanism")) {
                props["kafka.sasl.mechanism"] = "PLAIN"
            }
        }
    }

    private fun configureRabbitMQ(props: MutableMap<String, String>, lemlineProps: Map<String, String>) {
        val prefix = "lemline.messaging.rabbitmq"
        val incoming = "mp.messaging.incoming.$WORKFLOW_IN"
        val outgoing = "mp.messaging.outgoing.$WORKFLOW_OUT"

        // server
        props["rabbitmq-host"] = requireProp("$prefix.hostname", lemlineProps)
        val port = requireProp("$prefix.port", lemlineProps)
        validatePort(port.toInt())
        props["rabbitmq-port"] = port
        props["rabbitmq-username"] = requireProp("$prefix.username", lemlineProps)
        props["rabbitmq-password"] = requireProp("$prefix.password", lemlineProps)

        // incoming
        props["$incoming.connector"] = LemlineConfigConstants.RABBITMQ_CONNECTOR
        props["$incoming.queue.name"] = requireProp("$prefix.queue", lemlineProps)
        props["$incoming.queue.durable"] = "true"
        props["$incoming.auto-ack"] = "false"
        props["$incoming.deserializer"] = "java.lang.String"
        props["$incoming.queue.arguments.x-dead-letter-exchange"] = "dlx"
        props["$incoming.queue.arguments.x-dead-letter-routing-key"] =
            requireProp("$prefix.queue", lemlineProps) + "-dlq"

        // outgoing
        props["$outgoing.connector"] = LemlineConfigConstants.RABBITMQ_CONNECTOR
        props["$outgoing.queue.name"] =
            getProp("$prefix.queue-out", lemlineProps) ?: requireProp("$prefix.queue", lemlineProps)
        props["$outgoing.serializer"] = "java.lang.String"
        props["$outgoing.merge"] = "true"
    }

    private fun configureInMemory(props: MutableMap<String, String>, lemlineProps: Map<String, String>) {
        val incoming = "mp.messaging.incoming.$WORKFLOW_IN"
        val outgoing = "mp.messaging.outgoing.$WORKFLOW_OUT"

        // Configure in-memory channels
        props["$incoming.connector"] = LemlineConfigConstants.IN_MEMORY_CONNECTOR
        props["$outgoing.connector"] = LemlineConfigConstants.IN_MEMORY_CONNECTOR
    }

    private fun validatePort(port: Int) {
        if (port < LemlineConfigConstants.MIN_PORT || port > LemlineConfigConstants.MAX_PORT) {
            throw IllegalArgumentException("Port number must be between ${LemlineConfigConstants.MIN_PORT} and ${LemlineConfigConstants.MAX_PORT}")
        }
    }

    private fun getProp(key: String, lemlineProps: Map<String, String>): String? = lemlineProps[key]
    private fun requireProp(key: String, lemlineProps: Map<String, String>): String = getProp(key, lemlineProps)
        ?: throw NoSuchElementException("Required lemline property not found in context: $key")
}
