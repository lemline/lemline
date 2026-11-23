---
title: Order Processing Workflow Example
---

# Order Processing Workflow Example

This example shows the core of an order processing workflow: reserve the stock, charge the payment, and release the stock if the charge fails. It showcases error handling, compensation, and data shared between tasks.

## Use Case Overview

This workflow implements the payment step of an e-commerce order:

1. Reserves the ordered items in the inventory service
2. Keeps the reservation identifier for later tasks
3. Charges the payment
4. If the charge fails, releases the reservation
5. Reports the failure with an error that the caller can catch

## Full Workflow Definition

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

## Key Features Demonstrated

### 1. Workflow Organization

- Task names describe their purpose (`reserveInventory`, `chargePayment`, `releaseInventory`)
- The step that can fail is isolated in its own `try` task

### 2. Data Shared Between Tasks

- Every task reads the order from `$workflow.input`, whatever the previous task returned
- `reserveInventory` stores the reservation identifier in `$context` with `export.as`, so the compensation can read it later

### 3. Error Handling

- The `catch` receives any error raised by the payment call
- After compensating, the workflow raises its own error (`order-failed`), so the instance ends as faulted instead of completing silently

### 4. Compensation Logic

- The reservation made before the `try` is released in `catch.do`
- The reservation identifier is URL-encoded with `@uri` before being placed in the endpoint

## How to Use This Example

You can use this example as a starting point for your own order processing workflows. To adapt it to your environment:

1. Replace the function URLs with your actual service endpoints
2. Adjust the data structures to match your specific schema
3. Modify the error handling to align with your business processes
4. Customize the notification messages to match your brand voice
5. Add or remove steps based on your specific requirements

## Testing This Workflow

To test this workflow locally, with a runner (`lemline listen`) running:

```bash
# Register the definition
lemline definition post -f order-processing.yaml

# Start the workflow with a test order
lemline instance start <namespace> <name> --input "$(cat orders/test-order.json)"
```

Replace `<namespace>` and `<name>` with the values declared in the `document` section.

To run it without calling the real services, start the runner with `lemline listen --mock-config <file>`.

## Related Resources

To learn more about the concepts demonstrated in this example:

- [How to define a workflow](lemline-howto-define-workflow.md)
- [How to make HTTP calls](lemline-howto-http.md)
- [How to emit and listen for events](lemline-howto-events.md)
- [How to use try/catch to handle faults](lemline-howto-try-catch.md)
- [Data passing between tasks](lemline-howto-data-passing.md)

## Performance Considerations

This workflow is designed for reliability and correctness. For high-volume scenarios, consider these optimizations:

1. **Parallel Processing**: Use the `fork` task to parallelize independent operations
2. **Batched Operations**: Group related items for inventory or shipping operations
3. **Event Streaming**: Use streaming responses for higher throughput
5. **Response Caching**: Cache customer or product data that doesn't change frequently