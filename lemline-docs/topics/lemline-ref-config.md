# Runner Configuration Reference

This reference documents all configuration properties available in the Lemline runner.

## Configuration Structure

Lemline's configuration is organized into these main categories:

1. **Core**: General system settings
2. **Database**: Database connection settings
3. **Messaging**: Event broker configuration
4. **HTTP**: HTTP client settings
5. **Workflow**: Workflow execution configuration
6. **Security**: Security-related configuration
7. **Observability**: Logging, metrics, and tracing

## Core Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.name` | String | "Lemline" | Instance name, useful when running multiple instances |
| `lemline.data-dir` | String | "./data" | Directory for storing persistent data |
| `lemline.temp-dir` | String | System temp | Directory for temporary files |
| `lemline.config-dir` | String | "./config" | Directory for configuration files |
| `lemline.node-id` | String | Generated UUID | Unique identifier for this node |
| `lemline.cluster.enabled` | Boolean | false | Enable cluster mode |
| `lemline.cluster.name` | String | "lemline-cluster" | Cluster name for node discovery |

## Database Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.database.type` | String | "h2" | Database type: "postgresql", "mysql", or "h2" |
| `lemline.database.host` | String | "localhost" | Database host |
| `lemline.database.port` | Integer | Depends on type | Database port |
| `lemline.database.name` | String | "lemline" | Database name |
| `lemline.database.username` | String | | Database username |
| `lemline.database.password` | String | | Database password |
| `lemline.database.schema` | String | Public/default | Database schema name |
| `lemline.database.min-pool-size` | Integer | 5 | Minimum connection pool size |
| `lemline.database.max-pool-size` | Integer | 20 | Maximum connection pool size |
| `lemline.database.connection-timeout` | Duration | PT30S | Connection acquisition timeout |
| `lemline.database.idle-timeout` | Duration | PT10M | Connection idle timeout |
| `lemline.database.max-lifetime` | Duration | PT30M | Maximum connection lifetime |
| `lemline.database.auto-migration` | Boolean | true | Automatically run database migrations |
| `lemline.database.migration-locations` | String | "db/migration" | Location of migration scripts |

## Messaging Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.type` | String | "memory" | Messaging type: "kafka", "rabbitmq", or "memory" |
| `lemline.messaging.default-timeout` | Duration | PT1M | Default message processing timeout |
| `lemline.messaging.dead-letter-enabled` | Boolean | true | Enable dead letter queue for failed messages |
| `lemline.messaging.error-handler` | String | "log" | Error handler: "log", "dlq", or "retry" |
| `lemline.messaging.retry-attempts` | Integer | 3 | Number of retry attempts for failed messages |
| `lemline.messaging.retry-delay` | Duration | PT5S | Delay between retry attempts |

### Kafka Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.kafka.bootstrap.servers` | String | "localhost:9092" | Kafka bootstrap servers |
| `lemline.messaging.kafka.client.id` | String | "${lemline.node-id}" | Kafka client ID |
| `lemline.messaging.kafka.group.id` | String | "lemline" | Kafka consumer group ID |
| `lemline.messaging.kafka.auto.offset.reset` | String | "earliest" | Offset reset strategy |
| `lemline.messaging.kafka.enable.auto.commit` | Boolean | false | Enable auto commit |
| `lemline.messaging.kafka.auto.commit.interval.ms` | Integer | 5000 | Auto commit interval |
| `lemline.messaging.kafka.key.serializer` | String | "org.apache.kafka.common.serialization.StringSerializer" | Key serializer class |
| `lemline.messaging.kafka.value.serializer` | String | "io.quarkus.kafka.client.serialization.JsonbSerializer" | Value serializer class |
| `lemline.messaging.kafka.key.deserializer` | String | "org.apache.kafka.common.serialization.StringDeserializer" | Key deserializer class |
| `lemline.messaging.kafka.value.deserializer` | String | "io.quarkus.kafka.client.serialization.JsonbDeserializer" | Value deserializer class |
| `lemline.messaging.kafka.security.protocol` | String | "PLAINTEXT" | Security protocol |
| `lemline.messaging.kafka.sasl.mechanism` | String | | SASL mechanism |
| `lemline.messaging.kafka.sasl.jaas.config` | String | | JAAS configuration |

### RabbitMQ Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.rabbitmq.host` | String | "localhost" | RabbitMQ host |
| `lemline.messaging.rabbitmq.port` | Integer | 5672 | RabbitMQ port |
| `lemline.messaging.rabbitmq.username` | String | "guest" | RabbitMQ username |
| `lemline.messaging.rabbitmq.password` | String | "guest" | RabbitMQ password |
| `lemline.messaging.rabbitmq.virtual-host` | String | "/" | RabbitMQ virtual host |
| `lemline.messaging.rabbitmq.exchange` | String | "lemline" | Default exchange name |
| `lemline.messaging.rabbitmq.delivery-mode` | Integer | 2 | Delivery mode (1=non-persistent, 2=persistent) |
| `lemline.messaging.rabbitmq.connection-timeout` | Duration | PT30S | Connection timeout |
| `lemline.messaging.rabbitmq.requested-heartbeat` | Duration | PT60S | Heartbeat interval |
| `lemline.messaging.rabbitmq.automatic-recovery-enabled` | Boolean | true | Enable automatic connection recovery |
| `lemline.messaging.rabbitmq.topology-recovery-enabled` | Boolean | true | Enable topology recovery |

## HTTP Client Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.http.connect-timeout` | Duration | PT5S | Connection establishment timeout |
| `lemline.http.read-timeout` | Duration | PT60S | Socket read timeout |
| `lemline.http.write-timeout` | Duration | PT30S | Socket write timeout |
| `lemline.http.call-timeout` | Duration | PT90S | Total request timeout |
| `lemline.http.follow-redirects` | Boolean | true | Automatically follow redirects |
| `lemline.http.max-redirects` | Integer | 5 | Maximum number of redirects to follow |
| `lemline.http.max-connections` | Integer | 100 | Maximum number of concurrent connections |
| `lemline.http.max-connections-per-route` | Integer | 20 | Maximum connections per route |
| `lemline.http.keep-alive-time` | Duration | PT5M | Keep-alive duration |
| `lemline.http.user-agent` | String | "Lemline/${version}" | User-Agent header value |
| `lemline.http.compression-enabled` | Boolean | true | Enable request/response compression |
| `lemline.http.default-headers` | Map | | Default headers for all requests |
| `lemline.http.trace-enabled` | Boolean | false | Enable HTTP request/response tracing |
| `lemline.http.trace-level` | String | "HEADERS" | Trace level: "BASIC", "HEADERS", "BODY" |
| `lemline.http.circuit-breaker.enabled` | Boolean | true | Enable circuit breaker |
| `lemline.http.circuit-breaker.failure-threshold` | Double | 0.5 | Failure ratio threshold |
| `lemline.http.circuit-breaker.request-volume-threshold` | Integer | 20 | Minimum request volume |
| `lemline.http.circuit-breaker.delay` | Duration | PT60S | Circuit open duration |
| `lemline.http.proxy.host` | String | | HTTP proxy host |
| `lemline.http.proxy.port` | Integer | 8080 | HTTP proxy port |
| `lemline.http.proxy.username` | String | | HTTP proxy username |
| `lemline.http.proxy.password` | String | | HTTP proxy password |
| `lemline.http.proxy.non-proxy-hosts` | String | "localhost\|127.*\|[::1]" | Hosts to exclude from proxy |

## Workflow Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.workflow.definitions-dir` | String | "./workflows" | Directory for workflow definitions |
| `lemline.workflow.max-execution-time` | Duration | PT1H | Default maximum workflow execution time |
| `lemline.workflow.max-concurrent-instances` | Integer | 100 | Maximum concurrent workflow instances |
| `lemline.workflow.max-instances-per-definition` | Integer | 20 | Maximum instances per workflow definition |
| `lemline.workflow.auto-cleanup` | Boolean | true | Automatically clean up completed workflows |
| `lemline.workflow.cleanup-after` | Duration | P7D | Time after completion to clean up workflows |
| `lemline.workflow.instance-id-prefix` | String | | Prefix for workflow instance IDs |
| `lemline.workflow.cache-definitions` | Boolean | true | Cache workflow definitions |
| `lemline.workflow.cache-max-size` | Integer | 100 | Maximum size of definition cache |
| `lemline.workflow.cache-expiry` | Duration | PT1H | Definition cache entry expiry |
| `lemline.workflow.validation.enabled` | Boolean | true | Enable workflow definition validation |
| `lemline.workflow.validation.strict` | Boolean | false | Enable strict validation mode |
| `lemline.workflow.state-persistence` | String | "full" | State persistence: "full", "minimal", "checkpoints" |
| `lemline.workflow.checkpoint-interval` | Integer | 10 | Checkpoint every N state changes |
| `lemline.workflow.event-correlation-ttl` | Duration | PT1H | Event correlation timeout |

## Execution Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.execution.executor` | String | "thread-pool" | Executor type: "thread-pool", "virtual-thread", "project-reactor" |
| `lemline.execution.thread-pool.core-size` | Integer | 10 | Core thread pool size |
| `lemline.execution.thread-pool.max-size` | Integer | 50 | Maximum thread pool size |
| `lemline.execution.thread-pool.queue-size` | Integer | 100 | Work queue size |
| `lemline.execution.thread-pool.keep-alive` | Duration | PT60S | Thread keep-alive time |
| `lemline.execution.virtual-thread.max-parallelism` | Integer | 1000 | Maximum virtual thread parallelism |
| `lemline.execution.priority.enabled` | Boolean | false | Enable priority-based scheduling |
| `lemline.execution.priority.levels` | Integer | 5 | Number of priority levels |
| `lemline.execution.retry.max-attempts` | Integer | 3 | Maximum retry attempts |
| `lemline.execution.retry.delay` | Duration | PT1S | Initial retry delay |
| `lemline.execution.retry.max-delay` | Duration | PT1M | Maximum retry delay |
| `lemline.execution.retry.multiplier` | Double | 2.0 | Retry delay multiplier |
| `lemline.execution.retry.jitter` | Double | 0.1 | Retry delay jitter factor |

## Outbox Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.outbox.enabled` | Boolean | true | Enable outbox pattern |
| `lemline.outbox.polling-interval` | Duration | PT2S | Polling interval for outbox processor |
| `lemline.outbox.max-poll-records` | Integer | 50 | Maximum records to process per poll |
| `lemline.outbox.processing-timeout` | Duration | PT30S | Maximum processing time for a record |
| `lemline.outbox.retry-attempts` | Integer | 5 | Maximum retry attempts for failed processing |
| `lemline.outbox.retry-delay` | Duration | PT5S | Initial retry delay |
| `lemline.outbox.retry-multiplier` | Double | 2.0 | Retry delay multiplier |
| `lemline.outbox.retry-max-delay` | Duration | PT5M | Maximum retry delay |
| `lemline.outbox.cleanup-interval` | Duration | PT1H | Cleanup interval for processed records |
| `lemline.outbox.retention-time` | Duration | P7D | Retention time for processed records |

## Security Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.security.secrets.source` | String | "env" | Secrets source: "env", "file", "vault" |
| `lemline.security.secrets.env.prefix` | String | "LEMLINE_SECRET_" | Environment variable prefix for secrets |
| `lemline.security.secrets.file.path` | String | "./secrets.properties" | Path to secrets file |
| `lemline.security.secrets.vault.url` | String | | HashiCorp Vault URL |
| `lemline.security.secrets.vault.token` | String | | HashiCorp Vault token |
| `lemline.security.secrets.vault.path` | String | "secret/lemline" | HashiCorp Vault secrets path |
| `lemline.security.secrets.refresh.enabled` | Boolean | false | Enable periodic secrets refresh |
| `lemline.security.secrets.refresh.interval` | Duration | PT15M | Secrets refresh interval |
| `lemline.security.tls.enabled` | Boolean | true | Enable TLS for HTTP client |
| `lemline.security.tls.truststore.path` | String | | Path to truststore |
| `lemline.security.tls.truststore.password` | String | | Truststore password |
| `lemline.security.tls.truststore.type` | String | "JKS" | Truststore type |
| `lemline.security.tls.keystore.path` | String | | Path to keystore |
| `lemline.security.tls.keystore.password` | String | | Keystore password |
| `lemline.security.tls.keystore.type` | String | "PKCS12" | Keystore type |
| `lemline.security.tls.protocols` | String | "TLSv1.2,TLSv1.3" | Enabled TLS protocols |
| `lemline.security.tls.cipher-suites` | String | | Enabled cipher suites |
| `lemline.security.tls.verify-hostname` | Boolean | true | Verify hostname in TLS certificates |
| `lemline.security.tls.trustall` | Boolean | false | Trust all certificates (insecure!) |

## Observability Configuration

### Logging Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `quarkus.log.level` | String | "INFO" | Root log level |
| `quarkus.log.category."com.lemline"` | String | "INFO" | Log level for Lemline packages |
| `quarkus.log.console.enable` | Boolean | true | Enable console logging |
| `quarkus.log.console.format` | String | "%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c{2.}] (%t) %s%e%n" | Console log format |
| `quarkus.log.console.json` | Boolean | false | Use JSON format for console logs |
| `quarkus.log.file.enable` | Boolean | false | Enable file logging |
| `quarkus.log.file.path` | String | "lemline.log" | Log file path |
| `quarkus.log.file.rotation.max-file-size` | String | "10M" | Maximum log file size |
| `quarkus.log.file.rotation.max-backup-index` | Integer | 5 | Maximum number of log backups |
| `quarkus.log.file.rotation.file-suffix` | String | ".yyyy-MM-dd" | Log file rotation suffix |
| `lemline.log.structured` | Boolean | true | Enable structured logging |
| `lemline.log.workflow.enabled` | Boolean | true | Enable workflow execution logging |
| `lemline.log.workflow.level` | String | "INFO" | Workflow execution log level |
| `lemline.log.http.enabled` | Boolean | true | Enable HTTP client logging |
| `lemline.log.http.level` | String | "INFO" | HTTP client log level |
| `lemline.log.http.include-headers` | Boolean | false | Include headers in HTTP logs |
| `lemline.log.http.include-body` | Boolean | false | Include body in HTTP logs |
| `lemline.log.messaging.enabled` | Boolean | true | Enable messaging logging |
| `lemline.log.messaging.level` | String | "INFO" | Messaging log level |

### Metrics Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.metrics.enabled` | Boolean | true | Enable metrics collection |
| `lemline.metrics.prefix` | String | "lemline" | Metrics name prefix |
| `lemline.metrics.workflow.enabled` | Boolean | true | Enable workflow metrics |
| `lemline.metrics.http.enabled` | Boolean | true | Enable HTTP client metrics |
| `lemline.metrics.database.enabled` | Boolean | true | Enable database metrics |
| `lemline.metrics.messaging.enabled` | Boolean | true | Enable messaging metrics |
| `quarkus.micrometer.enabled` | Boolean | true | Enable Micrometer metrics |
| `quarkus.micrometer.registry-enabled-default` | Boolean | true | Enable default Micrometer registries |
| `quarkus.micrometer.export.prometheus.enabled` | Boolean | true | Enable Prometheus metrics |
| `quarkus.micrometer.export.prometheus.path` | String | "/metrics" | Prometheus metrics endpoint |

### Tracing Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.tracing.enabled` | Boolean | false | Enable distributed tracing |
| `lemline.tracing.workflow.enabled` | Boolean | true | Enable workflow execution tracing |
| `lemline.tracing.workflow.level` | String | "STANDARD" | Workflow trace level: "BASIC", "STANDARD", "DETAILED" |
| `lemline.tracing.http.enabled` | Boolean | true | Enable HTTP client tracing |
| `lemline.tracing.messaging.enabled` | Boolean | true | Enable messaging tracing |
| `quarkus.opentelemetry.enabled` | Boolean | false | Enable OpenTelemetry |
| `quarkus.opentelemetry.tracer.enabled` | Boolean | false | Enable OpenTelemetry tracer |
| `quarkus.opentelemetry.tracer.exporter.otlp.enabled` | Boolean | false | Enable OTLP exporter |
| `quarkus.opentelemetry.tracer.exporter.otlp.endpoint` | String | "http://localhost:4317" | OTLP endpoint |

### Health Checks Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.health.enabled` | Boolean | true | Enable health checks |
| `lemline.health.database.enabled` | Boolean | true | Enable database health check |
| `lemline.health.messaging.enabled` | Boolean | true | Enable messaging health check |
| `lemline.health.disk-space.enabled` | Boolean | true | Enable disk space health check |
| `lemline.health.disk-space.threshold` | String | "100MB" | Disk space threshold |
| `quarkus.health.extensions.enabled` | Boolean | true | Enable Quarkus health extensions |
| `quarkus.health.openapi.included` | Boolean | true | Include OpenAPI in health endpoint |
| `quarkus.smallrye-health.root-path` | String | "/health" | Health endpoint path |
| `quarkus.smallrye-health.liveness-path` | String | "/health/live" | Liveness endpoint path |
| `quarkus.smallrye-health.readiness-path` | String | "/health/ready" | Readiness endpoint path |

## Configuration Methods

Lemline configuration can be provided through multiple methods, in order of precedence:

1. **Command Line Arguments**: `-Dlemline.property=value`
2. **Environment Variables**: `LEMLINE_PROPERTY=value`
3. **Configuration Files**: `application.properties` or `application.yaml`
4. **System Properties**: Java system properties
5. **Default Values**: Fallback to defaults

## Environment Variable Mapping

Configuration properties can be set via environment variables using the following transformation:

1. Convert to uppercase
2. Replace dots (`.`) with underscores (`_`)
3. Add `LEMLINE_` prefix (for Lemline-specific properties)

Examples:

| Property | Environment Variable |
|----------|----------------------|
| `lemline.database.host` | `LEMLINE_DATABASE_HOST` |
| `lemline.http.connect-timeout` | `LEMLINE_HTTP_CONNECT_TIMEOUT` |
| `quarkus.log.level` | `QUARKUS_LOG_LEVEL` |

## Profile-Based Configuration

Configuration can be specific to profiles using the `%{profile}` prefix:

```properties
# Default
lemline.log.level=INFO

# Development profile
%dev.lemline.log.level=DEBUG

# Production profile
%prod.lemline.log.level=WARN
```

Activate a profile using:
- `-Dquarkus.profile=dev` command line argument
- `QUARKUS_PROFILE=dev` environment variable

## Related Resources

- [Configuring the Lemline Runner](lemline-howto-config.md)
- [Environment Variables Reference](lemline-ref-env-vars.md)
- [Secrets Management](lemline-howto-secrets.md)
- [Database Configuration](lemline-howto-brokers.md)