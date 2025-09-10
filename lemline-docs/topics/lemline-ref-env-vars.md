# Environment Variables Reference

This reference documents all environment variables supported by the Lemline runner.

## Environment Variable Mapping

Lemline configuration properties can be set via environment variables using a standard transformation:

1. Convert the property name to uppercase
2. Replace dots (`.`) with underscores (`_`)
3. Add the `LEMLINE_` prefix for Lemline-specific properties
4. For Quarkus properties, add the `QUARKUS_` prefix

## Core Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_NAME` | "Lemline" | Instance name, useful when running multiple instances |
| `LEMLINE_DATA_DIR` | "./data" | Directory for storing persistent data |
| `LEMLINE_TEMP_DIR` | System temp | Directory for temporary files |
| `LEMLINE_CONFIG_DIR` | "./config" | Directory for configuration files |
| `LEMLINE_NODE_ID` | Generated UUID | Unique identifier for this node |
| `LEMLINE_CLUSTER_ENABLED` | "false" | Enable cluster mode ("true" or "false") |
| `LEMLINE_CLUSTER_NAME` | "lemline-cluster" | Cluster name for node discovery |

## Database Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_DATABASE_TYPE` | "h2" | Database type: "postgresql", "mysql", or "h2" |
| `LEMLINE_DATABASE_HOST` | "localhost" | Database host |
| `LEMLINE_DATABASE_PORT` | Depends on type | Database port |
| `LEMLINE_DATABASE_NAME` | "lemline" | Database name |
| `LEMLINE_DATABASE_USERNAME` | | Database username |
| `LEMLINE_DATABASE_PASSWORD` | | Database password |
| `LEMLINE_DATABASE_SCHEMA` | Public/default | Database schema name |
| `LEMLINE_DATABASE_MIN_POOL_SIZE` | "5" | Minimum connection pool size |
| `LEMLINE_DATABASE_MAX_POOL_SIZE` | "20" | Maximum connection pool size |
| `LEMLINE_DATABASE_CONNECTION_TIMEOUT` | "PT30S" | Connection acquisition timeout |
| `LEMLINE_DATABASE_IDLE_TIMEOUT` | "PT10M" | Connection idle timeout |
| `LEMLINE_DATABASE_MAX_LIFETIME` | "PT30M" | Maximum connection lifetime |
| `LEMLINE_DATABASE_AUTO_MIGRATION` | "true" | Automatically run database migrations |
| `LEMLINE_DATABASE_MIGRATION_LOCATIONS` | "db/migration" | Location of migration scripts |

## Messaging Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_MESSAGING_TYPE` | "memory" | Messaging type: "kafka", "rabbitmq", or "memory" |
| `LEMLINE_MESSAGING_DEFAULT_TIMEOUT` | "PT1M" | Default message processing timeout |
| `LEMLINE_MESSAGING_DEAD_LETTER_ENABLED` | "true" | Enable dead letter queue for failed messages |
| `LEMLINE_MESSAGING_ERROR_HANDLER` | "log" | Error handler: "log", "dlq", or "retry" |
| `LEMLINE_MESSAGING_RETRY_ATTEMPTS` | "3" | Number of retry attempts for failed messages |
| `LEMLINE_MESSAGING_RETRY_DELAY` | "PT5S" | Delay between retry attempts |

### Kafka Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_MESSAGING_KAFKA_BOOTSTRAP_SERVERS` | "localhost:9092" | Kafka bootstrap servers |
| `LEMLINE_MESSAGING_KAFKA_CLIENT_ID` | | Kafka client ID |
| `LEMLINE_MESSAGING_KAFKA_GROUP_ID` | "lemline" | Kafka consumer group ID |
| `LEMLINE_MESSAGING_KAFKA_AUTO_OFFSET_RESET` | "earliest" | Offset reset strategy |
| `LEMLINE_MESSAGING_KAFKA_ENABLE_AUTO_COMMIT` | "false" | Enable auto commit |
| `LEMLINE_MESSAGING_KAFKA_AUTO_COMMIT_INTERVAL_MS` | "5000" | Auto commit interval |
| `LEMLINE_MESSAGING_KAFKA_SECURITY_PROTOCOL` | "PLAINTEXT" | Security protocol |
| `LEMLINE_MESSAGING_KAFKA_SASL_MECHANISM` | | SASL mechanism |
| `LEMLINE_MESSAGING_KAFKA_SASL_JAAS_CONFIG` | | JAAS configuration |

### RabbitMQ Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_MESSAGING_RABBITMQ_HOST` | "localhost" | RabbitMQ host |
| `LEMLINE_MESSAGING_RABBITMQ_PORT` | "5672" | RabbitMQ port |
| `LEMLINE_MESSAGING_RABBITMQ_USERNAME` | "guest" | RabbitMQ username |
| `LEMLINE_MESSAGING_RABBITMQ_PASSWORD` | "guest" | RabbitMQ password |
| `LEMLINE_MESSAGING_RABBITMQ_VIRTUAL_HOST` | "/" | RabbitMQ virtual host |
| `LEMLINE_MESSAGING_RABBITMQ_EXCHANGE` | "lemline" | Default exchange name |
| `LEMLINE_MESSAGING_RABBITMQ_DELIVERY_MODE` | "2" | Delivery mode (1=non-persistent, 2=persistent) |
| `LEMLINE_MESSAGING_RABBITMQ_CONNECTION_TIMEOUT` | "PT30S" | Connection timeout |
| `LEMLINE_MESSAGING_RABBITMQ_REQUESTED_HEARTBEAT` | "PT60S" | Heartbeat interval |
| `LEMLINE_MESSAGING_RABBITMQ_AUTOMATIC_RECOVERY_ENABLED` | "true" | Enable automatic connection recovery |
| `LEMLINE_MESSAGING_RABBITMQ_TOPOLOGY_RECOVERY_ENABLED` | "true" | Enable topology recovery |

## HTTP Client Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_HTTP_CONNECT_TIMEOUT` | "PT5S" | Connection establishment timeout |
| `LEMLINE_HTTP_READ_TIMEOUT` | "PT60S" | Socket read timeout |
| `LEMLINE_HTTP_WRITE_TIMEOUT` | "PT30S" | Socket write timeout |
| `LEMLINE_HTTP_CALL_TIMEOUT` | "PT90S" | Total request timeout |
| `LEMLINE_HTTP_FOLLOW_REDIRECTS` | "true" | Automatically follow redirects |
| `LEMLINE_HTTP_MAX_REDIRECTS` | "5" | Maximum number of redirects to follow |
| `LEMLINE_HTTP_MAX_CONNECTIONS` | "100" | Maximum number of concurrent connections |
| `LEMLINE_HTTP_MAX_CONNECTIONS_PER_ROUTE` | "20" | Maximum connections per route |
| `LEMLINE_HTTP_KEEP_ALIVE_TIME` | "PT5M" | Keep-alive duration |
| `LEMLINE_HTTP_USER_AGENT` | "Lemline/${version}" | User-Agent header value |
| `LEMLINE_HTTP_COMPRESSION_ENABLED` | "true" | Enable request/response compression |
| `LEMLINE_HTTP_TRACE_ENABLED` | "false" | Enable HTTP request/response tracing |
| `LEMLINE_HTTP_TRACE_LEVEL` | "HEADERS" | Trace level: "BASIC", "HEADERS", "BODY" |
| `LEMLINE_HTTP_CIRCUIT_BREAKER_ENABLED` | "true" | Enable circuit breaker |
| `LEMLINE_HTTP_CIRCUIT_BREAKER_FAILURE_THRESHOLD` | "0.5" | Failure ratio threshold |
| `LEMLINE_HTTP_CIRCUIT_BREAKER_REQUEST_VOLUME_THRESHOLD` | "20" | Minimum request volume |
| `LEMLINE_HTTP_CIRCUIT_BREAKER_DELAY` | "PT60S" | Circuit open duration |
| `LEMLINE_HTTP_PROXY_HOST` | | HTTP proxy host |
| `LEMLINE_HTTP_PROXY_PORT` | "8080" | HTTP proxy port |
| `LEMLINE_HTTP_PROXY_USERNAME` | | HTTP proxy username |
| `LEMLINE_HTTP_PROXY_PASSWORD` | | HTTP proxy password |
| `LEMLINE_HTTP_PROXY_NON_PROXY_HOSTS` | "localhost\|127.*\|[::1]" | Hosts to exclude from proxy |

## Workflow Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_WORKFLOW_DEFINITIONS_DIR` | "./workflows" | Directory for workflow definitions |
| `LEMLINE_WORKFLOW_MAX_EXECUTION_TIME` | "PT1H" | Default maximum workflow execution time |
| `LEMLINE_WORKFLOW_MAX_CONCURRENT_INSTANCES` | "100" | Maximum concurrent workflow instances |
| `LEMLINE_WORKFLOW_MAX_INSTANCES_PER_DEFINITION` | "20" | Maximum instances per workflow definition |
| `LEMLINE_WORKFLOW_AUTO_CLEANUP` | "true" | Automatically clean up completed workflows |
| `LEMLINE_WORKFLOW_CLEANUP_AFTER` | "P7D" | Time after completion to clean up workflows |
| `LEMLINE_WORKFLOW_INSTANCE_ID_PREFIX` | | Prefix for workflow instance IDs |
| `LEMLINE_WORKFLOW_CACHE_DEFINITIONS` | "true" | Cache workflow definitions |
| `LEMLINE_WORKFLOW_CACHE_MAX_SIZE` | "100" | Maximum size of definition cache |
| `LEMLINE_WORKFLOW_CACHE_EXPIRY` | "PT1H" | Definition cache entry expiry |
| `LEMLINE_WORKFLOW_VALIDATION_ENABLED` | "true" | Enable workflow definition validation |
| `LEMLINE_WORKFLOW_VALIDATION_STRICT` | "false" | Enable strict validation mode |
| `LEMLINE_WORKFLOW_STATE_PERSISTENCE` | "full" | State persistence: "full", "minimal", "checkpoints" |
| `LEMLINE_WORKFLOW_CHECKPOINT_INTERVAL` | "10" | Checkpoint every N state changes |
| `LEMLINE_WORKFLOW_EVENT_CORRELATION_TTL` | "PT1H" | Event correlation timeout |

## Execution Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_EXECUTION_EXECUTOR` | "thread-pool" | Executor type: "thread-pool", "virtual-thread", "project-reactor" |
| `LEMLINE_EXECUTION_THREAD_POOL_CORE_SIZE` | "10" | Core thread pool size |
| `LEMLINE_EXECUTION_THREAD_POOL_MAX_SIZE` | "50" | Maximum thread pool size |
| `LEMLINE_EXECUTION_THREAD_POOL_QUEUE_SIZE` | "100" | Work queue size |
| `LEMLINE_EXECUTION_THREAD_POOL_KEEP_ALIVE` | "PT60S" | Thread keep-alive time |
| `LEMLINE_EXECUTION_VIRTUAL_THREAD_MAX_PARALLELISM` | "1000" | Maximum virtual thread parallelism |
| `LEMLINE_EXECUTION_PRIORITY_ENABLED` | "false" | Enable priority-based scheduling |
| `LEMLINE_EXECUTION_PRIORITY_LEVELS` | "5" | Number of priority levels |
| `LEMLINE_EXECUTION_RETRY_MAX_ATTEMPTS` | "3" | Maximum retry attempts |
| `LEMLINE_EXECUTION_RETRY_DELAY` | "PT1S" | Initial retry delay |
| `LEMLINE_EXECUTION_RETRY_MAX_DELAY` | "PT1M" | Maximum retry delay |
| `LEMLINE_EXECUTION_RETRY_MULTIPLIER` | "2.0" | Retry delay multiplier |
| `LEMLINE_EXECUTION_RETRY_JITTER` | "0.1" | Retry delay jitter factor |

## Outbox Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_OUTBOX_ENABLED` | "true" | Enable outbox pattern |
| `LEMLINE_OUTBOX_POLLING_INTERVAL` | "PT2S" | Polling interval for outbox processor |
| `LEMLINE_OUTBOX_MAX_POLL_RECORDS` | "50" | Maximum records to process per poll |
| `LEMLINE_OUTBOX_PROCESSING_TIMEOUT` | "PT30S" | Maximum processing time for a record |
| `LEMLINE_OUTBOX_RETRY_ATTEMPTS` | "5" | Maximum retry attempts for failed processing |
| `LEMLINE_OUTBOX_RETRY_DELAY` | "PT5S" | Initial retry delay |
| `LEMLINE_OUTBOX_RETRY_MULTIPLIER` | "2.0" | Retry delay multiplier |
| `LEMLINE_OUTBOX_RETRY_MAX_DELAY` | "PT5M" | Maximum retry delay |
| `LEMLINE_OUTBOX_CLEANUP_INTERVAL` | "PT1H" | Cleanup interval for processed records |
| `LEMLINE_OUTBOX_RETENTION_TIME` | "P7D" | Retention time for processed records |

## Security Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_SECURITY_SECRETS_SOURCE` | "env" | Secrets source: "env", "file", "vault" |
| `LEMLINE_SECURITY_SECRETS_ENV_PREFIX` | "LEMLINE_SECRET_" | Environment variable prefix for secrets |
| `LEMLINE_SECURITY_SECRETS_FILE_PATH` | "./secrets.properties" | Path to secrets file |
| `LEMLINE_SECURITY_SECRETS_VAULT_URL` | | HashiCorp Vault URL |
| `LEMLINE_SECURITY_SECRETS_VAULT_TOKEN` | | HashiCorp Vault token |
| `LEMLINE_SECURITY_SECRETS_VAULT_PATH` | "secret/lemline" | HashiCorp Vault secrets path |
| `LEMLINE_SECURITY_SECRETS_REFRESH_ENABLED` | "false" | Enable periodic secrets refresh |
| `LEMLINE_SECURITY_SECRETS_REFRESH_INTERVAL` | "PT15M" | Secrets refresh interval |
| `LEMLINE_SECURITY_TLS_ENABLED` | "true" | Enable TLS for HTTP client |
| `LEMLINE_SECURITY_TLS_TRUSTSTORE_PATH` | | Path to truststore |
| `LEMLINE_SECURITY_TLS_TRUSTSTORE_PASSWORD` | | Truststore password |
| `LEMLINE_SECURITY_TLS_TRUSTSTORE_TYPE` | "JKS" | Truststore type |
| `LEMLINE_SECURITY_TLS_KEYSTORE_PATH` | | Path to keystore |
| `LEMLINE_SECURITY_TLS_KEYSTORE_PASSWORD` | | Keystore password |
| `LEMLINE_SECURITY_TLS_KEYSTORE_TYPE` | "PKCS12" | Keystore type |
| `LEMLINE_SECURITY_TLS_PROTOCOLS` | "TLSv1.2,TLSv1.3" | Enabled TLS protocols |
| `LEMLINE_SECURITY_TLS_VERIFY_HOSTNAME` | "true" | Verify hostname in TLS certificates |
| `LEMLINE_SECURITY_TLS_TRUSTALL` | "false" | Trust all certificates (insecure!) |

## Observability Environment Variables

### Logging Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `QUARKUS_LOG_LEVEL` | "INFO" | Root log level |
| `QUARKUS_LOG_CATEGORY__COM_LEMLINE_` | "INFO" | Log level for Lemline packages |
| `QUARKUS_LOG_CONSOLE_ENABLE` | "true" | Enable console logging |
| `QUARKUS_LOG_CONSOLE_FORMAT` | "%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c{2.}] (%t) %s%e%n" | Console log format |
| `QUARKUS_LOG_CONSOLE_JSON` | "false" | Use JSON format for console logs |
| `QUARKUS_LOG_FILE_ENABLE` | "false" | Enable file logging |
| `QUARKUS_LOG_FILE_PATH` | "lemline.log" | Log file path |
| `QUARKUS_LOG_FILE_ROTATION_MAX_FILE_SIZE` | "10M" | Maximum log file size |
| `QUARKUS_LOG_FILE_ROTATION_MAX_BACKUP_INDEX` | "5" | Maximum number of log backups |
| `LEMLINE_LOG_STRUCTURED` | "true" | Enable structured logging |
| `LEMLINE_LOG_WORKFLOW_ENABLED` | "true" | Enable workflow execution logging |
| `LEMLINE_LOG_WORKFLOW_LEVEL` | "INFO" | Workflow execution log level |
| `LEMLINE_LOG_HTTP_ENABLED` | "true" | Enable HTTP client logging |
| `LEMLINE_LOG_HTTP_LEVEL` | "INFO" | HTTP client log level |
| `LEMLINE_LOG_HTTP_INCLUDE_HEADERS` | "false" | Include headers in HTTP logs |
| `LEMLINE_LOG_HTTP_INCLUDE_BODY` | "false" | Include body in HTTP logs |
| `LEMLINE_LOG_MESSAGING_ENABLED` | "true" | Enable messaging logging |
| `LEMLINE_LOG_MESSAGING_LEVEL` | "INFO" | Messaging log level |

### Metrics Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_METRICS_ENABLED` | "true" | Enable metrics collection |
| `LEMLINE_METRICS_PREFIX` | "lemline" | Metrics name prefix |
| `LEMLINE_METRICS_WORKFLOW_ENABLED` | "true" | Enable workflow metrics |
| `LEMLINE_METRICS_HTTP_ENABLED` | "true" | Enable HTTP client metrics |
| `LEMLINE_METRICS_DATABASE_ENABLED` | "true" | Enable database metrics |
| `LEMLINE_METRICS_MESSAGING_ENABLED` | "true" | Enable messaging metrics |
| `QUARKUS_MICROMETER_ENABLED` | "true" | Enable Micrometer metrics |
| `QUARKUS_MICROMETER_REGISTRY_ENABLED_DEFAULT` | "true" | Enable default Micrometer registries |
| `QUARKUS_MICROMETER_EXPORT_PROMETHEUS_ENABLED` | "true" | Enable Prometheus metrics |
| `QUARKUS_MICROMETER_EXPORT_PROMETHEUS_PATH` | "/metrics" | Prometheus metrics endpoint |

### Tracing Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_TRACING_ENABLED` | "false" | Enable distributed tracing |
| `LEMLINE_TRACING_WORKFLOW_ENABLED` | "true" | Enable workflow execution tracing |
| `LEMLINE_TRACING_WORKFLOW_LEVEL` | "STANDARD" | Workflow trace level: "BASIC", "STANDARD", "DETAILED" |
| `LEMLINE_TRACING_HTTP_ENABLED` | "true" | Enable HTTP client tracing |
| `LEMLINE_TRACING_MESSAGING_ENABLED` | "true" | Enable messaging tracing |
| `QUARKUS_OPENTELEMETRY_ENABLED` | "false" | Enable OpenTelemetry |
| `QUARKUS_OPENTELEMETRY_TRACER_ENABLED` | "false" | Enable OpenTelemetry tracer |
| `QUARKUS_OPENTELEMETRY_TRACER_EXPORTER_OTLP_ENABLED` | "false" | Enable OTLP exporter |
| `QUARKUS_OPENTELEMETRY_TRACER_EXPORTER_OTLP_ENDPOINT` | "http://localhost:4317" | OTLP endpoint |

### Health Checks Environment Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_HEALTH_ENABLED` | "true" | Enable health checks |
| `LEMLINE_HEALTH_DATABASE_ENABLED` | "true" | Enable database health check |
| `LEMLINE_HEALTH_MESSAGING_ENABLED` | "true" | Enable messaging health check |
| `LEMLINE_HEALTH_DISK_SPACE_ENABLED` | "true" | Enable disk space health check |
| `LEMLINE_HEALTH_DISK_SPACE_THRESHOLD` | "100MB" | Disk space threshold |
| `QUARKUS_HEALTH_EXTENSIONS_ENABLED` | "true" | Enable Quarkus health extensions |
| `QUARKUS_HEALTH_OPENAPI_INCLUDED` | "true" | Include OpenAPI in health endpoint |
| `QUARKUS_SMALLRYE_HEALTH_ROOT_PATH` | "/health" | Health endpoint path |

## Secrets Environment Variables

When using the environment variable secrets source (`LEMLINE_SECURITY_SECRETS_SOURCE=env`), secrets are read from environment variables with the prefix defined by `LEMLINE_SECURITY_SECRETS_ENV_PREFIX` (default: `LEMLINE_SECRET_`).

Examples:

| Environment Variable | Description |
|---------------------|-------------|
| `LEMLINE_SECRET_API_KEY` | Secret named "api.key" |
| `LEMLINE_SECRET_DB_PASSWORD` | Secret named "db.password" |
| `LEMLINE_SECRET_OAUTH_CLIENT_SECRET` | Secret named "oauth.client.secret" |

## Profile Environment Variables

The active profile can be set using:

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `QUARKUS_PROFILE` | | Active profile: "dev", "test", "prod" |

## Native Image Environment Variables

When running the native executable:

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `JAVA_OPTS` | | JVM options for native image |
| `JAVA_OPTS_APPEND` | | Additional JVM options |
| `QUARKUS_LOG_FILE_ENABLE` | "false" | Enable file logging |
| `QUARKUS_HTTP_PORT` | "8080" | HTTP server port |
| `QUARKUS_HTTP_HOST` | "0.0.0.0" | HTTP server host |

## Container Environment Variables

When running in containers, additional variables apply:

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_CONFIG_LOCATIONS` | | Comma-separated list of external config locations |
| `LEMLINE_DATABASE_URL` | | JDBC URL (alternative to separate host/port/name) |
| `LEMLINE_HTTP_PROXY` | | HTTP proxy URL (alternative to separate host/port) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | | OpenTelemetry collector endpoint |
| `OTEL_SERVICE_NAME` | "lemline" | OpenTelemetry service name |
| `OTEL_RESOURCE_ATTRIBUTES` | | OpenTelemetry resource attributes |

## Environment Variable Usage Example

```bash
# Basic configuration
export LEMLINE_NAME="OrderProcessor"
export LEMLINE_DATA_DIR="/data/lemline"

# Database configuration
export LEMLINE_DATABASE_TYPE="postgresql"
export LEMLINE_DATABASE_HOST="postgres.example.com"
export LEMLINE_DATABASE_PORT="5432"
export LEMLINE_DATABASE_NAME="lemline_prod"
export LEMLINE_DATABASE_USERNAME="lemline_user"
export LEMLINE_DATABASE_PASSWORD="secret-password"

# Messaging configuration
export LEMLINE_MESSAGING_TYPE="kafka"
export LEMLINE_MESSAGING_KAFKA_BOOTSTRAP_SERVERS="kafka-1:9092,kafka-2:9092"
export LEMLINE_MESSAGING_KAFKA_GROUP_ID="lemline-prod"

# Logging configuration
export QUARKUS_LOG_LEVEL="INFO"
export QUARKUS_LOG_FILE_ENABLE="true"
export QUARKUS_LOG_FILE_PATH="/var/log/lemline/lemline.log"
export QUARKUS_LOG_CONSOLE_JSON="true"

# Set active profile
export QUARKUS_PROFILE="prod"

# Secrets
export LEMLINE_SECRET_API_KEY="your-api-key"
export LEMLINE_SECRET_OAUTH_CLIENT_SECRET="oauth-client-secret"
```

## Related Resources

- [Configuring the Lemline Runner](lemline-howto-config.md)
- [Runner Configuration Reference](lemline-ref-config.md)
- [Secrets Management](lemline-howto-secrets.md)
- [Database Configuration](lemline-howto-brokers.md)