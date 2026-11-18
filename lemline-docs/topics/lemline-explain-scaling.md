# Understanding Scaling in Lemline

This document explains how a Lemline deployment grows with the load: what runs where, what can be multiplied, and which settings matter.

## The Moving Parts

A Lemline deployment has four kinds of components:

| Component | Started with | Scales by |
|-----------|--------------|-----------|
| Runner | `lemline listen` | Running more processes |
| Gateway | `lemline gateway start` | Running more processes behind a load balancer |
| Message broker | Kafka, RabbitMQ or PGMQ | The broker's own means (partitions, nodes) |
| Database | PostgreSQL or MySQL | The database's own means |

Runners and gateways keep no state between messages. Every runner and gateway connects to the same broker and the same database.

## Stateless Runners

The state of a running workflow travels inside the messages: each command carries what the next step needs. Any runner can therefore execute the next step of any instance it serves, and a runner can be stopped or added at any time.

To process more workflows, start more `lemline listen` processes with the same configuration. There is no cluster membership, leader election or node discovery to configure.

## How Work Is Shared

### Broker messages

Runners consume four channels: `commands`, `events`, `cloudevents` and `lifecycleevents` (see [Runner Configuration Reference](lemline-ref-config.md)).

- **Kafka**: runners of the same consumer group (`lemline.messaging.kafka.<channel>.consumer.group-id`) share the partitions of a topic, using the cooperative sticky assignor so that adding a runner moves few partitions. A topic with N partitions keeps at most N runners busy on that topic: create topics with enough partitions for the number of runners you plan.
- **RabbitMQ** and **PGMQ**: runners reading the same queue compete for its messages.

### Parallelism inside a runner

Each channel processes up to `lemline.messaging.<broker>.<channel>.consumer.concurrency` messages at the same time (default `64`). Raise it when runners wait mostly on remote calls; lower it when they run out of memory or CPU.

### Database work

Waits, retries, schedules, listeners and gateway starts are stored in the database and processed by background tasks on every runner. Each run reads a batch of due rows with `FOR UPDATE SKIP LOCKED`, so several runners can work on the same table without processing the same row twice.

Per processor (`wait`, `retry`, `schedule`, `listener`, `gateway`), you can tune:

- `lemline.outbox.<outbox>.outbox.every`: how often it runs (default `10s`)
- `lemline.outbox.<outbox>.outbox.batch-size`: rows per run (default `1000`)
- `lemline.outbox.<outbox>.outbox.initial-jitter`: random delay before the first run (default `3s`), so runners started together do not hit the database at the same moment

Old rows are deleted by cleanup tasks configured under `lemline.outbox.<outbox>.cleanup.*`.

### Namespaces

`lemline.namespaces` lists the namespaces a runner serves; destinations are named `<prefix>-<namespace>-<type>`. Use it to give a team or a heavy workload its own runners, scaled independently, while sharing the same broker and database.

## The Gateway

The gateway accepts start requests and serves the dashboard's read and watch requests. It does not execute workflows: a start request is stored and then sent to the runners. Run several gateways behind a gRPC-aware load balancer for availability; they share the database.

## Limits to Watch

- **Database**: every runner and gateway uses it. It is usually the first shared resource to saturate when runners are added.
- **Kafka partitions**: runners beyond the partition count stay idle on that topic.
- **Workflow design**: a `fork` or a `for` loop increases the work of a single instance; very large inputs and outputs travel in every message of the instance.

## Observing Scaling

Use the [Metrics Reference](lemline-ref-metrics.md):

- `lemline_messaging_commands_active` close to the configured concurrency means a runner is saturated.
- A growing broker lag or queue depth (from the broker's own metrics) means more runners are needed.
- `lemline_messaging_commands_processing_duration_seconds` shows whether steps slow down as load grows.

## Related Resources

- [How to Configure Scaling](lemline-howto-scaling.md)
- [Runner Configuration Reference](lemline-ref-config.md)
- [Metrics Reference](lemline-ref-metrics.md)
