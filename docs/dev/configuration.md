# Configuration System

## Overview

Lemline's configuration system is designed to be flexible, type-safe, and support multiple deployment scenarios. Key
features include:

- External configuration file for deployment-specific settings
- Type-safe configuration with Quarkus `@ConfigMapping`
- Multiple database backends (in-memory, PostgreSQL, MySQL)
- Multiple messaging systems (in-memory, Kafka, RabbitMQ)
- Proper initialization order for database resources and migrations

## Configuration Structure

The configuration is organized hierarchically:

```
lemline
├── database
│   ├── type: "postgresql"
│   ├── migrate-at-start: true
│   ├── postgresql:
│   │   ├── host: "localhost"
│   │   ├── port: 5432
│   │   └── ...
│   └── mysql:
│       ├── host: "localhost"
│       ├── port: 3306
│       └── ...
├── messaging
│   ├── type: "kafka"
│   ├── kafka:
│   │   ├── brokers: "localhost:9092"
│   │   └── ...
│   └── rabbitmq:
│       ├── host: "localhost"
│       ├── port: 5672
│       └── ...
└── ...
```

## Configuration Sources

The configuration file is searched in the following order:

1. Command-line option: `--config=<path>` or `-c <path>`
2. Environment variable: `LEMLINE_CONFIG`
3. File in current directory: `.lemline.yaml`
4. XDG-compliant location: `~/.config/lemline/config.yaml`
5. Home directory: `~/.lemline.yaml`


For example, with this configuration file (e.g., `config.yaml`):

```yaml
lemline:
  database:
    type: postgresql
    migrate-at-start: true
    postgresql:
      host: db.example.com
      port: 5432
      username: lemline
      password: secure-password
      name: lemline_db
  
  messaging:
    type: kafka
    consumer:
      enabled: true
    producer:
      enabled: true
    kafka:
      brokers: kafka.example.com:9092
      topic: workflows-in
```

Then launch with:

```bash
java -jar lemline-runner.jar --config=/path/to/config.yaml
```

Or using environment variable:

```bash
LEMLINE_CONFIG=/path/to/config.yaml java -jar lemline-runner.jar
```

## Command-Specific Configuration

Lemline CLI commands automatically set appropriate configuration:

- `lemline listen`: Enables both message consumer and producer
- `lemline instance start`: Enables only the message producer
- Other commands: Neither consumer nor producer enabled by default

## Database Configuration

### Supported Database Types

- `in-memory`: In-memory database (H2) for testing and development
- `postgresql`: PostgreSQL database for production
- `mysql`: MySQL database as an alternative

### Database Configuration Properties

```yaml
lemline:
  database:
    # Database type: in-memory, postgresql, mysql
    type: postgresql
    
    # Whether to run migrations at startup
    migrate-at-start: true
    
    # PostgreSQL-specific configuration (used when type is postgresql)
    postgresql:
      host: localhost
      port: 5432
      username: lemline
      password: lemline
      name: lemline
      schema: public
      ssl: false
    
    # MySQL-specific configuration (used when type is mysql)
    mysql:
      host: localhost
      port: 3306
      username: lemline
      password: lemline
      name: lemline
```

## Messaging Configuration

### Supported Messaging Types

- `in-memory`: In-memory messaging for testing and development
- `kafka`: Apache Kafka for production
- `rabbitmq`: RabbitMQ as an alternative

### Messaging Configuration Properties

```yaml
lemline:
  messaging:
    # Messaging type: in-memory, kafka, rabbitmq
    type: kafka
    
    # Consumer settings
    consumer:
      enabled: true
    
    # Producer settings
    producer:
      enabled: true
    
    # Kafka-specific configuration (used when type is kafka)
    kafka:
      brokers: localhost:9092
      topic: lemline-workflows
      groupId: lemline-group
      topicOut: lemline-workflows-out
      topicDlq: lemline-workflows-dlq
    
    # RabbitMQ-specific configuration (used when type is rabbitmq)
    rabbitmq:
      host: localhost
      port: 5672
      username: guest
      password: guest
      queue: lemline-workflows
      queueOut: lemline-workflows-out
      queueDlq: lemline-workflows-dlq
```

## Implementation Details

### Configuration Phases

Lemline's configuration system operates in distinct phases to ensure proper initialization and runtime flexibility:

1. **Pre-Quarkus CLI Processing**: 
   - Process command-line arguments and set system properties
   - Set logging levels
   - Locate configuration files
   - Configure message consumer/producer activation based on commands

2. **Quarkus Startup Configuration**:
   - Transform Lemline configuration into Quarkus-compatible format
   - Load and apply configuration from external files
   - Set up database connections and messaging infrastructure

3. **Runtime Configuration Management**:
   - Initialize database connections based on configuration
   - Execute database migrations when appropriate
   - Enable/disable messaging consumers based on configuration
   - Apply runtime settings from configuration sources

4. **Dynamic Service Activation**:
   - Selectively activate message consumers/producers at runtime
   - Choose appropriate database implementations

This phased approach allows Lemline to be configured flexibly while respecting Quarkus's initialization constraints.

### Pre-Quarkus CLI Processing

Before Quarkus starts, the `LemlineApplication` class processes command-line arguments to set environment variables for:
* log level
* config path
* producer/consumer enabled

```kotlin
@JvmStatic
fun main(args: Array<String>) {
    // Create a temporary CommandLine instance to parse the arguments
    val tempCli = CommandLine(MainCommand()).setup()

    try {
        val parseResults = getParseResults(tempCli, args)

        // Check if the command line arguments contain help or version options
        val helpOrVersion = parseResults.any { it.isUsageHelpRequested || it.isVersionHelpRequested }

        // for the listen command (not overridden by --help or --version)
        // enable the consumer and producer
        if (parseResults.any { it.commandSpec().userObject() is ListenCommand && !helpOrVersion }) {
            System.setProperty(CONSUMER_ENABLED, "true")
            System.setProperty(PRODUCER_ENABLED, "true")
        }

        // for the start command (not overridden by --help or --version)
        // enable the producer only
        if (parseResults.any { it.commandSpec().userObject() is InstanceStartCommand && !helpOrVersion }) {
            System.setProperty(PRODUCER_ENABLED, "true")
        }

        // Set the logging level
        setLoggingLevel(parseResults)

        // Set the config path
        setConfigPath(parseResults)

        // ... additional checks ...

    } catch (ex: Exception) {
        // Handle all exceptions in a unified way
        System.err.println("⚠️ ${ex.message}")
        exitProcess(1)
    }

    // --- Launch Quarkus ---
    Quarkus.run(LemlineApplication::class.java, *args)
}
```

> Note: all database sources (and corresponding Flyway instances) are active at Quarkus startup, but no connection are established until one is proactively requested. This is how it's possible to choose a database type at runtime.

#### Command-Specific Configuration

The CLI design automatically sets appropriate configuration based on the command:

1. **listen** command: Enables both message consumer and producer
   ```kotlin
   if (parseResults.any { it.commandSpec().userObject() is ListenCommand && !helpOrVersion }) {
       System.setProperty(CONSUMER_ENABLED, "true")
       System.setProperty(PRODUCER_ENABLED, "true")
   }
   ```

2. **instance start** command: Enables only the message producer
   ```kotlin
   if (parseResults.any { it.commandSpec().userObject() is InstanceStartCommand && !helpOrVersion }) {
       System.setProperty(PRODUCER_ENABLED, "true")
   }
   ```

#### Configuration File Discovery

The configuration file path is determined in the following order of precedence:

1. Command-line option: `--config=<path>` or `-c <path>`
2. Environment variable: `LEMLINE_CONFIG`
3. File in current directory: `.lemline.yaml`
4. XDG-compliant location: `~/.config/lemline/config.yaml`
5. Home directory: `~/.lemline.yaml`

```kotlin
private fun setConfigPath(parseResults: List<ParseResult>) {
    parseResults.forEach { parseResult ->
        val optionPath = parseResult.matchedOptionValue<String>("--config", null)
            ?: parseResult.matchedOptionValue<String>("-c", null)

        optionPath?.let {
            if (checkConfigLocation(Paths.get(it), true)) return
        }
    }

    // If not found via CLI, or if parseResult was null, check other sources:
    System.getenv("LEMLINE_CONFIG")?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (checkConfigLocation(Paths.get(it), true)) return
    }

    if (checkConfigLocation(Paths.get(".lemline.yaml"), false)) return

    System.getProperty("user.home")?.let {
        val xdgPath = Paths.get(it, ".config", "lemline", "config.yaml")
        if (checkConfigLocation(xdgPath, false)) return
        val homePath = Paths.get(it, ".lemline.yaml")
        if (checkConfigLocation(homePath, false)) return
    }
}
```

### Quarkus Startup Configuration

During Quarkus startup, the `LemlineConfigSourceFactory` transforms Lemline configuration into Quarkus-compatible properties:

```kotlin
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
class LemlineConfigSourceFactory : ConfigSourceFactory {
    override fun getConfigSources(context: ConfigSourceContext): Iterable<ConfigSource> {
        // Collect all properties from the context that start with "lemline."
        val lemlineProps = mutableMapOf<String, String>()
        for (name in context.iterateNames()) {
            if (name.startsWith("lemline.")) {
                // Retrieve the value for the property and add it to the map
                context.getValue(name)?.value?.let { lemlineProps[name] = it.split("#").first().trim() }
            }
        }

        // Add default values for consumer and producer enabled properties
        lemlineProps[CONSUMER_ENABLED] = System.getProperty(CONSUMER_ENABLED)
            ?: lemlineProps[CONSUMER_ENABLED] ?: "false"
        lemlineProps[PRODUCER_ENABLED] = System.getProperty(PRODUCER_ENABLED)
            ?: lemlineProps[PRODUCER_ENABLED] ?: "false"

        // Override properties from the config file, if any
        LemlineApplication.configPath?.let {
            log.debug { "Lemline config file location=$it" }
            ExtraFileConfigFactory().getConfig(it).properties.forEach { (name, value) ->
                if (name.startsWith("lemline.")) {
                    lemlineProps[name] = value.split("#").first().trim()
                } else {
                    log.info { "Skipping not lemline property $name" }
                }
            }
        }

        // Create a type-safe configuration and generate Quarkus properties
        // ...
    }
}
```

This factory runs at Quarkus's `BUILD_TIME` phase, ensuring configuration is available early in the initialization process.

#### Type-safe Configuration

The `LemlineConfiguration` interface defines a structured, type-safe configuration model:

```kotlin
@ConfigMapping(prefix = "lemline")
interface LemlineConfiguration {
    fun database(): DatabaseConfig
    fun messaging(): MessagingConfig
    // Other configuration sections...
}
```

This provides compile-time safety, validation, and auto-completion support.

### Runtime Configuration Management

#### Database Management

The `DatabaseManager` class provides runtime access to the configured database:

```kotlin
@ApplicationScoped
class DatabaseManager {
    @Inject
    @ConfigProperty(name = "lemline.database.type")
    internal lateinit var dbType: String

    @Inject
    @IfBuildProfile("test")
    private lateinit var h2DataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("postgresql")
    private lateinit var postgresDataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("mysql")
    private lateinit var mysqlDataSource: Instance<AgroalDataSource>

    val datasource: AgroalDataSource by lazy {
        // Choose datasource based on configuration
        when (dbType) {
            DB_TYPE_POSTGRESQL -> {
                if (postgresDataSource.isResolvable) postgresDataSource.get()
                else throw IllegalStateException("PostgreSQL datasource is not available.")
            }
            // ... other database types ...
        }
    }
    
    // Similar lazy initialization for Flyway
    val flyway: Flyway by lazy {
        // Choose Flyway instance based on configuration
        // ...
    }
}
```

This lazy initialization approach ensures the correct database implementation is used at runtime based on configuration.

#### Database Migration Control

The `FlywayMigration` class controls when migrations are executed:

```kotlin
@ApplicationScoped
class FlywayMigration(
    @ConfigProperty(name = "quarkus.profile")
    val profile: String,

    @ConfigProperty(name = "lemline.database.type")
    val db: String,

    @ConfigProperty(name = "lemline.database.migrate-at-start")
    val migrateAtStart: Boolean,
) {
    @Inject
    lateinit var databaseManager: DatabaseManager

    fun onStart(@Observes event: StartupEvent) {
        // Run migrations:
        // - if the profile is "test" - as databases are recreated for each test
        // - if the database type is in-memory - as it is provided by the app
        // - if migrateAtStart is true - as it is requested by the user
        if (profile == "test" || db == DB_TYPE_IN_MEMORY || migrateAtStart) {
            // migrate custom Flyway
            databaseManager.flyway.migrate()
            log.info("Flyway migrations applied successfully on ${databaseManager.dbType} database.")
        }
    }
}
```

This ensures migrations only run when appropriate, based on configuration and application context.

#### Messaging Consumer Management

The `MessageConsumer` class activates or deactivates the message consumer based on configuration:

```kotlin
@Startup
@ApplicationScoped
internal class MessageConsumer @Inject constructor(
    @Channel(WORKFLOW_IN) private val messages: Multi<String>,
    @Channel(WORKFLOW_OUT) private val emitter: Emitter<String>,
    @ConfigProperty(name = CONSUMER_ENABLED) private val enabled: Boolean,
    // ...
) {
    @PostConstruct
    fun init() {
        if (enabled) {
            messages.subscribe().with { consume(it) }
            logger.info { "✅ Consumer enabled" }
        } else {
            logger.info { "❌ Consumer disabled" }
        }
    }
    
    // ... message processing methods ...
}
```

This runtime check allows the message consumer to be selectively enabled or disabled based on configuration, even in native mode.

### Type-safe Configuration

The configuration is defined using Quarkus `@ConfigMapping`:

```kotlin
@ConfigMapping(prefix = "lemline")
interface LemlineConfiguration {
    fun database(): DatabaseConfig
    fun messaging(): MessagingConfig
    // Other configuration sections...
}

interface DatabaseConfig {
    @Pattern(regexp = "in-memory|postgresql|mysql")
    fun type(): String
    
    @WithDefault("false")
    fun migrateAtStart(): Boolean
    
    fun postgresql(): PostgreSQLConfig
    fun mysql(): MySQLConfig
}
```

### Configuration Source Factory

The configuration is processed by `LemlineConfigSourceFactory`:

```kotlin
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
class LemlineConfigSourceFactory : ConfigSourceFactory {
    override fun getConfigSources(context: ConfigSourceContext): Iterable<ConfigSource> {
        // Collect Lemline properties
        val lemlineProps = mutableMapOf<String, String>()
        
        // Add properties from context
        for (name in context.iterateNames()) {
            if (name.startsWith("lemline.")) {
                context.getValue(name)?.value?.let { 
                    lemlineProps[name] = it.split("#").first().trim() 
                }
            }
        }
        
        // Load from config file if specified
        LemlineApplication.configPath?.let {
            ExtraFileConfigFactory().getConfig(it).properties.forEach { (name, value) ->
                if (name.startsWith("lemline.")) {
                    lemlineProps[name] = value.split("#").first().trim()
                }
            }
        }
        
        // Transform into Quarkus properties
        val quarkusPropsList = mutableListOf<ConfigSource>()
        
        // Process database configuration
        processDatabaseConfig(lemlineProps)?.let { 
            quarkusPropsList.add(it) 
        }
        
        // Process messaging configuration
        processMessagingConfig(lemlineProps)?.let { 
            quarkusPropsList.add(it) 
        }
        
        return quarkusPropsList
    }
}
```

### Runtime Configuration Management

The `DatabaseManager` provides runtime access to the configured database:

```kotlin
@ApplicationScoped
class DatabaseManager {
    @Inject
    @ConfigProperty(name = "lemline.database.type")
    internal lateinit var dbType: String

    @Inject
    @IfBuildProfile("test")
    private lateinit var h2DataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("postgresql")
    private lateinit var postgresDataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("mysql")
    private lateinit var mysqlDataSource: Instance<AgroalDataSource>

    val datasource: AgroalDataSource by lazy {
        // Choose datasource based on configuration
        when (dbType) {
            DB_TYPE_POSTGRESQL -> {
                if (postgresDataSource.isResolvable) postgresDataSource.get()
                else throw IllegalStateException("PostgreSQL datasource is not available.")
            }
            // ... other database types ...
        }
    }
}
```

## Troubleshooting

### Common Configuration Issues

- **Missing configuration**: Check that all required properties are set
- **Invalid values**: Ensure all values meet the constraints (@Pattern, etc.)
- **Startup errors**: Check logs for configuration validation errors

### Configuration Validation

Lemline validates configuration at startup. Error messages will indicate:

- Missing required properties
- Invalid values (e.g., database type not in allowed list)
- Properties with invalid format

### Debugging Configuration

To see all active configuration properties:

```bash
java -jar lemline-runner.jar --config-dump
```

For more detailed configuration processing:

```bash
java -jar lemline-runner.jar -Dquarkus.log.category."io.quarkus.config".level=DEBUG
```

## Best Practices

1. **Use YAML for complex configuration**: YAML is more readable for nested structures
2. **Use environment variables for secrets**: Don't store passwords in configuration files
3. **Keep database credentials separate**: Use environment variables for database credentials
4. **Use profiles for different environments**: Development, testing, and production
5. **Validate configuration at startup**: Enable `quarkus.configuration.validation=true`

## Configuration Reference

| Property                             | Type    | Default   | Description                                  |
|--------------------------------------|---------|-----------|----------------------------------------------|
| `lemline.database.type`              | string  | in-memory | Database type (in-memory, postgresql, mysql) |
| `lemline.database.migrate-at-start`  | boolean | false     | Whether to run migrations at startup         |
| `lemline.database.postgresql.host`   | string  | localhost | PostgreSQL host                              |
| `lemline.database.postgresql.port`   | int     | 5432      | PostgreSQL port                              |
| ...                                  | ...     | ...       | ...                                          |
| `lemline.messaging.type`             | string  | in-memory | Messaging type (in-memory, kafka, rabbitmq)  |
| `lemline.messaging.consumer.enabled` | boolean | false     | Whether to enable the message consumer       |
| `lemline.messaging.producer.enabled` | boolean | false     | Whether to enable the message producer       |
| ...                                  | ...     | ...       | ...                                          |
