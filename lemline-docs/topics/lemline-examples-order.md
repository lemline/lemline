---
title: Order Processing Workflow Example
---

# Order Processing Workflow Example

This example demonstrates a complete order processing workflow that handles validation, payment processing, inventory management, shipping, and notifications. It showcases many of Lemline's features including error handling, event correlation, and data transformation.

## Use Case Overview

This workflow implements an e-commerce order processing pipeline that:

1. Receives an order request
2. Validates customer and order details
3. Checks inventory availability
4. Processes payment
5. Allocates inventory
6. Creates a shipment
7. Waits for shipping confirmation
8. Sends order confirmations and updates
9. Handles potential failures at each step

## Full Workflow Definition

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

## Key Features Demonstrated

### 1. Workflow Organization

This workflow demonstrates good organization practices:
- Clear task naming that describes the purpose
- Logical flow grouping (validation, inventory, payment, shipping)
- Comprehensive error handling for each major section
- Proper event emission for important state changes

### 2. Error Handling

The workflow implements a robust error handling strategy:
- Specific error handlers for different error types
- Retry policies for transient failures
- Rollback operations for partial failures (payment, inventory)
- Proper customer notifications for different error scenarios

### 3. Event Correlation

The workflow uses event correlation to integrate with external systems:
- Waits for shipment updates correlated by order ID
- Handles timeout with a fallback path to check status manually
- Emits events at key lifecycle points

### 4. Data Transformations

Various data transformation patterns are demonstrated:
- Building structured notification messages
- Calculating processing durations
- Constructing summary objects
- Mapping and filtering array data

### 5. Conditional Processing

The workflow includes multiple decision points:
- Validating input conditions
- Checking inventory availability
- Evaluating payment results
- Processing shipment status

### 6. Compensation Logic

For handling partial failures, the workflow includes compensation logic:
- Refunding payments if inventory allocation fails
- Releasing allocated inventory if shipment creation fails
- Adjusting orders for partial inventory availability

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

Replace `<namespace>` and `<name>` with the values declared in the `document` section. To simulate the shipment status update, publish a `ShipmentStatusUpdate` CloudEvent on the CloudEvents topic or queue of that namespace with your broker's tools; Lemline has no CLI command to publish events.

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