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

## Stopping a Workflow on Request

Let the workflow listen for a cancellation event while it waits, and end when it arrives:

```yaml
do:
  - waitForPaymentOrCancellation:
      listen:
        to:
          any:
            - with:
                type: com.example.payment.received
            - with:
                type: com.example.order.cancelled
        read: envelope
  - checkCancellation:
      switch:
        - cancelled:
            when: ${ .[0].type == "com.example.order.cancelled" }
            then: end
        - default:
            then: continue
```

Send the cancellation as a CloudEvent on the CloudEvents channel of the workflow's namespace. See [How to emit and listen for events](lemline-howto-events.md).

## Bounding How Long a Workflow Runs

Use a timeout so an instance cannot run forever:

```yaml
timeout:
  after:
    hours: 24
do:
  - processOrder:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/orders
```

When the timeout expires, the workflow faults with a `timeout` error. See [Timeouts](dsl-timeouts.md).

## Pausing Until an External Signal

A `listen` task pauses the instance until a matching event arrives; a `wait` task pauses it for a fixed duration. Neither uses runner resources while waiting.

```yaml
do:
  - waitForApproval:
      listen:
        to:
          one:
            with:
              type: com.example.order.approved
```

## Recovering from Failures

- **Inside the workflow**: wrap fragile tasks in `try` with a `retry` policy, so temporary errors are retried automatically. See [Implementing Retry Mechanisms](lemline-howto-retry.md).
- **After a fault**: a faulted instance stays faulted. Fix the cause, then start a new instance with `lemline instance start`.
- **Messages parked by a runner**: messages received for a namespace that no runner served are kept in the failures table. Re-send them with `lemline failures replay`.

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

They are published on the lifecycle events channel and shown in the dashboard. See [Lifecycle Events](dsl-lifecycle-events.md).

## Next Steps

- Learn about [monitoring workflow execution](lemline-howto-monitor.md)
- Explore [how to debug faults in task execution](lemline-howto-debug.md)
- Understand [how to emit and listen for events](lemline-howto-events.md)
