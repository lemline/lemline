# Configuration System

## Overview

Lemline's configuration system is designed to be flexible, type-safe, and support multiple deployment scenarios. Key
features include:

- Type-safe configuration with Quarkus `@ConfigMapping`
- Support for multiple runtime environments (dev, test, production)
- Multiple database backends (in-memory, PostgreSQL, MySQL)
- Multiple messaging systems (in-memory, Kafka, RabbitMQ)
- External configuration files for deployment-specific settings
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
│   ├── consumer:
│   │   └── enabled: true
│   ├── producer:
│   │   └── enabled: true
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

Configuration is loaded from multiple sources in the following order (highest precedence first):

1. System properties (command line arguments)
2. Environment variables
3. External configuration file (if specified)
4. Application properties (application.properties, application.yaml)
5. Default values

## How to Configure Lemline

### Using Command Line Arguments

```bash
java -jar lemline-runner.jar \
  -Dlemline.database.type=mysql \
  -Dlemline.database.mysql.host=db.example.com
```

### Using Environment Variables

```bash
LEMLINE_DATABASE_TYPE=mysql \
LEMLINE_DATABASE_MYSQL_HOST=db.example.com \
java -jar lemline-runner.jar
```

### Using External Configuration Files

Create a YAML file (e.g., `config.yaml`):

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

### Configuration File Discovery

The configuration file is searched in the following order:

1. Command-line option: `--config=<path>` or `-c <path>`
2. Environment variable: `LEMLINE_CONFIG`
3. File in current directory: `.lemline.yaml`
4. XDG-compliant location: `~/.config/lemline/config.yaml`
5. Home directory: `~/.lemline.yaml`

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
