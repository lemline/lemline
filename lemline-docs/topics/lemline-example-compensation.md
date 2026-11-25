# Compensation Pattern Examples

This document provides examples of implementing compensation patterns in Lemline workflows. Compensation patterns are essential for maintaining data consistency and handling partial failures in distributed systems by implementing actions that "undo" or "compensate" for previously completed steps when errors occur.

## Run the Examples

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. Start the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source
checkout and keep [its page](http://127.0.0.1:8787) open to follow reservations, charges, refunds and documents:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The asynchronous example also needs the tutorial publisher. Save it as `tutorial-publisher.yaml` and register it:

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

```bash
bin/lemline definition post -f tutorial-publisher.yaml
```

## Basic Compensation Pattern

### Simple Two-Phase Workflow with Compensation

This example demonstrates a simple order processing workflow with reservation and charge steps, with compensation for the reservation if the charge fails.

<code-block lang="yaml" src="workflows/lemline-example-compensation/simple-two-phase-workflow-with-compensation.yaml"/>

Save it as `simple-two-phase-workflow-with-compensation.yaml`:

```bash
bin/lemline definition post -f simple-two-phase-workflow-with-compensation.yaml
bin/lemline instance start examples simple-compensation --input '{"orderId":"ORD-1501","items":[{"id":"SKU-1","quantity":1}],"paymentInfo":{"token":"approved"}}'
bin/lemline instance start examples simple-compensation --input '{"orderId":"ORD-1502","items":[{"id":"SKU-1","quantity":1}],"paymentInfo":{"token":"declined"}}'
```

ORD-1501 returns `success: true` with its reservation and the charge ID. For ORD-1502 the charge answers HTTP 402: the catch releases the reservation and returns `success: false` with the `runtime` error type.

## Multi-Step Transaction Compensation

### Saga Pattern Implementation

This example implements the Saga pattern for a multi-step business process with compensation actions for each step.

<code-block lang="yaml" src="workflows/lemline-example-compensation/saga-pattern-implementation.yaml"/>

Save it as `saga-pattern-implementation.yaml`:

```bash
bin/lemline definition post -f saga-pattern-implementation.yaml
bin/lemline instance start examples order-saga --input '{"orderId":"ORD-1503","items":[{"id":"SKU-1","quantity":1}],"paymentInfo":{"token":"approved"}}'
bin/lemline instance start examples order-saga --input '{"orderId":"ORD-1504","items":[{"id":"SKU-1","quantity":1}],"paymentInfo":{"token":"approved"},"shippingDown":true}'
bin/lemline instance start examples order-saga --input '{"orderId":"ORD-1505","items":[{"id":"SKU-1","quantity":1}],"paymentInfo":{"token":"approved"},"shippingDown":true,"refundsDown":true}'
bin/lemline instance start examples order-saga --input '{"orderId":"ORD-1506","items":[{"id":"SKU-1","quantity":1}],"paymentInfo":{"token":"declined"}}'
```

| Order | What fails | Compensation in the shop | Result |
|---|---|---|---|
| ORD-1503 | Nothing | None: the order ships with express | The shipment |
| ORD-1504 | The shipment (HTTP 503) | Payment refunded, then reservation released | `success: false`, `cleanupFailed: []` |
| ORD-1505 | The shipment, then the refund (HTTP 500) | Reservation released; the charge stays | `cleanupFailed: ["refundPayment"]` |
| ORD-1506 | The charge (HTTP 402) | No refund to make; reservation released | `cleanupFailed: []` |

Compensations run in reverse order of the steps, and each one is attempted even when an earlier one fails.

## Compensation with Retry

### Resilient Order Processing with Retries and Compensation

This example demonstrates combining retry policies with compensation actions for a more resilient workflow.

<code-block lang="yaml" src="workflows/lemline-example-compensation/resilient-order-processing-with-retries-and-compensation.yaml"/>

Save it as `resilient-order-processing-with-retries-and-compensation.yaml`:

```bash
bin/lemline definition post -f resilient-order-processing-with-retries-and-compensation.yaml
bin/lemline instance start examples resilient-order-processing --input '{"orderId":"ORD-1507","items":[{"id":"SKU-1","quantity":1}],"paymentInfo":{"token":"approved"},"paymentFailures":2}'
bin/lemline instance start examples resilient-order-processing --input '{"orderId":"ORD-1508","items":[{"id":"SKU-1","quantity":1}],"paymentInfo":{"token":"declined"}}'
```

ORD-1507 fails twice with HTTP 503, then is charged on the second retry; the reservation stays. ORD-1508 is declined on the call and on all three retries; the catch then releases the reservation and returns `success: false`. The `Idempotency-Key` header, visible in the shop's request list, lets a payment service recognize a retried charge. Allow about a minute: the worker picks up retries every 10 seconds.

## Compensation Strategies for Different Resource Types

### Transaction Management with Mixed Resource Types

This example allocates two kinds of resources (a record and a file) and, when a later step fails, deletes each resource that was created.

<code-block lang="yaml" src="workflows/lemline-example-compensation/transaction-management-with-mixed-resource-types.yaml"/>

Save it as `transaction-management-with-mixed-resource-types.yaml`:

```bash
bin/lemline definition post -f transaction-management-with-mixed-resource-types.yaml
bin/lemline instance start examples allocate-mixed-resources --input '{"name":"invoice-1509","content":"Invoice for ORD-1509"}'
bin/lemline instance start examples allocate-mixed-resources --input '{"name":"invoice-1510","content":"Invoice for ORD-1510","attachFails":true}'
```

For `invoice-1509`, the shop holds a record whose `attachmentId` points to the file. For `invoice-1510`, attaching fails with HTTP 503: the catch deletes the file, then the record, and both documents show `status: deleted`.

## Asynchronous Compensation

### Long-Running Process with Asynchronous Compensation

This example reserves stock, then waits up to 30 seconds for a payment confirmation event; a real payment would get minutes. If no confirmation arrives, the `listen` task times out and the `catch` releases the reservation.

<code-block lang="yaml" src="workflows/lemline-example-compensation/long-running-process-with-asynchronous-compensation.yaml"/>

Save it as `long-running-process-with-asynchronous-compensation.yaml`:

```bash
bin/lemline definition post -f long-running-process-with-asynchronous-compensation.yaml
bin/lemline instance start examples asynchronous-compensation --input '{"orderId":"ORD-1511","items":[{"id":"SKU-1","quantity":1}]}'
```

Confirm the payment while it waits:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.confirmed","data":{"orderId":"ORD-1511"}}'
```

The instance completes with `{"status":"confirmed"}` and the reservation stays. Start a second order and send nothing:

```bash
bin/lemline instance start examples asynchronous-compensation --input '{"orderId":"ORD-1512","items":[{"id":"SKU-1","quantity":1}]}'
```

After 30 to 40 seconds the listener times out, the catch releases the reservation, and the instance completes with
`{"status":"expired"}`. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Best Practices for Compensation Patterns

When implementing compensation patterns in Lemline workflows, consider these best practices:

1. **Resource Tracking**: Keep track of all resources allocated during workflow execution
2. **Compensate in Reverse Order**: Apply compensating actions in reverse order of the original operations
3. **Idempotent Compensation**: Design compensation actions to be safely retryable
4. **Partial Compensation**: Handle scenarios where only some steps need compensation
5. **Async Compensation**: For long-running processes, implement asynchronous compensation
6. **Compensation Logging**: Log all compensation actions for auditing and troubleshooting
7. **Escalation Policy**: Define clear escalation paths when compensation fails
8. **Independent Compensation**: Design compensation actions that don't depend on workflow state
9. **Explicit Cleanup**: Always explicitly clean up resources, don't rely on implicit cleanup
10. **Timeouts for Compensation**: Set appropriate timeouts for compensation actions (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)

## Conclusion

These examples demonstrate various compensation patterns for handling partial failures and maintaining data consistency in distributed workflows. By implementing proper compensation strategies, you can build resilient workflows that gracefully handle failures at any stage of execution.

Compensation patterns are essential for maintaining data integrity in distributed systems, preventing resource leaks, and ensuring that business processes remain in a consistent state even when technical failures occur.

For more examples of error handling and resilience patterns, see the [Error Handling Examples](lemline-examples-error-handling.md) document.