# How to Configure Scaling

This guide shows how to add capacity to a Lemline deployment. For the reasoning behind it, see [Understanding Scaling in Lemline](lemline-explain-scaling.md).

## When to Scale

Add capacity when:

- broker lag or queue depth keeps growing
- `lemline_messaging_commands_active` stays close to the configured concurrency
- step processing time (`lemline_messaging_commands_processing_duration_seconds`) grows with load

## Add Runners

Runners are stateless. Start more of them with the same configuration:

```bash
# Terminal 1
LEMLINE_CONFIG=/etc/lemline/lemline.yaml lemline listen

# Terminal 2 (another port for metrics if on the same machine)
LEMLINE_CONFIG=/etc/lemline/lemline.yaml lemline listen --metrics-port 8081
```

Stopping a runner is safe: messages it did not acknowledge are delivered to another runner.

### Try it on one machine

On [the SQLite setup](lemline-tutorial-setup.md), two workers started from the same directory share the same files
and the same work. Stop the setup worker, then start two workers with their own metrics ports, each in its own
terminal in the setup directory (with the JAR, substitute `java -jar lemline.jar` for `bin/lemline`):

```text
bin/lemline listen --debug --metrics-port 8081
bin/lemline listen --debug --metrics-port 8082
```

From a third terminal, register [the first workflow](lemline-getting-started.md#creating-your-first-workflow) and
start it a dozen times:

```text
bin/lemline definition post -f hello.yaml
for i in $(seq 1 12); do bin/lemline instance start examples shop-receipt; done
```

Each instance completes once, and both worker terminals show `Workflow completed with output` lines: in one run, one
worker completed 8 instances and the other 4. SQLite keeps everything on one host; to spread workers over several
machines, use a shared database and broker.

### With Kafka

Runners in the same consumer group share a topic's partitions, so a topic needs at least as many partitions as runners. Keep the same group id on every runner:

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: kafka
    kafka:
      brokers: kafka-1:9092,kafka-2:9092
      commands:
        consumer:
          group-id: lemline-group
```

### With RabbitMQ or PGMQ

Runners reading the same queue compete for its messages; no extra setting is needed.

## Tune Concurrency per Runner

Each channel processes up to 64 messages in parallel. Raise the limit when steps mostly wait on remote calls, lower it when a runner runs short of memory:

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    kafka:          # or rabbitmq, pgmq
      commands:
        consumer:
          concurrency: 128
      events:
        consumer:
          concurrency: 32
```

Set the limit under the broker you use (`lemline.messaging.<broker>.<channel>.consumer.concurrency`). The broker-independent `lemline.messaging.<channel>.consumer.concurrency` only applies to in-memory messaging: with Kafka, RabbitMQ or PGMQ it is overwritten by the broker setting.

## Tune Background Processing

Waits, retries, schedules, listeners and gateway starts are processed from the database by every runner. For a large backlog, process bigger batches or run more often:

<!-- workflow-example: data -->
```yaml
lemline:
  outbox:
    retry:
      outbox:
        every: 5s
        batch-size: 2000
```

See the outbox properties in the [Runner Configuration Reference](lemline-ref-config.md).

## Split Work by Namespace

Give a workload its own runners by putting it in its own namespace and starting runners that serve only that namespace:

<!-- workflow-example: data -->
```yaml
lemline:
  namespaces: orders
```

Runners without `lemline.namespaces` serve the `default` namespace only.

## Scale the Gateway

Run several `lemline gateway start` processes behind a load balancer that supports gRPC (HTTP/2). They share the database and need no coordination.

## Kubernetes

Run runners as a Deployment and scale its replicas. A CPU-based Horizontal Pod Autoscaler is a reasonable starting point:

<!-- workflow-example: data -->
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: lemline-runner
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: lemline-runner
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

With Kafka, keep `maxReplicas` at or below the number of partitions. Use `/q/health/live` and `/q/health/ready` on the metrics port as liveness and readiness probes.

## Common Issues

| Symptom | Likely cause | What to do |
|---------|--------------|------------|
| New runners stay idle (Kafka) | More runners than partitions | Add partitions to the topics |
| Runners receive messages they do not process (`foreign_namespace` metric grows) | Runner not configured for the message's namespace | Fix `lemline.namespaces`, then `lemline failures replay` |
| Database CPU saturates as runners are added | Shared database is the bottleneck | Scale the database; reduce outbox batch frequency |

## Related Resources

- [Understanding Scaling in Lemline](lemline-explain-scaling.md)
- [Metrics Reference](lemline-ref-metrics.md)
- [Runner Configuration Reference](lemline-ref-config.md)
