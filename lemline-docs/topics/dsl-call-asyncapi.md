---
title: AsyncAPI Call
---

# AsyncAPI Call Task (`call: asyncapi`)

An AsyncAPI call selects an operation from an [AsyncAPI document](https://www.asyncapi.com/docs/reference/specification/v3.0.0).
The operation describes whether the application sends or receives a message and identifies the channel.
The tutorial shop serves a sample contract with one payment channel and two operations.

> **Lemline**
>
> Lemline accepts these workflow definitions at registration. Starting an instance that reaches
> `call: asyncapi` faults with `Unknown task type: CallAsyncAPI`. The shop serves the
> sample contract over HTTP. Use `emit` and `listen` for the executable event exchange below.

## Publish a Message

Select the `publishPayment` operation and pass the order ID as message data.

<code-block lang="yaml" src="workflows/dsl-call-asyncapi/asyncapi-publish-payment.yaml"/>

## Subscribe to Messages

Select the `receivePayment` operation. The subscription asks for one matching message and limits
the time spent consuming messages.

<code-block lang="yaml" src="workflows/dsl-call-asyncapi/asyncapi-receive-payment.yaml"/>

## Exchange CloudEvents

This listener waits for a payment with the requested order ID. The publisher sends that payment
as a CloudEvent through the runtime’s event channel.

<code-block lang="yaml" src="workflows/reference/listen-for-payment.yaml"/>

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

## Configuration Options

| Property | Purpose |
|---|---|
| `with.document.endpoint` | URL of the AsyncAPI description. |
| `with.operation` | Operation identifier in that description. |
| `with.server` | Server name and optional template variable values. |
| `with.protocol` | Transport protocol used for the operation. |
| `with.message` | Payload and optional headers for a send operation. |
| `with.subscription` | Consumer filter and limits for a receive operation. |

See [Listen](dsl-task-listen.md) and [Emit](dsl-task-emit.md) for the equivalent DSL event tasks.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Fetch the sample contract from the shop to inspect its channels.

```bash
curl --retry 10 --retry-connrefused --retry-delay 1 -fsS http://127.0.0.1:8787/asyncapi.json
```

Use the setup terminal to register the definitions and start both AsyncAPI workflows.

```bash
bin/lemline definition post -f asyncapi-publish-payment.yaml
bin/lemline instance start examples asyncapi-publish-payment --input '{"orderId":"O-170"}'
bin/lemline definition post -f asyncapi-receive-payment.yaml
bin/lemline instance start examples asyncapi-receive-payment --input '{"orderId":"O-170"}'
```

Start the listener in the same terminal. Wait for it to register before publishing the payment event.

```bash
bin/lemline definition post -f listen-for-payment.yaml
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline instance start examples listen-for-payment --input '{"orderId":"O-170"}'
sleep 12
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.received","data":{"orderId":"O-170"}}'
```

| Example | Expected result |
|---|---|
| `asyncapi-publish-payment` | Registers, then faults with `Unknown task type: CallAsyncAPI`. |
| `asyncapi-receive-payment` | Registers, then faults with `Unknown task type: CallAsyncAPI`. |
| `listen-for-payment` | Returns `[{"orderId":"O-170"}]` after receiving the payment event. |
| `tutorial-publisher` | Completes after emitting the payment event. |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
