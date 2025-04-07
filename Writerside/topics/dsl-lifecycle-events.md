---
title: Lifecycle Events
---

# Lifecycle Events

## Introduction

Lifecycle events are standardized [CloudEvents](https://cloudevents.io/) that provide visibility into the state changes of workflows and tasks throughout their execution. These events offer valuable insights for monitoring, auditing, and building reactive systems that respond to workflow states.

The Serverless Workflow DSL defines two categories of lifecycle events:
1. **Workflow Lifecycle Events** - Events emitted during the lifecycle of a workflow instance
2. **Task Lifecycle Events** - Events emitted during the lifecycle of individual tasks

These events follow the [CloudEvents specification](https://github.com/cloudevents/spec), promoting interoperability with other systems and standardized event handling.

## Event Publishing

Runtimes implementing the Serverless Workflow specification are expected to publish these events upon state changes. While the recommended approach is using the [HTTP protocol binding](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/bindings/http-protocol-binding.md) with [structured content mode](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/bindings/http-protocol-binding.md#32-structured-content-mode), other transports adhering to the CloudEvents specification may be used.

## Workflow Lifecycle Events

Workflow lifecycle events track the state of workflow instances as they progress from creation to completion. Each event carries consistent information including workflow identity, status transitions, timestamps, and relevant metadata.

| Event Type | Description | Required |
|------------|-------------|:--------:|
| `io.serverlessworkflow.workflow.started.v1` | Emitted when a workflow instance starts execution | Yes |
| `io.serverlessworkflow.workflow.suspended.v1` | Emitted when a workflow execution is suspended | Yes |
| `io.serverlessworkflow.workflow.resumed.v1` | Emitted when a suspended workflow resumes execution | Yes |
| `io.serverlessworkflow.workflow.correlation-started.v1` | Emitted when a workflow begins correlating events | Yes |
| `io.serverlessworkflow.workflow.correlation-completed.v1` | Emitted when a workflow completes an event correlation | Yes |
| `io.serverlessworkflow.workflow.cancelled.v1` | Emitted when a workflow execution is cancelled | Yes |
| `io.serverlessworkflow.workflow.faulted.v1` | Emitted when a workflow encounters an unhandled error | Yes |
| `io.serverlessworkflow.workflow.completed.v1` | Emitted when a workflow successfully completes | Yes |
| `io.serverlessworkflow.workflow.status-changed.v1` | Emitted when a workflow's status phase changes | No |

### Workflow Started Event

The `io.serverlessworkflow.workflow.started.v1` event is emitted when a workflow instance begins execution. It typically includes:

- **Workflow identity** (ID, namespace, name, version)
- **Timestamp** when the workflow started
- **Input data** (optional, may be excluded for privacy/security)
- **Parent workflow** information (if started by another workflow)
- **Correlation IDs** (if applicable)

Example event data:
```json
{
  "workflowId": "order-processing-12345",
  "namespace": "com.example.orders",
  "name": "process-order",
  "version": "1.2.0",
  "startedAt": "2023-11-15T14:30:00Z",
  "parentWorkflowId": "customer-onboarding-789",
  "input": {
    "orderId": "ORD-12345",
    "customerId": "CUST-6789"
  }
}
```

### Workflow Completed Event

The `io.serverlessworkflow.workflow.completed.v1` event is emitted when a workflow successfully completes all its tasks. It typically includes:

- **Workflow identity** (ID, namespace, name, version)
- **Timestamps** for both start and completion
- **Output data** (optional, may be excluded for privacy/security)
- **Execution duration**
- **Metrics** about the workflow execution (optional)

Example event data:
```json
{
  "workflowId": "order-processing-12345",
  "namespace": "com.example.orders",
  "name": "process-order",
  "version": "1.2.0",
  "startedAt": "2023-11-15T14:30:00Z",
  "completedAt": "2023-11-15T14:35:27Z",
  "durationMs": 327000,
  "output": {
    "orderId": "ORD-12345",
    "status": "COMPLETED",
    "paymentId": "PAY-87654"
  },
  "metrics": {
    "tasksExecuted": 5,
    "retries": 1
  }
}
```

### Workflow Correlation Events

The correlation events provide visibility into how workflows interact with external events:

- **Correlation Started**: Emitted when a workflow begins waiting for specific events with correlation criteria.
- **Correlation Completed**: Emitted when a workflow receives all required correlated events.

The Correlation Completed event is particularly useful as it includes `correlationKeys` that show which specific data values were matched.

Example correlation completed event data:
```json
{
  "workflowId": "payment-processing-67890",
  "namespace": "com.example.payments",
  "name": "process-payment",
  "version": "1.0.0",
  "correlationKeys": {
    "matchOrderId": "ORD-12345",
    "matchTransactionType": "PURCHASE"
  },
  "receivedAt": "2023-11-15T14:32:15Z"
}
```

## Task Lifecycle Events

Task lifecycle events provide detailed information about the execution of individual tasks within a workflow. These events help in monitoring task progress, identifying bottlenecks, and troubleshooting issues.

| Event Type | Description | Required |
|------------|-------------|:--------:|
| `io.serverlessworkflow.task.created.v1` | Emitted when a task is created | Yes |
| `io.serverlessworkflow.task.started.v1` | Emitted when a task begins execution | Yes |
| `io.serverlessworkflow.task.suspended.v1` | Emitted when a task is suspended | Yes |
| `io.serverlessworkflow.task.resumed.v1` | Emitted when a suspended task resumes | Yes |
| `io.serverlessworkflow.task.retried.v1` | Emitted when a task is retried after failure | Yes |
| `io.serverlessworkflow.task.cancelled.v1` | Emitted when a task is cancelled | Yes |
| `io.serverlessworkflow.task.faulted.v1` | Emitted when a task encounters an error | Yes |
| `io.serverlessworkflow.task.completed.v1` | Emitted when a task successfully completes | Yes |
| `io.serverlessworkflow.task.status-changed.v1` | Emitted when a task's status phase changes | No |

### Task Started Event

The `io.serverlessworkflow.task.started.v1` event is emitted when a task begins execution. It typically includes:

- **Task identity** (reference, type)
- **Workflow identity** (ID, namespace, name, version)
- **Timestamp** when the task started
- **Input data** (optional, may be excluded for privacy/security)

Example event data:
```json
{
  "taskReference": "validateOrder",
  "taskType": "function",
  "workflowId": "order-processing-12345",
  "namespace": "com.example.orders",
  "name": "process-order",
  "version": "1.2.0",
  "startedAt": "2023-11-15T14:30:05Z",
  "input": {
    "order": {
      "id": "ORD-12345",
      "items": [{"id": "ITEM-1", "quantity": 2}, {"id": "ITEM-2", "quantity": 1}]
    }
  }
}
```

### Task Completed Event

The `io.serverlessworkflow.task.completed.v1` event is emitted when a task successfully completes. It typically includes:

- **Task identity** (reference, type)
- **Workflow identity** (ID, namespace, name, version)
- **Timestamps** for both start and completion
- **Output data** (optional, may be excluded for privacy/security)
- **Execution duration**

Example event data:
```json
{
  "taskReference": "validateOrder",
  "taskType": "function",
  "workflowId": "order-processing-12345",
  "namespace": "com.example.orders",
  "name": "process-order",
  "version": "1.2.0",
  "startedAt": "2023-11-15T14:30:05Z",
  "completedAt": "2023-11-15T14:30:07Z",
  "durationMs": 2000,
  "output": {
    "isValid": true,
    "validatedItems": [{"id": "ITEM-1", "quantity": 2}, {"id": "ITEM-2", "quantity": 1}]
  }
}
```

### Task Faulted Event

The `io.serverlessworkflow.task.faulted.v1` event is emitted when a task encounters an error. This event is particularly valuable for monitoring and troubleshooting. It typically includes:

- **Task identity** (reference, type)
- **Workflow identity** (ID, namespace, name, version)
- **Error details** (type, status, message)
- **Timestamps** for start and fault occurrence
- **Input data** that caused the fault (optional)

Example event data:
```json
{
  "taskReference": "processPayment",
  "taskType": "function",
  "workflowId": "order-processing-12345",
  "namespace": "com.example.orders",
  "name": "process-order",
  "version": "1.2.0",
  "startedAt": "2023-11-15T14:32:10Z",
  "faultedAt": "2023-11-15T14:32:12Z",
  "error": {
    "type": "https://serverlessworkflow.io/spec/1.0.0/errors/validation",
    "status": 400,
    "detail": "Invalid payment information: Credit card expired",
    "instance": "/do/processPayment"
  }
}
```

### Task Retried Event

The `io.serverlessworkflow.task.retried.v1` event is emitted when a task is retried after a failure. It includes:

- **Task identity** (reference, type)
- **Workflow identity** (ID, namespace, name, version)
- **Error details** that triggered the retry
- **Retry information** (attempt number, delay)

Example event data:
```json
{
  "taskReference": "sendNotification",
  "taskType": "function",
  "workflowId": "order-processing-12345",
  "namespace": "com.example.orders",
  "name": "process-order",
  "version": "1.2.0",
  "retriedAt": "2023-11-15T14:33:15Z",
  "attemptNumber": 2,
  "delayMs": 1000,
  "previousError": {
    "type": "https://serverlessworkflow.io/spec/1.0.0/errors/communication",
    "status": 503,
    "detail": "Notification service temporarily unavailable"
  }
}
```

## Use Cases for Lifecycle Events

Lifecycle events enable several important use cases:

1. **Workflow Monitoring and Observability**
   - Real-time dashboards showing workflow execution status
   - Identifying bottlenecks or slow-running tasks
   - Tracking workflow completion rates and durations

2. **Auditing and Compliance**
   - Recording a complete history of workflow and task executions
   - Tracking who or what initiated workflows
   - Providing evidence for regulatory compliance requirements

3. **Event-Driven Reactions**
   - Triggering compensating workflows when a workflow faults
   - Notifying systems or users when workflows reach certain states
   - Updating external systems based on workflow progress

4. **Debugging and Troubleshooting**
   - Investigating the root cause of workflow failures
   - Tracking the execution path through complex workflow definitions
   - Identifying patterns in task retries or failures

## Consuming Lifecycle Events

Lifecycle events can be consumed by:

1. **Event Processors** - Systems that subscribe to and process these events can perform a wide range of actions, from generating metrics to initiating other workflows.

2. **Monitoring Tools** - Specialized monitoring tools can provide dashboards and alerts based on lifecycle events.

3. **Audit Repositories** - Events can be stored in audit repositories for historical record-keeping and analysis.

4. **Custom Applications** - Applications can subscribe to specific events to perform business-specific actions.

## Status Changed Events

The `status-changed` events (`io.serverlessworkflow.workflow.status-changed.v1` and `io.serverlessworkflow.task.status-changed.v1`) are optional convenience events. They:

- Contain minimal data (primarily just the status transition)
- Provide a lightweight alternative for consumers who only need to track status changes
- May be emitted in addition to the more specific lifecycle events

Since these events add additional overhead for runtime implementations and most status changes are already covered by the main lifecycle events, their implementation is optional.

## Conclusion

Lifecycle events provide a comprehensive way to gain visibility into workflow and task execution. By leveraging these standardized events, developers can build more observable, auditable, and reactive systems that respond intelligently to workflow state changes. Runtime implementations adhering to the Serverless Workflow specification provide these events as part of their standard behavior, enabling consistent monitoring and integration patterns across different environments. 