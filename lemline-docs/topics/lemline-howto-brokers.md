# How to Configure Message Brokers

This guide explains how to connect Lemline to a message broker. Runners exchange workflow steps through the broker, and workflows publish and receive CloudEvents through it.

## Supported Message Brokers

| Broker | `lemline.messaging.type` | Use |
|--------|--------------------------|-----|
| Kafka | `kafka` | Production, high throughput |
| RabbitMQ | `rabbitmq` | Production |
| PGMQ (PostgreSQL Message Queue) | `pgmq` | Production without a dedicated broker; needs the PGMQ extension in PostgreSQL |
| In-memory | `in-memory` | Single process only, for local tests |

If `lemline.messaging.type` is not set, Lemline uses the broker whose settings block (`lemline.messaging.kafka`, `rabbitmq` or `pgmq`) is present, and in-memory messaging when there is none. At startup, the runner checks that the broker is reachable and stops with an explicit error if it is not.

## Kafka Configuration

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

A message that cannot be processed is moved to a dead-letter destination named after its source with a `.dlq` suffix, for example `lemline-default-commands.dlq`. With PGMQ, a message goes there after `max-retries` deliveries.

## Consumer Concurrency

Each runner processes up to 64 messages in parallel per channel. Change it per channel, under the broker you use:

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

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: order-workflow
  version: '0.1.0'
do:
  - notifyShipping:
      emit:
        event:
          with:
            type: order.shipped
            source: order-service
            data:
              orderId: ${ .orderId }
```

### Listening for Events

`listen` tasks wait for CloudEvents received on the same destination. A workflow can also be started by an event with `schedule.on`:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: shipping-workflow
  version: '0.1.0'
schedule:
  on:
    one:
      with:
        type: order.shipped
do:
  - processShippingNotification:
      set:
        received: ${ . }
```

See [Listen behavior](dsl-task-listen.md).

## Testing with the In-Memory Broker

```yaml
lemline:
  messaging:
    type: in-memory
```

Messages only travel inside one process and are lost when it stops. Use it for local tests only.

## Common Issues and Solutions

### The runner stops at startup with a broker error

- Check the host and port (`brokers` for Kafka, `hostname` and `port` for RabbitMQ, `host` and `port` for PGMQ).
- Check credentials and network access.
- For PGMQ, check that the PGMQ extension is installed in the database.

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
