# Configuring the Lemline Runner

This guide explains how to give the Lemline runner its configuration: where the file lives, how to override values, and the settings you usually change. For the full list of properties, see the [Runner Configuration Reference](lemline-ref-config.md).

## Configuration Overview

Lemline reads its configuration from, in order of decreasing priority:

1. **Java system properties**: `-Dlemline.messaging.type=kafka`
2. **Environment variables**: `LEMLINE_MESSAGING_TYPE=kafka`
3. **A YAML configuration file**
4. **Built-in defaults**

Every command except `lemline config create` needs a configuration file, and stops with an error when none is found. To try Lemline on one machine, use SQLite for orchestration, messaging and lifecycle analytics, as described in [Setting Up A Local Environment](lemline-tutorial-setup.md).

The in-memory database and the in-memory H2 analytics store are only available in Lemline's own test suite: the released binaries and jar do not include them, so `database.type: in-memory` and the default `analytics.type: h2` cannot start a runner.

## Create a Configuration File

Choose the broker, orchestration database and workflow history database independently:

```bash
lemline config create --messaging kafka --database postgresql --analytics postgresql
```

This writes `lemline.yaml` in the current directory. Lemline does not look for that name on its own: either pass it with `--config lemline.yaml`, or create the file as `.lemline.yaml` with `-o .lemline.yaml` so that it is found automatically. Use `--output <path>` to choose another path, `--force` to overwrite an existing file, and `--dry-run` to print the file instead of writing it. See the [CLI reference](lemline-ref-cli.md#config-create) for supported types and defaults.

In the example above, workflow definitions use the PostgreSQL database `lemline` and workflow history uses `lemline_analytics`. Follow the commands in the generated file to start the services and create the analytics database. To store history in a local file while keeping Kafka and PostgreSQL for the other roles, change the option to `--analytics sqlite`.

## Tell Lemline Where the File Is

Lemline uses the first file found among:

1. the path given with `--config <path>` (or `-c <path>`)
2. the path in the `LEMLINE_CONFIG` environment variable
3. `./.lemline.yaml`
4. `~/.config/lemline/config.yaml`
5. `~/.lemline.yaml`

```bash
lemline listen --config /etc/lemline/lemline.yaml
```

```bash
LEMLINE_CONFIG=/etc/lemline/lemline.yaml lemline listen
```

A file given with `--config` or `LEMLINE_CONFIG` must exist; otherwise the command stops with an error.

## Override Values with Environment Variables

Any property can be overridden with an environment variable: write the property name in upper case and replace every character that is not a letter or a digit with `_`.

| Property                                | Environment Variable                      |
|-----------------------------------------|-------------------------------------------|
| `lemline.database.postgresql.host`      | `LEMLINE_DATABASE_POSTGRESQL_HOST`        |
| `lemline.database.postgresql.password`  | `LEMLINE_DATABASE_POSTGRESQL_PASSWORD`    |
| `lemline.messaging.kafka.brokers`       | `LEMLINE_MESSAGING_KAFKA_BROKERS`         |

This is the recommended way to pass passwords:

```bash
LEMLINE_DATABASE_POSTGRESQL_PASSWORD=secret lemline listen --config lemline.yaml
```

See [Environment Variables Reference](lemline-ref-env-vars.md).

## Check the Effective Configuration

Print the configuration Lemline will actually use, after merging all sources:

```bash
lemline config show
```

Use `--format yaml` or `--format properties` to choose the output format, and `--all` to include non-Lemline properties.

## Configuration Categories

### Database

<!-- workflow-example: data -->
```yaml
lemline:
  database:
    type: postgresql          # postgresql, mysql or sqlite
    migrate-at-start: true    # apply schema migrations at startup
    postgresql:
      host: localhost
      port: 5432
      database: lemline
      username: postgres
```

If `type` is omitted, Lemline picks `postgresql` or `mysql` from the block you filled in. You can also apply migrations explicitly with `lemline migrate`, or preview them with `lemline migrate --pretend`.

### Messaging

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: kafka               # kafka, rabbitmq, pgmq, sqlite or in-memory
    kafka:
      brokers: localhost:9092
      commands:
        consumer:
          group-id: lemline-group
```

RabbitMQ:

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: rabbitmq
    rabbitmq:
      hostname: localhost
      port: 5672
      username: guest
      password: guest
```

PGMQ (PostgreSQL as a message queue):

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: pgmq
    pgmq:
      host: localhost
      port: 5432
      database: lemline
      username: postgres
```

See [Message Brokers](lemline-howto-brokers.md).

### Namespaces

By default a runner works on the `default` namespace. To make it serve other namespaces:

<!-- workflow-example: data -->
```yaml
lemline:
  namespaces: orders, billing
```

### Concurrency

Each channel processes up to 64 messages in parallel by default:

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    commands:
      consumer:
        concurrency: 32
```

### Logging

Use the command-line options `--debug`, `--info`, `--warn` or `--error` to set the log level, and `--json` for JSON logs. Quarkus log properties are not read from the Lemline configuration file, which only accepts `lemline.*` settings. Set them as environment variables instead (or as `-D` system properties):

```bash
export QUARKUS_LOG_LEVEL=INFO
export QUARKUS_LOG_CONSOLE_LEVEL=DEBUG   # the console only shows ERROR by default
export QUARKUS_LOG_CATEGORY__COM_LEMLINE__LEVEL=DEBUG
```

### Secret Management

Lemline has no secret settings. Each secret a workflow declares in `use.secrets` is read from the runner environment variable with exactly the same name. See [Managing Secrets in Lemline](lemline-howto-secrets.md).

### Metrics and Health

`lemline listen` serves Prometheus metrics at `http://<host>:8080/q/metrics` and health checks at `/q/health/live` and `/q/health/ready`. Change the port with `lemline.metrics.port` or `lemline listen --metrics-port <port>`.

## Best Practices

1. **Keep one file per environment** and select it with `--config` or `LEMLINE_CONFIG`.
2. **Keep passwords out of the file**: pass them as environment variables.
3. **Set the types explicitly** (`lemline.database.type`, `lemline.messaging.type`) in production, so a stray property cannot change the backend.
4. **Check before deploying** with `lemline config show`.

## Related Resources

- [Runner Configuration Reference](lemline-ref-config.md)
- [Environment Variables Reference](lemline-ref-env-vars.md)
- [Command Line Reference](lemline-ref-cli.md)
- [Secrets Management](lemline-howto-secrets.md)
- [Message Brokers](lemline-howto-brokers.md)
