# Understanding Fan-In and Fan-Out Patterns

A fork starts a fixed set of independent branches on the same input. Its successor runs once and receives an array of branch outputs in declaration order. This workflow reads the profile and orders for one customer concurrently and returns a single object.

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: customer-summary
  version: '1.0.0'
do:
  - fetchCustomer:
      fork:
        branches:
          - profile:
              call: http
              with:
                method: get
                endpoint: '${ "https://customers.example.com/customers/\(.customerId | @uri)" }'
          - orders:
              call: http
              with:
                method: get
                endpoint: '${ "https://orders.example.com/customers/\(.customerId | @uri)/orders" }'
      output:
        as: '${ {profile: .[0], orders: .[1]} }'
```

Set `fork.compete: true` for a race between equivalent sources. The first successful branch supplies the output. When the caller requires results from every service, leave `compete` at its default and handle recoverable failures inside each branch. Apply a task timeout to bound a slow request.

## Distribute a variable number of items

Use `for` to iterate over the input. This task emits one event per item. Independent consumers can process those events concurrently. Include the batch identifier in each event so a listener can collect the results for the correct batch.

```yaml
- dispatch:
    for:
      each: item
      in: ${ $workflow.input.items }
    do:
      - publish:
          emit:
            event:
              with:
                source: https://example.com/batches
                type: ItemRequested
                data:
                  batchId: ${ $workflow.input.batchId }
                  item: ${ $item }
```

Worker capacity determines concurrency in this pattern. A DSL `for` task processes its iterations sequentially. For chunked processing, pass batches as the iteration values and send each batch in one request.

## Collect correlated results

A workflow with a positive expected count can wait for that many matching events. The correlation rule reads the batch identifier from each CloudEvent and compares it with the workflow input. The timeout bounds the wait if a consumer fails to report.

```yaml
- collectResults:
    listen:
      to:
        any:
          - with: { type: ItemProcessed }
            correlate:
              batchId:
                from: ${ .data.batchId }
                expect: ${ $workflow.input.batchId }
        until: ${ length >= $workflow.input.expectedCount }
      read: data
    timeout: { after: PT1H }
- summarize:
    set:
      total: ${ length }
      successful: ${ map(select(.status == "success")) | length }
      failed: ${ map(select(.status == "failed")) | length }
```

This condition counts events. If the transport can redeliver a result, include an item ID and count distinct IDs in the completion condition. Use a durable result store when consumers can finish before a workflow has registered its listener.

See the [fork task](dsl-task-fork.md), [listen task](dsl-task-listen.md) and [event correlation](dsl-event-correlation.md) references.
