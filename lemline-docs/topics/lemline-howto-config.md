# Configuring the Lemline Runner

This guide explains how to configure the Lemline runtime environment to meet your specific requirements.

## Configuration Overview

Lemline uses a layered configuration approach that allows you to customize its behavior through:

1. **Configuration files**: `application.properties` or `application.yaml`
2. **Environment variables**: Override settings for different environments
3. **Command line arguments**: Override specific settings at runtime
4. **External configuration sources**: Load configuration from external systems

## Core Configuration File

The main configuration file can be placed in several locations (in order of precedence):

1. Current directory: `./application.properties`
2. `config` subdirectory: `./config/application.properties`
3. User's home directory: `~/application.properties`
4. System config directory: `/etc/lemline/application.properties`

Example `application.properties` file:

```properties
# Core configuration
lemline.name=My Workflow Engine
lemline.data-dir=/var/lib/lemline/data

# Database configuration
lemline.database.type=postgresql
lemline.database.host=localhost
lemline.database.port=5432
lemline.database.name=lemline
lemline.database.username=${DB_USERNAME}
lemline.database.password=${DB_PASSWORD}

# Messaging configuration
lemline.messaging.type=kafka
lemline.messaging.bootstrap.servers=localhost:9092
lemline.messaging.group.id=lemline-group

# HTTP client configuration
lemline.http.connect-timeout=PT5S
lemline.http.read-timeout=PT30S
lemline.http.max-connections=100
```

## YAML Configuration

You can also use YAML format for more structured configuration:

```yaml
lemline:
  name: My Workflow Engine
  data-dir: /var/lib/lemline/data
  
  database:
    type: postgresql
    host: localhost
    port: 5432
    name: lemline
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  
  messaging:
    type: kafka
    bootstrap:
      servers: localhost:9092
    group:
      id: lemline-group
  
  http:
    connect-timeout: PT5S
    read-timeout: PT30S
    max-connections: 100
```

## Environment Variables

All configuration properties can be overridden with environment variables using the following pattern:

1. Convert the property name to uppercase
2. Replace dots (`.`) with underscores (`_`)
3. Prefix with `LEMLINE_`

Examples:

| Property                        | Environment Variable              |
|---------------------------------|-----------------------------------|
| `lemline.database.host`        | `LEMLINE_DATABASE_HOST`           |
| `lemline.messaging.type`       | `LEMLINE_MESSAGING_TYPE`          |
| `lemline.http.max-connections` | `LEMLINE_HTTP_MAX_CONNECTIONS`    |

## Command Line Arguments

Override configuration at runtime using command line arguments:

```bash
lemline instances start \
  --workflowId=order-processor \
  -Dlemline.database.host=prod-db.example.com \
  -Dlemline.messaging.bootstrap.servers=prod-kafka.example.com:9092
```

## Configuration Profiles

Lemline supports configuration profiles to manage environment-specific settings:

```properties
# Default settings
lemline.database.host=localhost

# Development profile settings
%dev.lemline.database.host=dev-db.example.com

# Production profile settings
%prod.lemline.database.host=prod-db.example.com
%prod.lemline.http.connect-timeout=PT10S
```

Activate a profile using:

```bash
lemline instances start --workflowId=order-processor -Dquarkus.profile=prod
```

## External Configuration Sources

Lemline can load configuration from external sources:

### ConfigMap/Secret (Kubernetes)

```properties
# Enable Kubernetes config source
quarkus.kubernetes-config.enabled=true
quarkus.kubernetes-config.config-maps=lemline-config
quarkus.kubernetes-config.secrets=lemline-secrets
```

### Vault (HashiCorp)

```properties
# Enable Vault config source
quarkus.vault.url=https://vault.example.com:8200
quarkus.vault.authentication.client-token=${VAULT_TOKEN}
quarkus.vault.secret-config-kv-path=lemline
```

## Configuration Categories

### Database Configuration

```properties
# Database type (postgresql, mysql, h2)
lemline.database.type=postgresql

# Connection details
lemline.database.host=localhost
lemline.database.port=5432
lemline.database.name=lemline
lemline.database.username=${DB_USERNAME}
lemline.database.password=${DB_PASSWORD}

# Connection pool settings
lemline.database.min-pool-size=5
lemline.database.max-pool-size=20
lemline.database.idle-timeout=PT30M
```

### Messaging Configuration

```properties
# Messaging system type (kafka, rabbitmq)
lemline.messaging.type=kafka

# Kafka settings (when type=kafka)
lemline.messaging.bootstrap.servers=localhost:9092
lemline.messaging.group.id=lemline-group
lemline.messaging.auto.offset.reset=earliest

# RabbitMQ settings (when type=rabbitmq)
lemline.messaging.rabbitmq.host=localhost
lemline.messaging.rabbitmq.port=5672
lemline.messaging.rabbitmq.username=${RABBITMQ_USER}
lemline.messaging.rabbitmq.password=${RABBITMQ_PASSWORD}
```

### HTTP Client Configuration

```properties
# Timeouts
lemline.http.connect-timeout=PT5S
lemline.http.read-timeout=PT30S
lemline.http.call-timeout=PT60S

# Connection pool
lemline.http.max-connections=100
lemline.http.max-connections-per-route=20
lemline.http.keep-alive=true
lemline.http.idle-timeout=PT60S

# Proxy settings
lemline.http.proxy.host=proxy.example.com
lemline.http.proxy.port=8080
lemline.http.proxy.username=${PROXY_USER}
lemline.http.proxy.password=${PROXY_PASSWORD}
lemline.http.non-proxy-hosts=localhost|*.example.com
```

### Logging Configuration

```properties
# Log levels
quarkus.log.level=INFO
quarkus.log.category."com.lemline".level=DEBUG
quarkus.log.category."com.lemline.core.execution".level=INFO
quarkus.log.category."org.apache.http".level=WARN

# Log file
quarkus.log.file.enable=true
quarkus.log.file.path=/var/log/lemline/lemline.log
quarkus.log.file.rotation.max-file-size=10M
quarkus.log.file.rotation.max-backup-index=5

# JSON formatting for production
%prod.quarkus.log.console.json=true
```

### Secret Management

```properties
# Secret source (file, env, vault)
lemline.secrets.source=file
lemline.secrets.file.path=/etc/lemline/secrets.properties

# Secret caching
lemline.secrets.cache.enabled=true
lemline.secrets.cache.ttl=PT15M
```

### Performance Tuning

```properties
# Thread pools
lemline.execution.pool.core-size=10
lemline.execution.pool.max-size=50
lemline.execution.pool.queue-size=100
lemline.execution.pool.keep-alive=PT60S

# Caching
lemline.cache.workflow-definitions.enabled=true
lemline.cache.workflow-definitions.max-size=100
lemline.cache.workflow-definitions.ttl=PT15M

# Execution limits
lemline.execution.max-parallel-workflows=100
lemline.execution.max-execution-time=PT10M
```

## Configuration Validation

Lemline validates your configuration at startup. Invalid configuration will cause startup to fail with clear error messages.

You can validate configuration without starting the service:

```bash
lemline config validate -Dlemline.database.type=postgresql
```

## Loading External Configuration Files

Load specific configuration files at runtime:

```bash
lemline instances start --workflowId=order-processor \
  -Dquarkus.config.locations=/path/to/custom-config.properties
```

## Best Practices

1. **Use profiles**: Organize environment-specific configurations using profiles
2. **Externalize secrets**: Keep sensitive values out of configuration files
3. **Use placeholders**: Use `${ENV_VAR}` syntax for environment-specific values
4. **Override selectively**: Override only what's necessary for each environment
5. **Version control**: Keep configuration in version control, except for secrets
6. **Document defaults**: Document default values and configuration options
7. **Validate changes**: Test configuration changes before deploying to production

## Related Resources

- [Environment Variables Reference](lemline-ref-env-vars.md)
- [Secrets Management](lemline-howto-secrets.md)
- [Database Configuration](lemline-howto-brokers.md)
- [Runner Configuration Reference](lemline-ref-config.md)