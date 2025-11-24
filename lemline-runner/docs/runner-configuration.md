# Lemline Runner - Configuration

This document covers the configuration system for the lemline-runner module.

## Configuration File Search Order

Lemline searches for configuration files in the following order (first found wins):

1. **CLI argument**: `--config=<path>` or `-c <path>`
2. **Environment variable**: `LEMLINE_CONFIG`
3. **Current directory**: `.lemline.yaml`
4. **XDG config**: `~/.config/lemline/config.yaml`
5. **Home directory**: `~/.lemline.yaml`

**Key file**: [LemlineApplication.kt](../src/main/kotlin/com/lemline/runner/cli/LemlineApplication.kt)

---

## Key Configuration Files

| File | Purpose |
|------|---------|
| [LemlineConfiguration.kt](../src/main/kotlin/com/lemline/runner/config/LemlineConfiguration.kt) | Type-safe configuration mapping |
| [LemlineConfigSource.kt](../src/main/kotlin/com/lemline/runner/config/LemlineConfigSource.kt) | Property transformation |
| [LemlineConfigConstants.kt](../src/main/kotlin/com/lemline/runner/config/LemlineConfigConstants.kt) | Default values |
| [ExtraFileConfigFactory.kt](../src/main/kotlin/com/lemline/runner/config/ExtraFileConfigFactory.kt) | YAML/properties loader |

---

## Configuration Transformation

Lemline uses a custom `ConfigSource` (ordinal 275) that transforms `lemline.*` properties into Quarkus-specific properties at runtime.

**Transformation flow:**

```
lemline.yaml (user config)
    ↓
LemlineConfigSource reads lemline.* properties
    ↓
Auto-detects database type (postgresql/mysql) and messaging type (kafka/rabbitmq)
    ↓
Generates quarkus.datasource.* and mp.messaging.* properties
    ↓
Quarkus runtime uses generated properties
```

---

## Supported Infrastructure Types

| Component | Types                                   | Auto-detection                           |
|-----------|-----------------------------------------|------------------------------------------|
| Database  | `in-memory` (H2), `postgresql`, `mysql` | Presence of `lemline.database.<type>.*`  |
| Messaging | `in-memory`, `kafka`, `rabbitmq`        | Presence of `lemline.messaging.<type>.*` |

---

## Example Configuration

### Full Example

```yaml
lemline:
    database:
        postgresql:
            host: localhost
            port: 5432
            username: postgres
            password: ${LEMLINE_PG_PASSWORD}
            name: lemline

    messaging:
        kafka:
            brokers: localhost:9092
            topic: lemline
            group-id: lemline-worker-group
```

### PostgreSQL + Kafka

```yaml
lemline:
    database:
        postgresql:
            host: db.example.com
            port: 5432
            username: lemline
            password: ${DB_PASSWORD}
            name: lemline_prod

    messaging:
        kafka:
            brokers: kafka1:9092,kafka2:9092,kafka3:9092
            topic: lemline-workflows
            group-id: lemline-workers
```

### MySQL + RabbitMQ

```yaml
lemline:
    database:
        mysql:
            host: mysql.example.com
            port: 3306
            username: lemline
            password: ${DB_PASSWORD}
            name: lemline_prod

    messaging:
        rabbitmq:
            host: rabbitmq.example.com
            port: 5672
            username: lemline
            password: ${RABBITMQ_PASSWORD}
            virtual-host: /lemline
```

### In-Memory (Development/Testing)

```yaml
lemline:
    database:
        type: in-memory

    messaging:
        type: in-memory
```

---

## Database Configuration

### PostgreSQL

```yaml
lemline:
    database:
        postgresql:
            host: localhost        # Required
            port: 5432             # Default: 5432
            username: postgres     # Required
            password: secret       # Required
            name: lemline          # Required (database name)
```

### MySQL

```yaml
lemline:
    database:
        mysql:
            host: localhost        # Required
            port: 3306             # Default: 3306
            username: root         # Required
            password: secret       # Required
            name: lemline          # Required (database name)
```

### H2 In-Memory

```yaml
lemline:
    database:
        type: in-memory
```

---

## Messaging Configuration

### Kafka

```yaml
lemline:
    messaging:
        kafka:
            brokers: localhost:9092     # Required (comma-separated)
            topic: lemline              # Default: lemline
            group-id: lemline-workers   # Default: lemline-worker-group
```

### RabbitMQ

```yaml
lemline:
    messaging:
        rabbitmq:
            host: localhost             # Required
            port: 5672                  # Default: 5672
            username: guest             # Required
            password: guest             # Required
            virtual-host: /             # Default: /
```

### In-Memory

```yaml
lemline:
    messaging:
        type: in-memory
```

---

## Environment Variable Substitution

Configuration values support environment variable substitution using `${VAR_NAME}` syntax:

```yaml
lemline:
    database:
        postgresql:
            password: ${LEMLINE_PG_PASSWORD}
```

---

## Adding a New Database Type

1. Add JDBC driver dependency to `lemline-runner/build.gradle.kts`
2. Create migration scripts in `resources/db/migration/{database}/`
3. Update `DatabaseConfig` in [LemlineConfiguration.kt](../src/main/kotlin/com/lemline/runner/config/LemlineConfiguration.kt)
4. Update `toQuarkusProperties()` method
5. Add test profile and test resources
6. Test all repositories

---

## Adding a New Message Broker

See ADR-0003 for detailed steps:

1. Add SmallRye connector dependency
2. Update [LemlineConfigConstants.kt](../src/main/kotlin/com/lemline/runner/config/LemlineConfigConstants.kt)
3. Add broker-specific config interface
4. Update `MessagingConfig.toQuarkusProperties()`
5. Create test profile and test resources

---

## Viewing Current Configuration

Use the CLI to view the resolved configuration:

```bash
# Show lemline configuration as YAML
lemline config

# Show as properties format
lemline config -f properties

# Show all Quarkus properties (includes generated properties)
lemline config -a
```
