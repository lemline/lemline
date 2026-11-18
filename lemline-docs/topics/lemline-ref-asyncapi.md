# AsyncAPI Reference

This page describes the support of `call: asyncapi` tasks in Lemline.

## AsyncAPI Support Status

Lemline does not execute `call: asyncapi` tasks yet. The Open Workflow DSL defines them (see [AsyncAPI calls](dsl-call-asyncapi.md)), and Lemline parses workflows that contain them, but no runner can run such a task: do not use it in workflows you deploy.

There are no `lemline.asyncapi.*` runner settings.

## Alternative

Exchange messages as CloudEvents with `emit` and `listen` tasks. They travel on the broker Lemline is configured with (see [Message Brokers](lemline-howto-brokers.md)):

```yaml
do:
  - publishOrder:
      emit:
        event:
          with:
            type: com.example.order.created
            source: order-service
            data: ${ .order }
  - waitForPayment:
      listen:
        to:
          one:
            with:
              type: com.example.payment.received
```

## Related Resources

- [HTTP Protocol Reference](lemline-ref-http.md)
- [Supported Features](lemline-ref-supported-features.md)
