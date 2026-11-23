# How to Configure Message Brokers

This guide explains how to connect Lemline to a message broker. Runners exchange workflow steps through the broker, and workflows publish and receive CloudEvents through it.

## Supported Message Brokers

| Broker | `lemline.messaging.type` | Use |
|--------|--------------------------|-----|
| Kafka | `kafka` | Production, high throughput |
| RabbitMQ | `rabbitmq` | Production |
| PGMQ (PostgreSQL Message Queue) | `pgmq` | Production without a dedicated broker; runs on a standard PostgreSQL, no extension needed |
| SQLite | `sqlite` | Single host without a broker or a queue server; messages are kept in a local file |
| In-memory | `in-memory` | Single process only, for local tests |

If `lemline.messaging.type` is not set, Lemline uses the broker whose settings block (`lemline.messaging.kafka`, `rabbitmq` or `pgmq`) is present, and in-memory messaging when there is none. SQLite is never selected this way: set `type: sqlite` explicitly. At startup, the runner checks that the broker is reachable and stops with an explicit error if it is not.

## Kafka Configuration

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: kafka
    kafka:
      brokers: localhost:9092
      # Optional security settings
      security-protocol: SASL_SSL
      sasl-mechanism: PLAIN
      sasl-username: lemline
      sasl-password: change-me
      commands:
        consumer:
          group-id: lemline-group
          offset-reset: earliest
          concurrency: 64
```

When both `sasl-username` and `sasl-password` are set, Lemline configures the PLAIN login module and defaults `sasl-mechanism` to `PLAIN`.

Each channel (`commands`, `events`, `cloudevents`, `lifecycleevents`) accepts `consumer.group-id`, `consumer.offset-reset` (`earliest` or `latest`) and `consumer.concurrency`.

## RabbitMQ Configuration

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
      virtual-host: /
      ssl-enabled: false
```

Lemline declares its exchanges and queues itself. Each channel accepts `consumer.concurrency`, `consumer.queue-dlq` and, for `commands`, `events` and `cloudevents`, a `queue` name override.

## PGMQ Configuration

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
      password: postgres
      commands:
        consumer:
          concurrency: 64
          batch-size: 10
          poll-interval: 100       # milliseconds
          visibility-timeout: 30   # seconds
          max-retries: 3
```

## SQLite Configuration

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: sqlite
    sqlite:
      path: /var/lib/lemline/messaging.db
      batch-size: 10
      poll-interval: 50        # milliseconds
      visibility-timeout: 30   # seconds
      max-retries: 3
```

All Lemline processes that share the queue must run on the same host and open the same file. A relative `path` is resolved against the directory each process is started from, so prefer an absolute path. The parent directory must already exist; Lemline creates the file and its tables itself.

A message that a runner holds longer than `visibility-timeout` is delivered again to another consumer. Raise it above the longest time one workflow step can take, for example a slow HTTP call.

## Destination Names

Lemline derives destination names from the namespace of each workflow:

```
<prefix>-<namespace>-<type>
```

- `<prefix>` is `lemline.messaging.prefix` (default `lemline`).
- `<type>` is `commands`, `events` or `cloudevents`.

For example, workflows of the `default` namespace use `lemline-default-commands`. Topic names cannot be set per channel. Lifecycle events go to a single shared destination, `lemline-lifecycle-events` by default (`lemline.messaging.<broker>.lifecycleevents.topic` or `.queue`).

A runner only consumes the namespaces listed in `lemline.namespaces` (default: `default`).

## Dead-Letter Destinations

A message that cannot be processed is moved to a dead-letter destination named after its source with a `.dlq` suffix, for example `lemline-default-commands.dlq`. With PGMQ and SQLite, a message goes there after `max-retries` deliveries.

## Consumer Concurrency

Each runner processes up to 64 messages in parallel per channel. Change it per channel, under the broker you use:

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    kafka:          # or rabbitmq, pgmq
      commands:
        consumer:
          concurrency: 32
```

The broker-independent `lemline.messaging.<channel>.consumer.concurrency` only applies to in-memory messaging; with a broker it is overwritten by `lemline.messaging.<broker>.<channel>.consumer.concurrency`. To process more messages, start more runners: they share the work.

## Implementing Event-Driven Workflows

### Emitting Events

`emit` tasks publish a CloudEvent on the `cloudevents` destination of the workflow's namespace:

<code-block lang="yaml" src="workflows/lemline-howto-brokers/emitting-events.yaml"/>

### Listening for Events

`listen` tasks wait for CloudEvents received on the same destination. A workflow can also be started by an event with `schedule.on`:

<code-block lang="yaml" src="workflows/lemline-howto-brokers/listening-for-events.yaml"/>

See [Listen behavior](dsl-task-listen.md).

## Testing with the In-Memory Broker

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: in-memory
```

Messages only travel inside one process and are lost when it stops, so a separate `lemline instance start` cannot reach a `lemline listen` runner through it. Use it for tests that run everything in one process only.

## Common Issues and Solutions

### The runner stops at startup with a broker error

- Check the host and port (`brokers` for Kafka, `hostname` and `port` for RabbitMQ, `host` and `port` for PGMQ).
- Check credentials and network access.
- For PGMQ, check that the database migrations have run (`lemline migrate`, or `lemline migrate status` to list them): they create the `pgmq` schema and functions in SQL, without the PostgreSQL extension, so managed PostgreSQL services work too.

### Messages are not processed

- Check that a runner is running (`lemline listen`) and serves the workflow's namespace (`lemline.namespaces`).
- Check the `.dlq` destinations for messages that failed.
- With Kafka, check that runners sharing the work use the same `group-id`.

### Processing is slow

- Start more runners.
- Increase `lemline.messaging.<broker>.<channel>.consumer.concurrency` if runners have spare CPU.

## Best Practices

1. **Set `lemline.messaging.type` explicitly** in production.
2. **Monitor the `.dlq` destinations**.
3. **Use TLS and authentication** on the broker connection (`security-protocol` and SASL for Kafka, `ssl-enabled` for RabbitMQ).
4. **Pass passwords as environment variables**, for example `LEMLINE_MESSAGING_KAFKA_SASL_PASSWORD`.

## Related Information

- [Runner Configuration Reference](lemline-ref-config.md)
- [Event Handling Guide](lemline-howto-events.md)
- [AsyncAPI Reference](lemline-ref-asyncapi.md)
