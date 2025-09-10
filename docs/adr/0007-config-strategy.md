# [ADR-0007] Configuration Strategy

## Status

Accepted

## Context

The Lemline project requires a flexible configuration system that can support:

1. Multiple runtime environments (development, testing, production)
2. Multiple database backends (in-memory, PostgreSQL, MySQL)
3. Multiple messaging systems (in-memory, Kafka, RabbitMQ)
4. External configuration files for deployment-specific settings
5. Proper initialization order, especially for database resources and migrations

Quarkus provides a robust configuration system, but it follows specific conventions and initialization phases that must be carefully managed to ensure correct application startup, particularly when dealing with database connections and migrations.

## Decision

We have implemented a comprehensive configuration strategy with the following components:

1. **Type-safe Configuration Mapping**: Using Quarkus `@ConfigMapping` for strong typing and validation
2. **Custom Configuration Source Factory**: Transforming Lemline-specific properties into Quarkus-compatible format
3. **External Configuration File Support**: Loading configuration from external YAML/properties files
4. **Database Configuration Manager**: Selecting and managing database resources based on configuration
5. **Controlled Migration Process**: Managing database migrations during application startup

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

### Configuration Hierarchy

The configuration is processed in this order (highest precedence first):

1. System properties (command line arguments)
2. Environment variables
3. External configuration file (if specified)
4. Application properties (application.properties, application.yaml)
5. Default values

### Pre-Quarkus CLI Processing

Before Quarkus starts, the `LemlineApplication` class processes command-line arguments to set critical configuration:

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

## Using External Configuration Files

To use an external configuration file:

1. Create a YAML or properties file with your Lemline configuration
2. Launch the application with:
   ```
   java -jar lemline-runner.jar --config=/path/to/config.yaml
   ```
   or
   ```
   java -Dlemline.config=/path/to/config.yaml -jar lemline-runner.jar
   ```
3. Or set the environment variable:
   ```
   LEMLINE_CONFIG=/path/to/config.yaml java -jar lemline-runner.jar
   ```

Example configuration file:
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
    producer:
      enabled: true
    consumer:
      enabled: true
    kafka:
      brokers: kafka.example.com:9092
      topic: workflows-in
```

## Message Consumer and Producer Activation

Lemline supports selectively enabling or disabling message consumers and producers through various methods:

### Activation Methods

1. **CLI Commands**:
   - The `listen` command automatically enables both consumer and producer
   - The `instance start` command automatically enables only the producer
   - Other commands leave both disabled by default

2. **System Properties**:
   ```
   java -Dlemline.messaging.consumer.enabled=true -Dlemline.messaging.producer.enabled=false -jar lemline-runner.jar
   ```

3. **Environment Variables**:
   ```
   LEMLINE_MESSAGING_CONSUMER_ENABLED=true LEMLINE_MESSAGING_PRODUCER_ENABLED=false java -jar lemline-runner.jar
   ```

4. **Configuration File**:
   ```yaml
   lemline:
     messaging:
       consumer:
         enabled: true
       producer:
         enabled: false
   ```

## Native Executable Support

A key advantage of this configuration approach is its compatibility with Quarkus native compilation. In a native executable:

1. **Pre-Quarkus Processing**: Still processes CLI arguments and sets system properties
2. **Database Selection**: Can select between database types at runtime
3. **Consumer Activation**: Can enable/disable message consumers at runtime
4. **Configuration Files**: Can still load external configuration files

This runtime flexibility is achieved through:
- Using lazy initialization for database and Flyway resources
- Checking configuration values at runtime in `@PostConstruct` methods
- Avoiding reflection-based configuration by using explicit @ConfigMapping

## Consequences

### Positive

- **Type Safety**: Strong typing and validation prevent configuration errors
- **Flexibility**: Support for multiple environments and deployment scenarios
- **Extensibility**: Easy to add new configuration sections or backend systems
- **Controlled Initialization**: Proper order of database setup and migrations
- **Runtime Configuration**: Ability to configure components at runtime even in native mode
- **Command-Driven Settings**: Automatic configuration based on the intended use case

### Negative

- **Complexity**: The configuration system is more complex than standard Quarkus configuration
- **Learning Curve**: Understanding the configuration transformation requires time
- **Maintenance**: The configuration system may need updates with new Quarkus versions

