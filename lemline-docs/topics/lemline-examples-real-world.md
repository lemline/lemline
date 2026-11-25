# Real-world workflow examples

These examples model common orchestration patterns. Supply your own service endpoints
and event producers before running a workflow that uses them.

## E-commerce order processing

Reserve stock before charging the customer. If charging fails, release the reservation
before raising an error. Keeping the reservation ID in workflow context makes it
available to the compensating task.

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

## Patient intake

Validate the request shape, register it with the intake service, and publish an event
with the request identifier. Keep clinical data out of the emitted notification unless
the consuming service requires it.

<code-block lang="yaml" src="workflows/reference/patient-intake.yaml"/>

## Financial loan application processing

Use an eligibility service response to route the application to an automatic or manual
review queue.

<code-block lang="yaml" src="workflows/reference/loan-application-review.yaml"/>

## IoT device provisioning and monitoring

Use event scheduling to process independent telemetry readings and publish an alert for
readings above a threshold.

<code-block lang="yaml" src="workflows/reference/device-telemetry.yaml"/>

See [Workflow Patterns](lemline-examples-basic-patterns.md) for smaller examples of
the constructs used here.

The [shop checkout](lemline-tutorial-order-processing.md) and [warehouse monitoring](lemline-tutorial-iot.md) examples include a local service and concrete inputs.
