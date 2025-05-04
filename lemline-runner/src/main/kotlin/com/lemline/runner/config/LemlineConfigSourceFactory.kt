// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.runner.config.LemlineConfiguration.DatabaseConfig
import com.lemline.runner.config.LemlineConfiguration.MessagingConfig
import io.quarkus.runtime.annotations.ConfigPhase
import io.quarkus.runtime.annotations.ConfigRoot
import io.smallrye.config.ConfigSourceContext
import io.smallrye.config.ConfigSourceFactory
import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import java.util.*
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

    private val log = logger()

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
    override fun getConfigSources(context: ConfigSourceContext): Iterable<ConfigSource> {

        log.debug { "LemlineConfigSourceFactory executing..." }

        // Collect all properties from the context that start with "lemline."
        val lemlineProps = mutableMapOf<String, String>()
        for (name in context.iterateNames()) {
            if (name.startsWith("lemline.")) {
                // Retrieve the value for the property and add it to the map
                context.getValue(name)?.value?.let { lemlineProps[name] = it.split("#").first().trim() }
            }
        }

        // Override properties from the lemline.config.locations files, if any
        val configUri = context.getValue("lemline.config.locations").value
        log.debug { "lemline.config.locations=$configUri" }
        ExtraFileConfigFactory().getConfigSources(configUri)
            .forEach { configSource ->
                configSource.properties.forEach { (name, value) ->
                    if (name.startsWith("lemline.")) {
                        lemlineProps[name] = value.split("#").first().trim()
                    } else {
                        log.info { "Skipping not lemline property $name" }
                    }
                }
            }

        if (lemlineProps.isEmpty()) {
            log.info { "No Lemline properties found, skipping configuration transformation" }
            return emptyList()
        } else {
            log.debug { "Lemline properties found: $lemlineProps" }
        }

        try {
            // Create a SmallRyeConfig instance with the collected properties
            val config = SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .addDiscoveredInterceptors()
                .withSources(PropertiesConfigSource(lemlineProps, "LemlineProperties", 100))
                .withMapping(LemlineConfiguration::class.java) // 👈 explicit registration
                .build()

            // Create a type-safe configuration
            val lemlineConfig = config.getConfigMapping(LemlineConfiguration::class.java)

            // Generate Quarkus properties using companion object methods
            val generatedProps = mutableMapOf<String, String>()
            generatedProps.putAll(DatabaseConfig.toQuarkusProperties(lemlineConfig.database()))
            generatedProps.putAll(MessagingConfig.toQuarkusProperties(lemlineConfig.messaging()))

            log.debug {
                "Generated properties: ${generatedProps.map { "${it.key}=${it.value}" }.joinToString()}"
            }

            // Combine both generated Quarkus properties and original Lemline properties
            val allProps = mutableMapOf<String, String>()
            allProps.putAll(generatedProps)
            allProps.putAll(lemlineProps)

            return listOf(
                PropertiesConfigSource(
                    allProps,
                    LemlineConfigConstants.CONFIG_SOURCE_NAME,
                    LemlineConfigConstants.CONFIG_ORDINAL
                )
            )
        } catch (e: Exception) {
            log.error(e) { "Error transforming Lemline configuration" }
            throw e
        }
    }
}
