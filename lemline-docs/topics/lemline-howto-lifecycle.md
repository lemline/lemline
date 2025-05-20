---
title: How to cancel, resume, or suspend a workflow
---

# How to cancel, resume, or suspend a workflow

This guide explains how to manage the lifecycle of workflow instances in Lemline, including cancelling, suspending, and resuming workflows.

## Understanding Workflow Lifecycle Operations

Lemline provides several operations to control workflow instances:

| Operation | Description | When to Use |
|-----------|-------------|------------|
| Cancel | Permanently stops a workflow | When the workflow is no longer needed or should be aborted |
| Suspend | Temporarily pauses a workflow | When you need to temporarily halt execution |
| Resume | Continues execution of a suspended workflow | When you're ready to continue a suspended workflow |
| Retry | Re-attempts execution from a failed state | When a workflow has faulted but should be recovered |

## Cancelling a Workflow Instance

### When to Cancel Workflows

You should cancel a workflow when:
- The business process it represents is no longer needed
- An error condition makes continued execution meaningless
- The workflow is stuck and cannot be recovered
- A duplicate workflow was started accidentally

### Using the CLI

To cancel a workflow instance:

```bash
lemline instance cancel <instance-id> --reason "Order was revoked by customer"
```

The `--reason` parameter is optional but recommended for audit purposes.

### Using the REST API

```bash
curl -X POST \
  http://localhost:8080/api/v1/instances/<instance-id>/cancel \
  -H "Content-Type: application/json" \
  -d '{"reason": "Order was revoked by customer"}'
```

### What Happens When a Workflow is Cancelled

When you cancel a workflow:

1. Execution stops immediately
2. The workflow state changes to `CANCELLED`
3. No further tasks are executed
4. Resources associated with the workflow are released
5. A cancellation event is emitted to the message broker
6. The cancellation is logged with the provided reason

### Cancelling Multiple Workflows

You can cancel multiple workflows matching specific criteria:

```bash
lemline instance cancel-bulk --workflow-id order-processing --status RUNNING
```

This is useful for batch operations, such as cancelling all instances of a specific workflow version when deploying an update.

## Suspending a Workflow Instance

### When to Suspend Workflows

You should suspend a workflow when:
- You need to temporarily halt execution for business reasons
- A dependent system is unavailable but will return
- You need to modify related data before continuing
- You're performing a system upgrade

### Using the CLI

To suspend a workflow instance:

```bash
lemline instance suspend <instance-id> --reason "Payment system maintenance" --duration PT1H
```

The `--duration` parameter specifies an optional automatic resume time using ISO-8601 duration format.

### Using the REST API

```bash
curl -X POST \
  http://localhost:8080/api/v1/instances/<instance-id>/suspend \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "Payment system maintenance", 
    "resumeAfter": "PT1H"
  }'
```

### What Happens When a Workflow is Suspended

When you suspend a workflow:

1. Execution pauses at the current task
2. The workflow state changes to `SUSPENDED`
3. Current task state is preserved
4. A suspension event is emitted
5. If a duration is specified, a resume timer is created

## Resuming a Workflow Instance

### When to Resume Workflows

You should resume a workflow when:
- The reason for suspension no longer applies
- Required systems are back online
- Necessary data modifications are complete
- System maintenance is finished

### Using the CLI

To resume a suspended workflow:

```bash
lemline instance resume <instance-id> --reason "Payment system is back online"
```

### Using the REST API

```bash
curl -X POST \
  http://localhost:8080/api/v1/instances/<instance-id>/resume \
  -H "Content-Type: application/json" \
  -d '{"reason": "Payment system is back online"}'
```

### What Happens When a Workflow is Resumed

When you resume a workflow:

1. The workflow state changes back to `RUNNING`
2. Execution continues from the point where it was suspended
3. A resume event is emitted
4. The resumption is logged with the provided reason

## Retrying a Faulted Workflow

### When to Retry Workflows

You should retry a workflow when:
- It has faulted due to a temporary issue
- The underlying problem has been resolved
- The workflow needs to complete despite the previous failure

### Using the CLI

To retry a faulted workflow:

```bash
lemline instance retry <instance-id> --reason "Database connectivity restored"
```

### Using the REST API

```bash
curl -X POST \
  http://localhost:8080/api/v1/instances/<instance-id>/retry \
  -H "Content-Type: application/json" \
  -d '{"reason": "Database connectivity restored"}'
```

### What Happens When a Workflow is Retried

When you retry a workflow:

1. The workflow state changes from `FAULTED` to `RUNNING`
2. Execution resumes from the task that failed
3. Error information is cleared
4. A retry event is emitted
5. The retry is logged with the provided reason

## Advanced Lifecycle Management

### Setting Up Automatic Retry Policies

For production environments, you may want to configure automatic retry policies for faulted workflows:

```yaml
# In lemline.yaml
lemline:
  workflows:
    retry:
      enabled: true
      max-attempts: 3
      delay: PT5M
      exclude-errors:
        - VALIDATION
        - AUTHORIZATION
```

This configuration will automatically retry faulted workflows up to 3 times with a 5-minute delay between attempts, except for validation and authorization errors.

### Implementing Compensation Actions on Cancellation

When cancelling workflows that have already performed external actions, you may need to implement compensation:

1. Create a compensation workflow that reverses the effects
2. When cancelling the original workflow, trigger the compensation workflow:

```bash
lemline instance cancel <instance-id> --reason "Order revoked" \
  --compensation-workflow order-compensation \
  --compensation-input '{"orderId": "ORD-123", "action": "CANCEL"}'
```

### Scheduling Future Lifecycle Operations

You can schedule lifecycle operations for a future time:

```bash
lemline instance schedule-suspend <instance-id> \
  --at "2023-12-24T23:59:59Z" \
  --reason "Christmas shutdown"
```

This is useful for planned maintenance or business hours restrictions.

## Lifecycle Events for External Systems

Lemline emits events for all lifecycle operations, which external systems can consume:

| Event Type | Description |
|------------|-------------|
| `WORKFLOW_CANCELLED` | Emitted when a workflow is cancelled |
| `WORKFLOW_SUSPENDED` | Emitted when a workflow is suspended |
| `WORKFLOW_RESUMED` | Emitted when a workflow is resumed |
| `WORKFLOW_RETRIED` | Emitted when a workflow is retried |

To listen for these events:

```bash
lemline listen events --type WORKFLOW_CANCELLED,WORKFLOW_SUSPENDED
```

## Lifecycle Management Best Practices

1. **Always provide a reason**: Document why lifecycle operations are performed for audit and troubleshooting
2. **Implement proper authorization**: Restrict who can perform lifecycle operations
3. **Consider compensation**: Define compensation strategies for cancelled workflows
4. **Monitor lifecycle operations**: Set up alerts for unusual patterns of cancellations or suspensions
5. **Use timeouts**: When suspending workflows, specify a maximum suspension duration
6. **Document lifecycle procedures**: Create clear procedures for operations teams
7. **Automate when possible**: Use the API to automate lifecycle management for common scenarios

## Common Troubleshooting Scenarios

### Workflow Won't Cancel

If a workflow doesn't properly cancel:

1. Check if the instance is in a transition state
2. Verify that the instance actually exists
3. Use the `--force` option for stuck instances:
   ```bash
   lemline instance cancel <instance-id> --force
   ```

### Workflow Won't Resume

If a suspended workflow can't be resumed:

1. Check if the workflow is actually in `SUSPENDED` state
2. Look for errors in the logs related to the instance
3. Verify that the database connection is working properly
4. Try stopping and restarting the Lemline runner

### Stuck Workflows

For workflows that appear stuck:

1. Check the current node position:
   ```bash
   lemline instance get <instance-id> --details
   ```
2. Determine if it's waiting for an event or timer
3. Verify if external systems are responsive
4. Consider cancelling and restarting if necessary

## Next Steps

- Learn about [monitoring workflow execution](lemline-howto-monitor.md)
- Explore [how to debug faults in task execution](lemline-howto-debug.md)
- Understand [how to emit and listen for events](lemline-howto-events.md)