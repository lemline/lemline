---
title: How to run loops (for, while)
---

# How to run loops (for, while)

This guide explains how to implement iteration patterns in Lemline workflows using `for` tasks and other loop constructs. You'll learn how to iterate over collections, implement conditional loops, and handle loop-specific error cases.

## Understanding Loops in Workflows

Loops allow you to repeat a sequence of tasks multiple times, either:
- Over each item in a collection (forEach pattern)
- Until a condition is met (while pattern)
- A fixed number of times (count pattern)

Lemline provides several approaches to implement these patterns.

## Using the For Task

The primary way to implement loops in Lemline is using the `for` task type. The `for` task iterates over items in a collection and executes a series of tasks for each item.

### Basic For Loop Structure

```yaml
  - ProcessItems:
      for:
        each: "item"
        in: ${ .items }
      do:
        - ProcessItem:
            set:
              processedItem:
                id: ${ .item.id }
                name: ${ .item.name }
                status: "PROCESSED"
      then: AfterLoop
```

This example iterates over an array of items, processing each one in turn.

### Key Components of a For Loop

- `iterator.collect`: A JQ expression that evaluates to the collection to iterate over
- `iterator.as`: The name to assign each item during iteration
- `do`: A list of tasks to execute for each item
- `next`: The task to execute after the loop completes

### Accessing Loop Variables

During loop execution, you can access:
- The current item using the name specified in `iterator.as`
- The current index using `$LOOP.index`
- The total count using `$LOOP.count`

```yaml
  - ProcessItems:
      for:
        each: "item"
        in: ${ .items }
      do:
        - ProcessWithIndex:
            set:
              result:
                id: ${ .item.id }
                position: ${ $LOOP.index }
                isLast: ${ $LOOP.index == $LOOP.count - 1 }
                processedTime: ${ $context.currentTime }
      then: AfterLoop
```

### Collecting Results

Results from each iteration are collected in an array under the task name:

```yaml
  - AfterLoop:
      set:
        allResults: ${ $context.ProcessItems.results }
        count: ${ $context.ProcessItems.results | length }
        message: ${ "Processed \(.count) items successfully" }
      then: end
```

The `$WORKFLOW.ProcessItems.results` array contains the output from the last task in each iteration.

## Iterating with Complex Collections

### Nested Collections

You can iterate over nested collections using JQ expressions:

```yaml
  - ProcessOrders:
      for:
        each: "order"
        in: ${ .customers | map(.orders[]) | flatten }
      do:
        - ProcessOrder:
            set:
              processedOrder:
                id: ${ .order.id }
                amount: ${ .order.amount }
                status: "PROCESSED"
      then: AfterProcessing
```

This example flattens a nested structure to iterate over all orders from all customers.

### Filtered Collections

You can filter the collection before iteration:

```yaml
  - ProcessPendingOrders:
      for:
        each: "order"
        in: ${ .orders | map(select(.status == "PENDING")) }
      do:
        - ProcessPendingOrder:
            call: orderService
            with:
              orderId: ${ .order.id }
              action: "process"
      then: AfterProcessing
```

This example only processes orders with a "PENDING" status.

### Object Iteration

To iterate over key-value pairs in an object:

```yaml
  - ProcessAttributes:
      for:
        each: "attribute"
        in: ${ .attributes | to_entries }
      do:
        - ProcessAttribute:
            set:
              processedAttribute:
                key: ${ .attribute.key }
                value: ${ .attribute.value }
                normalized: ${ .attribute.value | ascii_downcase }
      then: CombineResults
```

The `to_entries` JQ function converts an object to an array of key-value pairs.

## Implementing While Loops

While Lemline doesn't have a dedicated `while` task, you can implement while-loop behavior using a combination of `for` and conditional logic:

```yaml
  - InitializeWhileLoop:
      set:
        counter: 0
        maxIterations: 10
        condition: true
      then: WhileLoop
  - WhileLoop:
      switch:
        - case1:
            then: LoopBody
            when: ${ .condition == true and .counter < .maxIterations }
        - default:
            then: AfterLoop
  - LoopBody:
      set:
        result: ${ .counter * 2 }
        counter: ${ .counter + 1 }
        condition: ${ .result < 15 }
      then: WhileLoop
  - AfterLoop:
      set:
        finalCounter: ${ .counter }
        message: ${ "Loop executed \(.counter) times" }
      then: end
```

This pattern implements a while loop that continues as long as `condition` is true and `counter` is less than `maxIterations`.

## Error Handling in Loops

### Using Try/Catch Inside Loops

You can handle errors within each iteration:

```yaml
  - ProcessItems:
      for:
        each: "item"
        in: ${ .items }
      do:
        - ProcessWithErrorHandling:
            try:
              - AttemptProcessing:
                  call: processItem
                  with:
                    itemId: ${ .item.id }
            catch:
              as: failure
              then: HandleItemError
              retry:
                limit:
                  attempt:
                    count: 3
                delay: PT2S
        - HandleItemError:
            set:
              status: "ERROR"
              error: ${ $context.error }
              message: ${ "Failed to process item \(.item.id)" }
      then: AfterLoop
```

This approach allows the loop to continue even if processing fails for some items.

### Using Try/Catch Around the Loop

You can also handle errors for the entire loop:

```yaml
  - TryProcessingItems:
      try:
        - ProcessItems:
            for:
              each: "item"
              in: ${ .items }
            do:
              - ProcessItem:
                  call: processItem
                  with:
                    itemId: ${ .item.id }
            then: AfterLoop
      catch:
        as: failure
        then: HandleLoopError
        retry:
          limit:
            attempt:
              count: 2
  - HandleLoopError:
      set:
        status: "LOOP_ERROR"
        error: ${ $context.error }
        message: "Loop processing failed"
      then: end
```

This approach handles errors that might occur during loop execution.

## Advanced Loop Patterns

### Batched Processing

Process items in batches instead of individually:

```yaml
  - PrepareBatches:
      set:
        batches: ${ .items | _array_chunks(10) }
      then: ProcessBatches
  - ProcessBatches:
      for:
        each: "batch"
        in: ${ .batches }
      do:
        - ProcessBatch:
            call: batchProcessor
            with:
              items: ${ .batch }
      then: AfterProcessing
```

This pattern is useful for optimizing calls to external systems.

### Parallel Processing with For Loops

Combine `for` with `fork` for parallel processing:

```yaml
  - PrepareParallelProcessing:
      set:
        batches: ${ .items | _array_chunks(5) }
      then: ParallelBatchProcessing
  - ParallelBatchProcessing:
      for:
        each: "batch"
        in: ${ .batches }
      do:
        - ProcessBatchInParallel:
            fork:
              branches:
                - Branch1:
                    do:
                      - ProcessItems1:
                          call: processItems
                          with:
                            items: ${ .batch[0:2] }
                - Branch2:
                    do:
                      - ProcessItems2:
                          call: processItems
                          with:
                            items: ${ .batch[2:] }
      then: AfterProcessing
```

This pattern combines the iteration of a for loop with parallel processing.

### Implementing Paging

Handle paginated API calls:

```yaml
  - InitializePaging:
      set:
        page: 1
        pageSize: 100
        hasMorePages: true
        allResults: []
      then: PageLoop
  - PageLoop:
      switch:
        - case1:
            then: FetchPage
            when: ${ .hasMorePages == true }
        - default:
            then: ProcessAllResults
  - FetchPage:
      call: dataService
      with:
        page: ${ .page }
        pageSize: ${ .pageSize }
      then: ProcessPageResults
  - ProcessPageResults:
      set:
        currentResults: ${ .results }
        allResults: ${ .allResults + .results }
        page: ${ .page + 1 }
        hasMorePages: ${ .results | length == .pageSize }
      then: PageLoop
  - ProcessAllResults:
      set:
        totalCount: ${ .allResults | length }
        message: ${ "Retrieved \(.totalCount) items across \(.page) pages" }
      then: FinalProcessing
```

This pattern fetches data in pages until no more results are available.

## Real-World Example: Order Item Processing

Here's a complete example that processes items in a customer order:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: order-item-processor
  version: 1.0.0
  title: Order Item Processing
do:
  - ReceiveOrder:
      set:
        orderId: ${ $workflow.input.orderId }
        customerId: ${ $workflow.input.customerId }
        items: ${ $workflow.input.items }
        shippingAddress: ${ $workflow.input.shippingAddress }
      then: ValidateOrder
  - ValidateOrder:
      set:
        isValid: ${ .items | length > 0 }
      then: CheckOrderValidity
  - CheckOrderValidity:
      switch:
        - case1:
            then: RejectOrder
            when: ${ .isValid == false }
        - default:
            then: ProcessOrderItems
  - ProcessOrderItems:
      for:
        each: "item"
        in: ${ .items }
      do:
        - CheckInventory:
            try:
              - VerifyInventory:
                  call: http
                  with:
                    method: get
                    endpoint: https://api.example.com/inventory/{itemId}
                    query:
                      itemId: ${ .item.id }
            catch:
              as: failure
              then: MarkItemUnavailable
              retry:
                limit:
                  attempt:
                    count: 3
                delay: PT2S
        - EvaluateInventory:
            switch:
              - case1:
                  then: CalculateItemPrice
                  when: ${ .available == true and .quantity >= .item.quantity }
              - default:
                  then: MarkItemUnavailable
        - CalculateItemPrice:
            call: http
            with:
              method: post
              endpoint: https://api.example.com/pricing/calculate
              body:
                itemId: ${ .item.id }
                quantity: ${ .item.quantity }
                customerId: ${ .customerId }
                promoCode: ${ .promoCode }
        - PrepareItemResult:
            set:
              processedItem:
                id: ${ .item.id }
                name: ${ .item.name }
                quantity: ${ .item.quantity }
                unitPrice: ${ .unitPrice }
                totalPrice: ${ .totalPrice }
                discounts: ${ .discounts }
                finalPrice: ${ .finalPrice }
                available: true
                warehouse: ${ .warehouse }
        - MarkItemUnavailable:
            set:
              processedItem:
                id: ${ .item.id }
                name: ${ .item.name }
                quantity: ${ .item.quantity }
                available: false
                reason: ${ .error // "Insufficient inventory" }
      then: AnalyzeProcessedItems
  - AnalyzeProcessedItems:
      set:
        processedItems: ${ $context.ProcessOrderItems.results }
        availableItems: ${ .processedItems | map(select(.available == true)) }
        unavailableItems: ${ .processedItems | map(select(.available == false)) }
        allAvailable: ${ .unavailableItems | length == 0 }
        subtotal: ${ .availableItems | map(.finalPrice) | add }
      then: CheckAvailability
  - CheckAvailability:
      switch:
        - case1:
            then: FulfillOrder
            when: ${ .allAvailable == true }
        - case2:
            then: FulfillPartialOrder
            when: ${ .availableItems | length > 0 }
        - default:
            then: RejectUnavailableOrder
  - FulfillOrder:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/fulfillment/request
        body:
          orderId: ${ .orderId }
          customerId: ${ .customerId }
          items: ${ .availableItems }
          shippingAddress: ${ .shippingAddress }
      then: CompleteOrder
  - FulfillPartialOrder:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/fulfillment/request
        body:
          orderId: ${ .orderId }
          customerId: ${ .customerId }
          items: ${ .availableItems }
          shippingAddress: ${ .shippingAddress }
          partial: true
      then: CompletePartialOrder
  - CompleteOrder:
      set:
        status: "COMPLETED"
        message: "Order processed successfully"
        subtotal: ${ .subtotal }
        items: ${ .availableItems }
      then: end
  - CompletePartialOrder:
      set:
        status: "PARTIAL"
        message: "Order partially processed"
        subtotal: ${ .subtotal }
        availableItems: ${ .availableItems }
        unavailableItems: ${ .unavailableItems }
      then: end
  - RejectOrder:
      set:
        status: "REJECTED"
        reason: "Invalid order - no items specified"
      then: end
  - RejectUnavailableOrder:
      set:
        status: "REJECTED"
        reason: "All items unavailable"
        unavailableItems: ${ .unavailableItems }
      then: end
```

This workflow processes each item in an order, checking inventory and calculating prices before fulfilling the order.

## Best Practices for Loops

1. **Avoid infinite loops**: Always include a termination condition for while-loop patterns
2. **Handle empty collections**: Consider what should happen if the collection to iterate is empty
3. **Batch appropriately**: Process items in batches when appropriate to reduce API calls
4. **Include error handling**: Plan for failures during iteration
5. **Consider resource usage**: Be mindful of how many iterations might occur
6. **Use parallel processing selectively**: Not all loops benefit from parallelization
7. **Monitor loop execution**: Track and log metrics about loop iterations
8. **Consider transactionality**: Determine if partial success is acceptable or if all-or-nothing is required

## Limitations and Edge Cases

1. **Memory usage**: Very large collections may impact memory usage
2. **Loop duration**: Long-running loops may time out or be affected by environment constraints
3. **State management**: Complex loops may generate large state objects
4. **External rate limits**: Loops making API calls may be subject to rate limiting
5. **Loop interruption**: Consider what happens if a workflow is suspended during a loop

## Next Steps

- Learn about [executing tasks in parallel](lemline-howto-parallel.md)
- Explore [how to jump between tasks](lemline-howto-jumps.md)
- Understand [data passing in workflows](lemline-howto-data-passing.md)