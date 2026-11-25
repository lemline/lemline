# Real-world workflow examples

These examples model common orchestration patterns on the tutorial shop. Complete
[the SQLite setup](lemline-tutorial-setup.md) and run the commands from the setup directory. With the JAR, substitute
`java -jar lemline.jar` for `bin/lemline`. Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop)
from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The telemetry example at the end is started by events, so it must be registered before the worker starts. Stop the
setup worker with Ctrl+C, save the [telemetry workflow](#iot-device-provisioning-and-monitoring) as
`device-telemetry.yaml` and the publisher below as `tutorial-publisher.yaml`, register both and start the worker again:

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

```bash
bin/lemline definition post -f device-telemetry.yaml
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline listen --debug
```

Run the other commands from a second terminal in the setup directory.

## E-commerce order processing

Reserve stock before charging the customer. If charging fails, release the reservation
before raising an error. Keeping the reservation ID in workflow context makes it
available to the compensating task.

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

Save it as `compensate-reservation.yaml` and run an approved and a declined order:

```bash
bin/lemline definition post -f compensate-reservation.yaml
bin/lemline instance start examples compensate-reservation --input '{"orderId":"ORD-1301","items":[{"id":"SKU-1","quantity":2}],"paymentInfo":{"token":"approved"}}'
bin/lemline instance start examples compensate-reservation --input '{"orderId":"ORD-1302","items":[{"id":"SKU-1","quantity":2}],"paymentInfo":{"token":"declined"}}'
```

ORD-1301 is reserved, paid, shipped with the cheaper economy carrier and confirmed in the customer inbox. ORD-1302 is
declined: its reservation is released and the instance faults with `Order completion failed`.

## Customer registration

Validate the request shape, register the customer with the shop, and publish an event
with the new customer identifier. Keep personal data out of the emitted notification unless
the consuming service requires it.

<code-block lang="yaml" src="workflows/reference/patient-intake.yaml"/>

Save it as `patient-intake.yaml`:

```bash
bin/lemline definition post -f patient-intake.yaml
bin/lemline instance start examples customer-registration --input '{"name":"Katherine Johnson","email":"katherine@example.com"}'
bin/lemline instance start examples customer-registration --input '{"name":"Katherine Johnson","email":"katherine-at-example"}'
```

The first instance registers customer `C-901` and emits `com.example.customer.registered` with only its ID. The shop
refuses the second email with HTTP 422, so the instance faults before any event is emitted.

## Order review routing

Use the customer record returned by a service to route an order to an automatic or manual
review queue.

<code-block lang="yaml" src="workflows/reference/loan-application-review.yaml"/>

Save it as `loan-application-review.yaml`:

```bash
bin/lemline definition post -f loan-application-review.yaml
bin/lemline instance start examples order-review-routing --input '{"customerId":"C-100"}'
bin/lemline instance start examples order-review-routing --input '{"customerId":"C-300"}'
```

C-100 is a gold customer with a valid email: `{"queue":"automatic-review"}`. C-300 has an invalid email: `{"queue":"manual-review"}`.

## IoT device provisioning and monitoring

Use event scheduling to process independent telemetry readings and publish an alert for
readings above a threshold.

<code-block lang="yaml" src="workflows/reference/device-telemetry.yaml"/>

You registered it before starting the worker. Publish a normal and a high reading:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.device.telemetry.received","data":{"deviceId":"sensor-7","temperature":70}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.device.telemetry.received","data":{"deviceId":"sensor-7","temperature":95}}'
```

Each reading starts a `device-telemetry` instance about 10 seconds later. The first ends without an alert; the second
emits `com.example.device.temperature.high` for `sensor-7`. The [warehouse tutorial](lemline-tutorial-iot.md) adds a
second workflow that turns that alert into a notification. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

See [Workflow Patterns](lemline-examples-basic-patterns.md) for smaller examples of
the constructs used here.

The [shop checkout](lemline-tutorial-order-processing.md) and [warehouse monitoring](lemline-tutorial-iot.md) examples include a local service and concrete inputs.
