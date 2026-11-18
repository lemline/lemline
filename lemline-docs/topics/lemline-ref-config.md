# Runner Configuration Reference

This reference lists every `lemline.*` configuration property the Lemline runner reads. Properties that are not listed here are not recognized by Lemline.

## Configuration Sources

Lemline reads its configuration from, in order of decreasing priority:

1. **Java system properties**: `-Dlemline.database.type=postgresql`
2. **Environment variables**: `LEMLINE_DATABASE_TYPE=postgresql`
3. **Configuration file**: a YAML file (see below)
4. **Built-in defaults**

The configuration file is the first one found among:

1. the path given with `--config <path>` or `-c <path>`
2. the path in the `LEMLINE_CONFIG` environment variable
3. `./.lemline.yaml`
4. `~/.config/lemline/config.yaml`
5. `~/.lemline.yaml`

A path given with `--config` or `LEMLINE_CONFIG` must exist, otherwise the command fails. Generate a starting file with `lemline config create`, and print the effective configuration with `lemline config show`.

### Environment variable names

An environment variable name is the property name in upper case, with every character that is not a letter or a digit replaced by `_`:

| Property | Environment variable |
|----------|----------------------|
| `lemline.database.postgresql.host` | `LEMLINE_DATABASE_POSTGRESQL_HOST` |
| `lemline.messaging.kafka.commands.consumer.group-id` | `LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_GROUP_ID` |
| `lemline.gateway.tls.private-key` | `LEMLINE_GATEWAY_TLS_PRIVATE_KEY` |

See [Environment Variables Reference](lemline-ref-env-vars.md).

### Settings set by the command

Each command turns on only what it needs, whatever the configuration says:

- `lemline listen` enables every message producer and consumer, the metrics endpoint and the background tasks.
- `lemline gateway start` enables the gateway, the commands producer and the lifecycle events producer, and disables every consumer.
- `lemline instance start` enables the commands producer and the lifecycle events producer.
- `lemline failures replay` enables the commands and events producers.
- Other commands (`definition`, `config`, `migrate`) disable messaging, background tasks and the metrics endpoint.

## Namespaces

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.namespaces` | List | - | Namespaces this process works on. When unset, a runner works on the `default` namespace only and the gateway serves all namespaces. |



## Database

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.database.type` | String | inferred | `postgresql`, `mysql` or `in-memory`. When unset, Lemline uses `postgresql` if any `lemline.database.postgresql.*` property is set, `mysql` if any `lemline.database.mysql.*` property is set, and the in-memory H2 database otherwise. Setting both families without a type is an error. |
| `lemline.database.migrate-at-start` | Boolean | `false` | Apply database migrations when the runner starts |
| `lemline.database.baseline-on-migrate` | Boolean | `false` | Baseline an existing, non-empty schema before migrating |
| `lemline.database.enabled` | Boolean | `true` | Set by the command line; commands that need no database turn it off |

### PostgreSQL

| Property | Type | Default |
|----------|------|---------|
| `lemline.database.postgresql.host` | String | `localhost` |
| `lemline.database.postgresql.port` | Integer | `5432` |
| `lemline.database.postgresql.database` | String | `lemline` |
| `lemline.database.postgresql.username` | String | `postgres` |
| `lemline.database.postgresql.password` | String | `postgres` |

### MySQL

| Property | Type | Default |
|----------|------|---------|
| `lemline.database.mysql.host` | String | `localhost` |
| `lemline.database.mysql.port` | Integer | `3306` |
| `lemline.database.mysql.database` | String | `lemline` |
| `lemline.database.mysql.username` | String | `mysql` |
| `lemline.database.mysql.password` | String | `mysql` |

## Messaging

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.type` | String | inferred | `kafka`, `rabbitmq`, `pgmq` or `in-memory`. When unset, Lemline uses the broker whose `lemline.messaging.<broker>.*` properties are set, and `in-memory` when none is. Setting several broker families without a type is an error. |
| `lemline.messaging.prefix` | String | `lemline` | Prefix of destination names. Topics and queues are named `<prefix>-<namespace>-<type>`, for example `lemline-default-commands`. Lowercase letters, digits and `-` only. |

### Channels

Lemline uses four channels:

| Channel | Carries |
|---------|---------|
| `commands` | Workflow execution steps, processed by any runner |
| `events` | Durable operations (waits, retries, schedules, listeners, child workflows) stored by the runner |
| `cloudevents` | CloudEvents emitted by `emit` tasks and consumed by `listen` tasks |
| `lifecycleevents` | Workflow and task lifecycle CloudEvents, consumed by lifecycle analytics |

For each `<channel>` above:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.<channel>.producer.enabled` | Boolean | `false` | Publish on this channel. Usually set by the command. |
| `lemline.messaging.<channel>.consumer.enabled` | Boolean | `false` | Consume from this channel. Usually set by the command. |
| `lemline.messaging.<channel>.consumer.concurrency` | Integer | `64` | Maximum number of messages processed in parallel |

### Kafka

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.kafka.brokers` | String | `localhost:9092` | Bootstrap servers |
| `lemline.messaging.kafka.security-protocol` | String | - | Kafka `security.protocol`, e.g. `SASL_SSL` |
| `lemline.messaging.kafka.sasl-mechanism` | String | `PLAIN` when username and password are set | Kafka `sasl.mechanism` |
| `lemline.messaging.kafka.sasl-username` | String | - | SASL username (with the password, configures the PLAIN login module) |
| `lemline.messaging.kafka.sasl-password` | String | - | SASL password |

For each `<channel>` (`commands`, `events`, `cloudevents`, `lifecycleevents`):

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.kafka.<channel>.consumer.concurrency` | Integer | `64` | Consumer concurrency |
| `lemline.messaging.kafka.<channel>.consumer.group-id` | String | `lemline-group` (commands, events), `lemline-cloudevents-group`, `lemline-lifecycle-events-group` | Consumer group |
| `lemline.messaging.kafka.<channel>.consumer.offset-reset` | String | `earliest` | `earliest` or `latest` |

Lifecycle events go to a single shared topic:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.kafka.lifecycleevents.topic` | String | `lemline-lifecycle-events` | Lifecycle events topic |
| `lemline.messaging.kafka.lifecycleevents.producer.topic-out` | String | - | Override of the topic the producer writes to |
| `lemline.messaging.kafka.lifecycleevents.consumer.topic-dlq` | String | `<topic>.dlq` | Dead-letter topic of the lifecycle consumer |

Failed records of the other channels go to `<source-topic>.dlq`.

### RabbitMQ

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.rabbitmq.hostname` | String | `localhost` | Broker host |
| `lemline.messaging.rabbitmq.port` | Integer | `5672` | Broker port |
| `lemline.messaging.rabbitmq.username` | String | `guest` | Username |
| `lemline.messaging.rabbitmq.password` | String | `guest` | Password |
| `lemline.messaging.rabbitmq.virtual-host` | String | `/` | Virtual host |
| `lemline.messaging.rabbitmq.ssl-enabled` | Boolean | - | Connect with TLS |

For each `<channel>` (`commands`, `events`, `cloudevents`, `lifecycleevents`):

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.rabbitmq.<channel>.consumer.concurrency` | Integer | `64` | Consumer concurrency |
| `lemline.messaging.rabbitmq.<channel>.consumer.queue-dlq` | String | - | Dead-letter queue override |
| `lemline.messaging.rabbitmq.<channel>.queue` | String | derived | Queue name override (`commands`, `events`, `cloudevents`). By default the name is derived from the namespaces the runner serves. |

Lifecycle events:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.rabbitmq.lifecycleevents.queue` | String | `lemline-lifecycle-events` | Lifecycle events queue |
| `lemline.messaging.rabbitmq.lifecycleevents.producer.queue-out` | String | - | Override of the queue the producer writes to |
| `lemline.messaging.rabbitmq.lifecycleevents.producer.exchange-name` | String | - | Override of the exchange the producer writes to |

### PGMQ (PostgreSQL Message Queue)

| Property | Type | Default |
|----------|------|---------|
| `lemline.messaging.pgmq.host` | String | `localhost` |
| `lemline.messaging.pgmq.port` | Integer | `5432` |
| `lemline.messaging.pgmq.database` | String | `lemline` |
| `lemline.messaging.pgmq.username` | String | `postgres` |
| `lemline.messaging.pgmq.password` | String | `postgres` |

For each `<channel>` (`commands`, `events`, `cloudevents`, `lifecycleevents`):

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.pgmq.<channel>.consumer.concurrency` | Integer | `64` | Consumer concurrency |
| `lemline.messaging.pgmq.<channel>.consumer.visibility-timeout` | Integer | `30` | Seconds a read message stays invisible to other consumers |
| `lemline.messaging.pgmq.<channel>.consumer.poll-interval` | Integer | `100` | Milliseconds between polls when the queue is empty |
| `lemline.messaging.pgmq.<channel>.consumer.batch-size` | Integer | `10` | Messages read per poll |
| `lemline.messaging.pgmq.<channel>.consumer.max-retries` | Integer | `3` | Deliveries before a message goes to the dead-letter queue |

Lifecycle events:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.messaging.pgmq.lifecycleevents.queue` | String | `lemline-lifecycle-events` | Lifecycle events queue |
| `lemline.messaging.pgmq.lifecycleevents.producer.queue-out` | String | - | Override of the queue the producer writes to |
| `lemline.messaging.pgmq.lifecycleevents.consumer.queue-dlq` | String | - | Dead-letter queue of the lifecycle consumer |

## Orchestrator

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.orchestrator.mode` | String | `action` | `action` sends a message only for tasks that do something (call, run, emit, ...); `all` sends a message for every task, which is mainly useful for end-to-end tests |

## Definitions

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.definitions.cache.sync-every` | Duration | `10s` | How often each runner refreshes its cache of workflow definitions from the database |

## Background Tasks (Outboxes and Cleanup)

Runners process durable operations stored in the database (waits, retries, schedules, listeners, gateway starts) and delete old rows.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.scheduled.enabled` | Boolean | `true` | Run background tasks at all. Set by the command line. |

For each `<outbox>` in `wait`, `retry`, `schedule`, `listener`, `gateway`:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.outbox.<outbox>.enabled` | Boolean | `true` | Run this processor |
| `lemline.outbox.<outbox>.outbox.every` | Duration | `10s` | Processing interval |
| `lemline.outbox.<outbox>.outbox.batch-size` | Integer | `1000` | Rows processed per run |
| `lemline.outbox.<outbox>.outbox.initial-jitter` | Duration | `3s` | Maximum random delay before the first run, to spread runners apart |
| `lemline.outbox.<outbox>.outbox.retry-delay` | Duration | `30s` | Base delay before retrying a failed row; doubles on each attempt, with ±20% jitter |
| `lemline.outbox.<outbox>.outbox.max-attempts` | Integer | `5` | Attempts before a row is given up |
| `lemline.outbox.<outbox>.cleanup.every` | Duration | `1h` | Cleanup interval |
| `lemline.outbox.<outbox>.cleanup.initial-jitter` | Duration | `3s` | Maximum random delay before the first cleanup |
| `lemline.outbox.<outbox>.cleanup.after` | Duration | `7d` | Age after which processed rows are deleted |
| `lemline.outbox.<outbox>.cleanup.batch-size` | Integer | `1000` | Rows deleted per run |

`parent` and `fork` only have the `enabled` and `cleanup.*` properties.

Durations accept values such as `500ms`, `10s`, `5m`, `1h` and `7d`.

## Metrics and Health

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.metrics.port` | Integer | `8080` | HTTP port of the metrics and health endpoints. `listen --metrics-port` and `gateway start --metrics-port` override it. |
| `lemline.metrics.path` | String | `/q/metrics` | Path of the Prometheus metrics endpoint |

Health checks are served on the same port at `/q/health`, `/q/health/live` and `/q/health/ready`.

## Analytics

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.analytics.type` | String | `h2` | `h2` or `postgresql` |
| `lemline.analytics.migrate-at-start` | Boolean | `true` | Apply analytics migrations at startup |
| `lemline.analytics.baseline-on-migrate` | Boolean | `false` | Baseline an existing analytics schema |
| `lemline.analytics.postgresql.host` | String | `localhost` | |
| `lemline.analytics.postgresql.port` | Integer | `5432` | |
| `lemline.analytics.postgresql.database` | String | `lemline` | |
| `lemline.analytics.postgresql.username` | String | `postgres` | |
| `lemline.analytics.postgresql.password` | String | `postgres` | |
| `lemline.analytics.postgresql.schema` | String | `lemline` | |

See [Lifecycle Analytics Reference](lemline-ref-analytics.md).

## Gateway

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lemline.gateway.enabled` | Boolean | `false` | Start the gRPC gateway. Set by `lemline gateway start`. |
| `lemline.gateway.grpc.host` | String | `0.0.0.0` | Bind host |
| `lemline.gateway.grpc.port` | Integer | `9000` | Bind port. `gateway start --grpc-port` overrides it. |
| `lemline.gateway.tls.enabled` | Boolean | `true` | Serve gRPC over TLS |
| `lemline.gateway.tls.certificate` | String | - | Server certificate path (required when TLS is enabled) |
| `lemline.gateway.tls.private-key` | String | - | Server private key path (required when TLS is enabled) |
| `lemline.gateway.tls.client-auth` | String | `none` | Client certificate mode: `none`, `request` or `required` |
| `lemline.gateway.tls.trust-store` | String | - | Trust store for client certificates |
| `lemline.gateway.tls.trust-store-password` | String | - | Trust store password |
| `lemline.gateway.authentication.enabled` | Boolean | `true` | Require a JWT bearer token (requires TLS) |
| `lemline.gateway.authentication.jwt.issuer` | String | - | Expected token issuer |
| `lemline.gateway.authentication.jwt.jwks-url` | String | - | JWKS URL used to verify tokens |
| `lemline.gateway.authentication.claims.scope-field` | String | `scope` | Claim holding the scopes |
| `lemline.gateway.authentication.claims.namespaces-field` | String | `lemline_namespaces` | Claim holding the allowed namespaces |
| `lemline.gateway.cors.enabled` | Boolean | `true` | Enable CORS for browser clients |
| `lemline.gateway.cors.origins` | String | `http://localhost:5173` | Allowed origins |
| `lemline.gateway.cors.methods` | String | `GET,POST,OPTIONS` | Allowed methods |
| `lemline.gateway.cors.headers` | String | `Accept,Authorization,Content-Type,Grpc-Timeout,X-Grpc-Web,X-User-Agent` | Allowed request headers |
| `lemline.gateway.watch.poll-interval-ms` | Integer | `250` | Polling interval of watch streams, in milliseconds |
| `lemline.gateway.watch.batch-size` | Integer | `256` | Rows read per watch poll |

See [gRPC Gateway Reference](lemline-ref-gateway.md).

## Security Configuration

There are no `lemline.security.*` settings. Workflow secrets are read from environment variables named after each secret; see [Secrets Reference](lemline-ref-secrets.md).

## Script Runtimes

`run.script` tasks call local executables. Set them with the `lemline.python.exec` and `lemline.node.exec` system properties, or with the `LEMLINE_PYTHON_EXEC` and `LEMLINE_NODE_EXEC` environment variables. They are not read from the configuration file. Defaults: `python3` and `node`.

## Logging

Logging uses the standard Quarkus properties, for example:

```yaml
quarkus:
  log:
    level: INFO
    category:
      "com.lemline":
        level: DEBUG
```

From the command line, `--debug`, `--info`, `--warn` and `--error` set the log level, and `--json` switches to JSON logs. The default level is `ERROR`.

## Example

```yaml
lemline:
  database:
    type: postgresql
    migrate-at-start: true
    postgresql:
      host: db.internal
      database: lemline
      username: lemline
  messaging:
    type: kafka
    kafka:
      brokers: kafka-1:9092,kafka-2:9092
      commands:
        consumer:
          concurrency: 32
```

Keep passwords out of the file: set them with environment variables such as `LEMLINE_DATABASE_POSTGRESQL_PASSWORD`.

## Related Resources

- [Configuring the Lemline Runner](lemline-howto-config.md)
- [Environment Variables Reference](lemline-ref-env-vars.md)
- [Command Line Reference](lemline-ref-cli.md)
- [Secrets Management](lemline-howto-secrets.md)
- [Message Brokers](lemline-howto-brokers.md)
