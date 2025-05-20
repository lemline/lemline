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
- name: ProcessItems
  type: for
  iterator:
    collect: ".items"
    as: "item"
  do:
    - name: ProcessItem
      type: set
      data:
        processedItem:
          id: ".item.id"
          name: ".item.name"
          status: "PROCESSED"
  next: AfterLoop
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
- name: ProcessItems
  type: for
  iterator:
    collect: ".items"
    as: "item"
  do:
    - name: ProcessWithIndex
      type: set
      data:
        result:
          id: ".item.id"
          position: "$LOOP.index"
          isLast: "$LOOP.index == $LOOP.count - 1"
          processedTime: "$WORKFLOW.currentTime"
  next: AfterLoop
```

### Collecting Results

Results from each iteration are collected in an array under the task name:

```yaml
- name: AfterLoop
  type: set
  data:
    allResults: "$WORKFLOW.ProcessItems.results"
    count: "$WORKFLOW.ProcessItems.results | length"
    message: "Processed .count items successfully"
  end: true
```

The `$WORKFLOW.ProcessItems.results` array contains the output from the last task in each iteration.

## Iterating with Complex Collections

### Nested Collections

You can iterate over nested collections using JQ expressions:

```yaml
- name: ProcessOrders
  type: for
  iterator:
    collect: ".customers | map(.orders[]) | flatten"
    as: "order"
  do:
    - name: ProcessOrder
      type: set
      data:
        processedOrder:
          id: ".order.id"
          amount: ".order.amount"
          status: "PROCESSED"
  next: AfterProcessing
```

This example flattens a nested structure to iterate over all orders from all customers.

### Filtered Collections

You can filter the collection before iteration:

```yaml
- name: ProcessPendingOrders
  type: for
  iterator:
    collect: ".orders | map(select(.status == \"PENDING\"))"
    as: "order"
  do:
    - name: ProcessPendingOrder
      type: call
      function: orderService
      data:
        orderId: ".order.id"
        action: "process"
  next: AfterProcessing
```

This example only processes orders with a "PENDING" status.

### Object Iteration

To iterate over key-value pairs in an object:

```yaml
- name: ProcessAttributes
  type: for
  iterator:
    collect: ".attributes | to_entries"
    as: "attribute"
  do:
    - name: ProcessAttribute
      type: set
      data:
        processedAttribute:
          key: ".attribute.key"
          value: ".attribute.value"
          normalized: ".attribute.value | ascii_downcase"
  next: CombineResults
```

The `to_entries` JQ function converts an object to an array of key-value pairs.

## Implementing While Loops

While Lemline doesn't have a dedicated `while` task, you can implement while-loop behavior using a combination of `for` and conditional logic:

```yaml
- name: InitializeWhileLoop
  type: set
  data:
    counter: 0
    maxIterations: 10
    condition: true
  next: WhileLoop

- name: WhileLoop
  type: switch
  conditions:
    - condition: ".condition == true && .counter < .maxIterations"
      next: LoopBody
    - condition: true
      next: AfterLoop

- name: LoopBody
  type: set
  data:
    # Do something in the loop body
    result: ".counter * 2"
    counter: ".counter + 1"
    condition: ".result < 15"  # Continue condition
  next: WhileLoop

- name: AfterLoop
  type: set
  data:
    finalCounter: ".counter"
    message: "Loop executed .counter times"
  end: true
```

This pattern implements a while loop that continues as long as `condition` is true and `counter` is less than `maxIterations`.

## Error Handling in Loops

### Using Try/Catch Inside Loops

You can handle errors within each iteration:

```yaml
- name: ProcessItems
  type: for
  iterator:
    collect: ".items"
    as: "item"
  do:
    - name: ProcessWithErrorHandling
      type: try
      retry:
        maxAttempts: 3
        interval: PT2S
      catch:
        - error: "*"
          next: HandleItemError
      do:
        - name: AttemptProcessing
          type: call
          function: processItem
          data:
            itemId: ".item.id"
    - name: HandleItemError
      type: set
      data:
        status: "ERROR"
        error: "$WORKFLOW.error"
        message: "Failed to process item .item.id"
  next: AfterLoop
```

This approach allows the loop to continue even if processing fails for some items.

### Using Try/Catch Around the Loop

You can also handle errors for the entire loop:

```yaml
- name: TryProcessingItems
  type: try
  retry:
    maxAttempts: 2
  catch:
    - error: "*"
      next: HandleLoopError
  do:
    - name: ProcessItems
      type: for
      iterator:
        collect: ".items"
        as: "item"
      do:
        - name: ProcessItem
          type: call
          function: processItem
          data:
            itemId: ".item.id"
      next: AfterLoop

- name: HandleLoopError
  type: set
  data:
    status: "LOOP_ERROR"
    error: "$WORKFLOW.error"
    message: "Loop processing failed"
  end: true
```

This approach handles errors that might occur during loop execution.

## Advanced Loop Patterns

### Batched Processing

Process items in batches instead of individually:

```yaml
- name: PrepareBatches
  type: set
  data:
    batches: ".items | _array_chunks(10)"  # Split into batches of 10
  next: ProcessBatches

- name: ProcessBatches
  type: for
  iterator:
    collect: ".batches"
    as: "batch"
  do:
    - name: ProcessBatch
      type: call
      function: batchProcessor
      data:
        items: ".batch"
  next: AfterProcessing
```

This pattern is useful for optimizing calls to external systems.

### Parallel Processing with For Loops

Combine `for` with `fork` for parallel processing:

```yaml
- name: PrepareParallelProcessing
  type: set
  data:
    batches: ".items | _array_chunks(5)"  # Split into batches of 5
  next: ParallelBatchProcessing

- name: ParallelBatchProcessing
  type: for
  iterator:
    collect: ".batches"
    as: "batch"
  do:
    - name: ProcessBatchInParallel
      type: fork
      branches:
        - name: Branch1
          tasks:
            - name: ProcessItems1
              type: call
              function: processItems
              data:
                items: ".batch[0:2]"  # First half of batch
        - name: Branch2
          tasks:
            - name: ProcessItems2
              type: call
              function: processItems
              data:
                items: ".batch[2:]"  # Second half of batch
  next: AfterProcessing
```

This pattern combines the iteration of a for loop with parallel processing.

### Implementing Paging

Handle paginated API calls:

```yaml
- name: InitializePaging
  type: set
  data:
    page: 1
    pageSize: 100
    hasMorePages: true
    allResults: []
  next: PageLoop

- name: PageLoop
  type: switch
  conditions:
    - condition: ".hasMorePages == true"
      next: FetchPage
    - condition: true
      next: ProcessAllResults

- name: FetchPage
  type: call
  function: dataService
  data:
    page: ".page"
    pageSize: ".pageSize"
  next: ProcessPageResults

- name: ProcessPageResults
  type: set
  data:
    currentResults: ".results"
    allResults: ".allResults + .results"
    page: ".page + 1"
    hasMorePages: ".results | length == .pageSize"
  next: PageLoop

- name: ProcessAllResults
  type: set
  data:
    totalCount: ".allResults | length"
    message: "Retrieved .totalCount items across .page pages"
  next: FinalProcessing
```

This pattern fetches data in pages until no more results are available.

## Real-World Example: Order Item Processing

Here's a complete example that processes items in a customer order:

```yaml
id: order-item-processor
name: Order Item Processing
version: '1.0'
specVersion: '1.0'
start: ReceiveOrder
functions:
  - name: inventoryService
    type: http
    operation: GET
    url: https://api.example.com/inventory/{itemId}
  - name: pricingService
    type: http
    operation: POST
    url: https://api.example.com/pricing/calculate
  - name: fulfillmentService
    type: http
    operation: POST
    url: https://api.example.com/fulfillment/request
tasks:
  - name: ReceiveOrder
    type: set
    data:
      orderId: "$WORKFLOW.input.orderId"
      customerId: "$WORKFLOW.input.customerId"
      items: "$WORKFLOW.input.items"
      shippingAddress: "$WORKFLOW.input.shippingAddress"
    next: ValidateOrder
  
  - name: ValidateOrder
    type: set
    data:
      isValid: ".items | length > 0"
    next: CheckOrderValidity
  
  - name: CheckOrderValidity
    type: switch
    conditions:
      - condition: ".isValid == false"
        next: RejectOrder
      - condition: true
        next: ProcessOrderItems
  
  - name: ProcessOrderItems
    type: for
    iterator:
      collect: ".items"
      as: "item"
    do:
      - name: CheckInventory
        type: try
        retry:
          maxAttempts: 3
          interval: PT2S
        catch:
          - error: "*"
            next: MarkItemUnavailable
        do:
          - name: VerifyInventory
            type: call
            function: inventoryService
            data:
              itemId: ".item.id"
      
      - name: EvaluateInventory
        type: switch
        conditions:
          - condition: ".available == true && .quantity >= .item.quantity"
            next: CalculateItemPrice
          - condition: true
            next: MarkItemUnavailable
      
      - name: CalculateItemPrice
        type: call
        function: pricingService
        data:
          itemId: ".item.id"
          quantity: ".item.quantity"
          customerId: ".customerId"
          promoCode: ".promoCode"
      
      - name: PrepareItemResult
        type: set
        data:
          processedItem:
            id: ".item.id"
            name: ".item.name"
            quantity: ".item.quantity"
            unitPrice: ".unitPrice"
            totalPrice: ".totalPrice"
            discounts: ".discounts"
            finalPrice: ".finalPrice"
            available: true
            warehouse: ".warehouse"
      
      - name: MarkItemUnavailable
        type: set
        data:
          processedItem:
            id: ".item.id"
            name: ".item.name"
            quantity: ".item.quantity"
            available: false
            reason: ".error || 'Insufficient inventory'"
    next: AnalyzeProcessedItems
  
  - name: AnalyzeProcessedItems
    type: set
    data:
      processedItems: "$WORKFLOW.ProcessOrderItems.results"
      availableItems: ".processedItems | map(select(.available == true))"
      unavailableItems: ".processedItems | map(select(.available == false))"
      allAvailable: ".unavailableItems | length == 0"
      subtotal: ".availableItems | map(.finalPrice) | add"
    next: CheckAvailability
  
  - name: CheckAvailability
    type: switch
    conditions:
      - condition: ".allAvailable == true"
        next: FulfillOrder
      - condition: ".availableItems | length > 0"
        next: FulfillPartialOrder
      - condition: true
        next: RejectUnavailableOrder
  
  - name: FulfillOrder
    type: call
    function: fulfillmentService
    data:
      orderId: ".orderId"
      customerId: ".customerId"
      items: ".availableItems"
      shippingAddress: ".shippingAddress"
    next: CompleteOrder
  
  - name: FulfillPartialOrder
    type: call
    function: fulfillmentService
    data:
      orderId: ".orderId"
      customerId: ".customerId"
      items: ".availableItems"
      shippingAddress: ".shippingAddress"
      partial: true
    next: CompletePartialOrder
  
  - name: CompleteOrder
    type: set
    data:
      status: "COMPLETED"
      message: "Order processed successfully"
      subtotal: ".subtotal"
      items: ".availableItems"
    end: true
  
  - name: CompletePartialOrder
    type: set
    data:
      status: "PARTIAL"
      message: "Order partially processed"
      subtotal: ".subtotal"
      availableItems: ".availableItems"
      unavailableItems: ".unavailableItems"
    end: true
  
  - name: RejectOrder
    type: set
    data:
      status: "REJECTED"
      reason: "Invalid order - no items specified"
    end: true
  
  - name: RejectUnavailableOrder
    type: set
    data:
      status: "REJECTED"
      reason: "All items unavailable"
      unavailableItems: ".unavailableItems"
    end: true
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