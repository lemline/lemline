// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.runner.LemlineApplication
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import com.lemline.runner.config.LemlineConfigConstants.IN_MEMORY_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_CONNECTOR
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_KAFKA
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_RABBITMQ
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_CONNECTOR
import com.lemline.runner.instances.WORKFLOW_IN
import com.lemline.runner.instances.WORKFLOW_OUT
import io.quarkus.runtime.annotations.ConfigPhase
import io.quarkus.runtime.annotations.ConfigRoot
import io.smallrye.config.ConfigSourceContext
import io.smallrye.config.ConfigSourceFactory
import io.smallrye.config.PropertiesConfigSource
import org.eclipse.microprofile.config.spi.ConfigSource


/**
 * Custom configuration source factory for Lemline.
 * This class transforms Lemline-specific configuration into Quarkus-compatible properties.
 *
 * Configuration Transformation Process:
 * 1. Collects all properties starting with "lemline."
 * 2. Creates a type-safe configuration using SmallRyeConfig
 * 3. Uses companion object methods to generate Quarkus-specific properties
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
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
class LemlineConfigSourceFactory : ConfigSourceFactory {

    private val logger = logger()

    override fun getConfigSources(context: ConfigSourceContext): Iterable<ConfigSource> {
        val lemlineProps = collectUserProperties()
        logger.info { "Lemline properties:\n${lemlineProps.toPrint()}" }
        try {
            // Generate Quarkus properties using companion object methods
            val generatedProps = mutableMapOf<String, String>()
            generatedProps.putAll(generateDatabaseProperties(lemlineProps))
            generatedProps.putAll(generateMessagingProperties(lemlineProps))
            generatedProps.putAll(generateMetricsProperties(lemlineProps))

            logger.info { "Generated properties:\n${generatedProps.toPrint()}" }

            // Combine both generated and original Lemline properties
            val allProps = mutableMapOf<String, String>()
            allProps.putAll(lemlineProps)
            allProps.putAll(generatedProps)

            return listOf(
                PropertiesConfigSource(
                    allProps,
                    LemlineConfigConstants.CONFIG_SOURCE_NAME,
                    LemlineConfigConstants.CONFIG_ORDINAL
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Error transforming Lemline configuration" }
            throw e
        }
    }

    /**
     * Retrieves configuration sources based on the provided context.
     * This method is called during Quarkus startup to transform Lemline configuration.
     *
     * Process:
     * 1. Collects all lemline.* properties
     * 2. Creates a type-safe configuration using SmallRyeConfig
     * 3. Uses companion object methods to generate Quarkus-specific properties
     * 4. Creates a ConfigSource with the transformed properties
     *
     * @param context The configuration context containing all available properties
     * @return iterable of ConfigSource instances
     */
    private fun collectUserProperties(): MutableMap<String, String> {
        // Collect all properties from the context that start with "lemline."
        val lemlineProps = mutableMapOf<String, String>()

        // Get properties from the config file, if any
        LemlineApplication.configPath?.let { path ->
            logger.info { "Retrieve Lemline config from file: $path" }
            ExtraFileConfigFactory().getConfig(path).properties.forEach { (name, value) ->
                if (name.startsWith("lemline.")) {
                    lemlineProps[name] = value.split("#").first().trim()
                } else {
                    logger.info { "Skipping non-Lemline property: $name" }
                }
            }
        }

        // Are consumers enabled?
        lemlineProps[CONSUMER_ENABLED] =
            System.getProperty(CONSUMER_ENABLED) ?: lemlineProps[CONSUMER_ENABLED] ?: "false"

        // Are producers enabled?
        lemlineProps[PRODUCER_ENABLED] =
            System.getProperty(PRODUCER_ENABLED) ?: lemlineProps[PRODUCER_ENABLED] ?: "false"

        return lemlineProps
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
            val host = props["$prefix.host"] ?: "localhost"
            val port = props["$prefix.port"] ?: "5432"
            val name = props["$prefix.name"] ?: "lemline"
            val username = props["$prefix.username"] ?: "postgres"
            val password = props["$prefix.password"] ?: "postgres"

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
            val host = props["$prefix.host"] ?: "localhost"
            val port = props["$prefix.port"] ?: "3306"
            val name = props["$prefix.name"] ?: "lemline"
            val username = props["$prefix.username"] ?: "mysql"
            val password = props["$prefix.password"] ?: "mysql"

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
            generated["quarkus.datasource.username"] = LemlineConfigConstants.DEFAULT_H2_USERNAME
            generated["quarkus.datasource.password"] = LemlineConfigConstants.DEFAULT_H2_PASSWORD
            generated["quarkus.datasource.jdbc.url"] =
                "jdbc:h2:mem:${LemlineConfigConstants.DEFAULT_H2_DB_NAME};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
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
            val topic = props["$prefix.topic"] ?: "lemline"
            val groupId = props["$prefix.group-id"] ?: "group-1"
            val offsetReset = props["$prefix.offset-reset"] ?: "earliest"
            val brokers = props["$prefix.brokers"] ?: "localhost:9092"
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
                    generated["$incoming.value.deserializer"] =
                        "org.apache.kafka.common.serialization.StringDeserializer"
                }

                if (producerEnabled) {
                    generated["$outgoing.connector"] = KAFKA_CONNECTOR
                    generated["$outgoing.topic"] = topicOut
                    generated["$outgoing.value.serializer"] =
                        "org.apache.kafka.common.serialization.StringSerializer"
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
            val hostname = props["$prefix.hostname"] ?: "localhost"
            val port = props["$prefix.port"] ?: "5672"
            val username = props["$prefix.username"] ?: "guest"
            val password = props["$prefix.password"] ?: "guest"
            val virtualHost = props["$prefix.virtual-host"] ?: "/"
            val queue = props["$prefix.queue"] ?: "lemline"
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
                    generated["$incoming.deserializer"] = "java.lang.String"
                    generated["$incoming.queue.arguments.x-dead-letter-exchange"] = "dlx"
                    generated["$incoming.queue.arguments.x-dead-letter-routing-key"] = queueDLQ
                }

                if (producerEnabled) {
                    generated["$outgoing.connector"] = RABBITMQ_CONNECTOR
                    generated["$outgoing.queue.name"] = queueOut
                    generated["$outgoing.serializer"] = "java.lang.String"
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
        val port = props["$prefix.port"] ?: "8080"
        val path = props["$prefix.path"] ?: "/q/metrics"

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
