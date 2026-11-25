---
title: Lifecycle Events
---

# Lifecycle Events

Lifecycle events are [CloudEvents](https://cloudevents.io/) that describe workflow or task state
changes. The runtime publishes them for observers such as monitoring and audit systems. Use
[Emit](dsl-task-emit.md) and [Listen](dsl-task-listen.md) to publish and consume business events.

## Workflow Lifecycle Events

The [Open Workflow 1.0.3 specification](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl.md#workflow-lifecycle-events)
lists the event types for workflows. A started event marks the beginning of execution. The terminal
event says whether it completed, faulted, or was cancelled. The specification also defines events
for suspension, resumption, and the beginning and end of event correlation.

See the [correlation lifecycle example](dsl-event-correlation.md#inspect-correlation-lifecycle-events)
to query the start and completion of a correlated wait.

## Task Lifecycle Events

At task level, a lifecycle event identifies the task within the workflow. For example, observers can
look for the start and completion of a task, the error on a faulted task, or the attempt on a retried task.
The specification also defines optional status-change events at both levels.

## Observe Completion, Fault, and Retry

The workflow below approves an ordinary order, rejects an order in fault mode, or retries a
transient check once in retry mode and recovers when the attempted check still fails.

<code-block lang="yaml" src="workflows/dsl-lifecycle-events/lifecycle-order-check.yaml"/>

> **Lemline**
>
> With the SQLite setup, the analytics consumer stores lifecycle events in
> `analytics.db`. The worker emits the `org.open-workflow-specification.*` event types
> and recognizes the older `io.serverlessworkflow.*` names when reading stored events.
> The query below lists the event type and selected payload fields.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Start the three instances in the CLI terminal, where Node.js 22.13 or later must be available for the query.

```bash
bin/lemline definition post -f lifecycle-order-check.yaml
bin/lemline instance start examples lifecycle-order-check --input '{"orderId":"O-200","mode":"complete"}'
bin/lemline instance start examples lifecycle-order-check --input '{"orderId":"O-201","mode":"fault"}'
bin/lemline instance start examples lifecycle-order-check --input '{"orderId":"O-202","mode":"retry"}'
```

Give the worker time to finish the retry and store the events, then query SQLite from the setup directory.

```bash
sleep 22
node --input-type=module <<'EOF'
import { DatabaseSync } from 'node:sqlite';

const db = new DatabaseSync('analytics.db', { readOnly: true });
const rows = db.prepare(`
  SELECT payload
  FROM lemline_lifecycle_events
  WHERE lemline_workflow_name = ?
  ORDER BY id
`).all('lifecycle-order-check');

const stages = new Set([
  'workflowStarted', 'workflowCompleted', 'workflowFaulted',
  'taskStarted', 'taskCompleted', 'taskFaulted', 'taskRetried',
]);

for (const { payload } of rows) {
  const event = JSON.parse(payload);
  const data = event.data;
  if (!stages.has(data.type)) continue;
  console.log(JSON.stringify({
    type: event.type,
    instance: event.lemlineworkflowid,
    task: data.task,
    output: data.output,
    status: data.error?.status,
    retryCount: data.retryCount,
  }));
}
db.close();
EOF
```

| Example | Expected result |
|---|---|
| `O-200` | The instance completes with `status: "approved"`. Find its workflow started and completed events and its task start and completion events. |
| `O-201` | The instance faults with status `422`. The faulted task and workflow events carry that status. |
| `O-202` | The instance completes with `status: "recovered"`. A task retried event reports `retryCount: 1`. |
