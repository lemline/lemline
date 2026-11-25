# AsyncAPI Reference

This page describes the support of `call: asyncapi` tasks in Lemline.

## AsyncAPI Support Status

Lemline does not execute `call: asyncapi` tasks yet. The Open Workflow DSL defines them (see [AsyncAPI calls](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#asyncapi-call)), and Lemline parses workflows that contain them, but no runner can run such a task: do not use it in workflows you deploy.

There are no `lemline.asyncapi.*` runner settings.

## Alternative

Exchange messages as CloudEvents with `emit` and `listen` tasks. They travel on the broker Lemline is configured with (see [Message Brokers](lemline-howto-brokers.md)):

<code-block lang="yaml" src="workflows/lemline-ref-asyncapi/alternative.yaml"/>

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

The event examples need the tutorial publisher. Save it as `tutorial-publisher.yaml`:

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

Keep the setup worker running and use a second terminal in the setup directory.

```bash
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline definition post -f alternative.yaml
bin/lemline instance start examples asyncapi-alternative --input '{"order":{"orderId":"ORD-1701"}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.received","data":{"orderId":"ORD-1701"}}'
```

| Example | Expected result |
|---|---|
| `asyncapi-alternative` | Emits `com.example.order.created`, waits, then completes with `[{"orderId":"ORD-1701"}]` after the payment event |

## Related Resources

- [HTTP Protocol Reference](lemline-ref-http.md)
- [Supported Features](lemline-ref-supported-features.md)
