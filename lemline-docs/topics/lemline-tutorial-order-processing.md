---
title: "Tutorial: Database-Less Order Processing"
---

# Tutorial: Database-Less Order Processing

This example shows how workflow context can carry an external reservation ID across
tasks. Lemline persists orchestration state. The inventory and payment services own
their business records.

## Read the definition

The workflow reserves inventory, saves the reservation ID in context, then calls
a payment service. If payment fails, the catch task list releases the reservation
and raises an error. The workflow data passed to the payment service comes from
the original input.

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

## Try it with your services

Replace the inventory and payment URLs with services you can test against. The
reservation endpoint should return an `id` in its response. Use an idempotent
release operation so a retried request can safely release the same reservation.

Save the file as `compensate-reservation.yaml`. Follow
[Getting Started](lemline-getting-started.md) to prepare a runner for the
`examples` namespace, then register the workflow and start it with an order payload.

```bash
lemline definition post -f compensate-reservation.yaml
lemline instance start examples compensate-reservation --input '{"orderId":"ORD-123","items":[{"id":"SKU-1","quantity":2}],"paymentInfo":{"token":"example-token"}}'
```

Test a successful charge and a failed charge. Inspect the payment and inventory
services to verify that the reservation is released on the failure path.

See [Event Correlation](dsl-event-correlation.md) to extend the pattern with a
payment confirmation event.
