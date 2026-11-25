---
title: How to cancel, resume, or suspend a workflow
---

# How to cancel, resume, or suspend a workflow

This guide explains what you can do today to stop, pause or recover a running workflow instance in Lemline.

## Current Status

Lemline does not yet provide operator commands to act on a running instance. There is no CLI command, gRPC gateway call or dashboard action to cancel, suspend, resume or retry an instance.

| Operation | Available as an operator action | What to do instead |
|-----------|----------------------------------|--------------------|
| Cancel | No | Design the workflow to stop on a cancellation event or a timeout |
| Suspend / Resume | No | Design the workflow to wait for an event before continuing |
| Retry a faulted instance | No | Use `retry` on the failing task, or start a new instance |

The rest of this guide shows how to build these behaviors into the workflow itself.

## Prepare the examples

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. The examples
need no shop. They react to events that this publisher sends on the worker's SQLite event channel. Save it as
`tutorial-publisher.yaml` and register it (skip it if you registered it in the tutorial):

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

```bash
bin/lemline definition post -f tutorial-publisher.yaml
```

Start each listening instance first, then publish its event. An event only resumes the instances already waiting for it.

## Stopping a Workflow on Request

Let the workflow listen for a cancellation event while it waits, and end when it arrives:

<code-block lang="yaml" src="workflows/lemline-howto-lifecycle/stopping-a-workflow-on-request.yaml"/>

Save it as `stopping-a-workflow-on-request.yaml`. Start one instance and cancel it, then start another and pay it:

```bash
bin/lemline definition post -f stopping-a-workflow-on-request.yaml
bin/lemline instance start examples stopping-a-workflow-on-request
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.cancelled","data":{"orderId":"ORD-401"}}'
bin/lemline instance start examples stopping-a-workflow-on-request
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.received","data":{"orderId":"ORD-402"}}'
```

The first instance completes with `{"status":"cancelled"}`, the second with `{"status":"paid"}`. Each instance
resumes a few seconds after its event. This example matches events by type only, so start the second instance after
the first has completed. Add a `correlate` rule to match an order ID, as in
[How to emit and listen for events](lemline-howto-events.md).

Send the cancellation as a CloudEvent on the CloudEvents channel of the workflow's namespace. See [How to emit and listen for events](lemline-howto-events.md).

## Bounding How Long a Workflow Waits

Lemline does not enforce workflow-level timeouts yet, nor timeouts on tasks other than `listen`: they are accepted but ignored. To keep an instance from waiting forever for an external signal, put a timeout on the `listen` task:

<code-block lang="yaml" src="workflows/lemline-howto-lifecycle/bounding-how-long-a-workflow-waits.yaml"/>

When the timeout expires, the task faults with a `timeout` error (status 408), which the `try` task catches here.
The example waits 30 seconds so that you can see both outcomes; a real payment wait would use hours. See
[Timeouts](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl.md#timeouts).

Save it as `bounding-how-long-a-workflow-waits.yaml`. Pay the first instance in time, and let the second expire:

```bash
bin/lemline definition post -f bounding-how-long-a-workflow-waits.yaml
bin/lemline instance start examples bounding-how-long-a-workflow-waits
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.received","data":{"orderId":"ORD-403"}}'
bin/lemline instance start examples bounding-how-long-a-workflow-waits
```

The first instance completes with the payment data, `[{"orderId":"ORD-403"}]`. The second completes with
`{"status":"expired"}` 30 to 40 seconds later: the worker checks expired waits every 10 seconds.

## Pausing Until an External Signal

A `listen` task pauses the instance until a matching event arrives; a `wait` task pauses it for a fixed duration. Neither uses runner resources while waiting.

<code-block lang="yaml" src="workflows/lemline-howto-lifecycle/pausing-until-an-external-signal.yaml"/>

Save it as `pausing-until-an-external-signal.yaml`, start it, then send the approval:

```bash
bin/lemline definition post -f pausing-until-an-external-signal.yaml
bin/lemline instance start examples pausing-until-an-external-signal
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.approved","data":{"orderId":"ORD-404","approver":"manager"}}'
```

The instance stays paused until the approval arrives, then completes with `{"approvedBy":"manager"}`.

## Recovering from Failures

- **Inside the workflow**: wrap fragile tasks in `try` with a `retry` policy, so temporary errors are retried automatically. See [Implementing Retry Mechanisms](lemline-howto-retry.md).
- **After a fault**: a faulted instance stays faulted. Fix the cause, then start a new instance with `lemline instance start`.
- **Instances of a namespace no worker serves**: `instance start` succeeds, but the instance does not run.
  On the SQLite setup, its message waits in the queue: add the namespace to `lemline.namespaces` and restart the
  worker, and the instance runs. With a shared broker, a worker that receives a message for a namespace it does
  not serve parks it in the failures table; re-send it with `bin/lemline failures replay` once a worker serves that
  namespace. On SQLite, `failures replay --dry-run` answers `No failures found with reason 'foreign_namespace'`.

## Observing Lifecycle Changes

Lemline emits lifecycle CloudEvents that external systems can consume:

| Event type | Emitted when |
|------------|--------------|
| `org.open-workflow-specification.workflow.created.v1` | An instance is created |
| `org.open-workflow-specification.workflow.started.v1` | An instance starts |
| `org.open-workflow-specification.workflow.completed.v1` | An instance completes |
| `org.open-workflow-specification.workflow.faulted.v1` | An instance faults |
| `org.open-workflow-specification.task.created.v1` | A task is created |
| `org.open-workflow-specification.task.started.v1` | A task starts |
| `org.open-workflow-specification.task.completed.v1` | A task completes |
| `org.open-workflow-specification.task.faulted.v1` | A task faults |
| `org.open-workflow-specification.task.retried.v1` | A task is retried |

They are published on the lifecycle events channel and shown in the dashboard. See [Lifecycle Events](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#lifecycle-events).

## Next Steps

- Learn about [monitoring workflow execution](lemline-howto-monitor.md)
- Explore [how to debug faults in task execution](lemline-howto-debug.md)
- Understand [how to emit and listen for events](lemline-howto-events.md)
