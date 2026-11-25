# Emit

An `emit` task publishes a [CloudEvent](https://cloudevents.io/). Set the event properties
under `emit.event.with`. The `source` identifies the producer, `type` identifies the event,
and `subject` identifies the order in this example. Use `data` for the result the consumer needs.

## Publish an Order Result

The workflow attempts to reserve stock. It publishes a reserved event when the shop accepts
the request or a rejected event with the HTTP status when the request fails.

<code-block lang="yaml" src="workflows/dsl-task-emit/emit-order-result.yaml"/>

## Read the Published Event

This listener waits for the result of a particular order and reads the event envelope. Its output
selects the type, subject, and data so you can see the result the publisher sent.

<code-block lang="yaml" src="workflows/dsl-task-emit/listen-order-result.yaml"/>

For more on event filters, see [Listen](dsl-task-listen.md) and [Event Correlation](dsl-event-correlation.md).

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Start each listener before its publisher. The pauses give the worker time to register the listener.

```bash
bin/lemline definition post -f listen-order-result.yaml
bin/lemline definition post -f emit-order-result.yaml
bin/lemline instance start examples listen-order-result --input '{"orderId":"O-160"}'
sleep 12
bin/lemline instance start examples emit-order-result --input '{"orderId":"O-160","items":[{"id":"SKU-1","quantity":1}]}'
bin/lemline instance start examples listen-order-result --input '{"orderId":"O-161"}'
sleep 12
bin/lemline instance start examples emit-order-result --input '{"orderId":"O-161","items":[{"id":"SKU-9","quantity":1}]}'
sleep 12
```

| Example | Expected result |
|---|---|
| `listen-order-result` for `O-160` | `{"type":"com.example.order.reserved","subject":"O-160","data":{"orderId":"O-160","status":"reserved"}}` |
| `listen-order-result` for `O-161` | `{"type":"com.example.order.rejected","subject":"O-161","data":{"orderId":"O-161","status":"rejected","httpStatus":400}}` |
| `emit-order-result` | Completes with `sentFor` equal to the order ID after publishing the event. |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
