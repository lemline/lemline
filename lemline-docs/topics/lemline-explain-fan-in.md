# Understanding Fan-In and Fan-Out Patterns

This document explains the concepts of fan-out and fan-in patterns in workflow orchestration and how they are implemented in Lemline.

## What are Fan-Out and Fan-In Patterns?

Fan-out and fan-in are complementary patterns used to handle parallel processing in workflows:

- **Fan-Out**: Distributing a task across multiple parallel workers or processes
- **Fan-In**: Collecting and aggregating results from multiple parallel processes

These patterns enable efficient parallel processing while maintaining overall workflow coordination.

## Why Use Fan-Out/Fan-In?

These patterns offer several benefits:

1. **Improved Performance**: Process multiple items simultaneously
2. **Reduced Latency**: Complete work faster with parallel execution
3. **Resource Utilization**: Make better use of available compute resources
4. **Scalability**: Handle varying workloads by adjusting parallelism
5. **Fault Isolation**: Prevent failures in one branch from affecting others

## Fan-Out Patterns in Lemline

Lemline supports multiple fan-out patterns:

### 1. Fork Task Fan-Out

The `fork` task provides explicit parallel execution:

```yaml
- processInParallel:
    fork:
      branches:
        - processPayment:
            callHTTP:
              url: "https://payment-service/process"
              method: "POST"
              body: "${ .payment }"
        
        - updateInventory:
            callHTTP:
              url: "https://inventory-service/update"
              method: "POST"
              body: "${ .items }"
        
        - notifyShipping:
            callHTTP:
              url: "https://shipping-service/schedule"
              method: "POST"
              body: "${ .shipping }"
```

This pattern:
- Executes all branches in parallel
- Continues when all branches complete
- Collects results from all branches

### 2. For-Loop Fan-Out

The `for` task can process array items in parallel:

```yaml
- processOrders:
    for:
      iterator: "${ .orders }"
      as: "order"
      parallel: true  # Enable parallel processing
      maxConcurrency: 5  # Process up to 5 items at once
      do:
        - processOrder:
            callHTTP:
              url: "https://order-service/process/${ .order.id }"
              method: "POST"
              body: "${ .order }"
```

Benefits of this approach:
- Naturally maps to array processing
- Controls parallelism level with `maxConcurrency`
- Automatically collects results in original order

### 3. Event-Based Fan-Out

Using events to trigger multiple parallel workflows:

```yaml
- distributeWork:
    emit:
      event: "ProcessItem"
      foreach: "${ .items }"
      data: "${ @ }"  # Current item in the iteration
```

This approach:
- Scales beyond single workflow capacity
- Doesn't wait for completion (fire and forget)
- Works well for very high parallelism

## Fan-In Patterns in Lemline

Lemline supports multiple fan-in patterns to collect and process results:

### 1. Implicit Fork Fan-In

The `fork` task automatically implements fan-in:

```yaml
- processInParallel:
    fork:
      branches:
        - processPayment:
            # Payment processing tasks...
        
        - updateInventory:
            # Inventory update tasks...
      
      output: "*"  # Merge outputs from all branches
```

The `output` property controls result handling:
- `"*"`: Merge all branch outputs (default)
- `"first"`: Use the first completed branch's output
- `"last"`: Use the last completed branch's output

### 2. For-Loop Fan-In

The `for` task implements fan-in through result collection:

```yaml
- processItems:
    for:
      iterator: "${ .items }"
      as: "item"
      parallel: true
      maxConcurrency: 10
      do:
        - processItem:
            # Process individual item
      output:
        collect: true  # Collect all iteration results
```

This approach:
- Preserves the order of results to match the input array
- Controls whether to collect results or discard them
- Provides a single aggregated result

### 3. Event-Based Fan-In

For distributed processing, event-based fan-in uses the `listen` task:

```yaml
- waitForResults:
    listen:
      to: "all"
      events:
        - event: "ItemProcessed"
          filter: "${ .batchId == .currentBatch.id }"
      consume:
        amount: "${ .items | length }"  # Wait for all items
        timeout: PT1H
```

This approach:
- Scales to high levels of parallelism
- Works across distributed systems
- Can handle varying completion times

### 4. Aggregation Fan-In

Aggregating results through data transformation:

```yaml
- aggregateResults:
    set:
      totalAmount: "${ .processedOrders | map(.amount) | sum }"
      successCount: "${ .processedOrders | map(select(.status == "success")) | length }"
      failCount: "${ .processedOrders | map(select(.status == "failed")) | length }"
      summary:
        batchId: "${ .batchId }"
        timestamp: "${ now() }"
        totalAmount: "${ .totalAmount }"
        successRate: "${ .successCount / (.successCount + .failCount) * 100 }"
```

This pattern:
- Applies calculations across collected results
- Creates summary or aggregate data
- Transforms raw results into business metrics

## Advanced Fan-Out/Fan-In Patterns

### Dynamic Parallelism

Adjusting parallelism based on workload characteristics:

```yaml
- processItems:
    set:
      # Calculate optimal parallelism based on item count
      parallelism: "${ min(20, max(1, .items | length / 10 | floor)) }"
    
    for:
      iterator: "${ .items }"
      as: "item"
      parallel: true
      maxConcurrency: "${ .parallelism }"
      do:
        - processItem:
            # Process individual item
```

### Chunked Processing

Processing large datasets in batches:

```yaml
- processBatches:
    set:
      # Create chunks of 10 items each
      batches: "${ .items | _chunk(10) }"
    
    for:
      iterator: "${ .batches }"
      as: "batch"
      parallel: true
      maxConcurrency: 5
      do:
        - processBatch:
            callHTTP:
              url: "https://batch-processor/process"
              method: "POST"
              body: "${ .batch }"
```

### Prioritized Fan-In

Collecting results with priority handling:

```yaml
- collectPrioritizedResults:
    listen:
      to: "any"
      events:
        - event: "HighPriorityResult"
          as: "highPriorityResult"
          do:
            - handleHighPriority:
                # Process high priority result immediately
        
        - event: "NormalResult"
          as: "normalResult"
          do:
            - queueNormalResult:
                # Queue normal result for later processing
      consume:
        amount: "${ .expectedResults }"
```

### Scatter-Gather with Timeouts

Fan-out with timeout-aware aggregation:

```yaml
- scatterGather:
    fork:
      branches:
        - serviceA:
            callHTTP:
              url: "https://service-a.example.com/api"
              timeout: PT5S
        
        - serviceB:
            callHTTP:
              url: "https://service-b.example.com/api"
              timeout: PT5S
        
        - serviceC:
            callHTTP:
              url: "https://service-c.example.com/api"
              timeout: PT5S
      
      output: "*"
      partial: true  # Allow partial results if some branches fail
      minSuccess: 2  # Require at least 2 successful branches
```

## Performance Considerations

### Optimizing Fan-Out

1. **Right-Size Parallelism**: Match parallelism to available resources
2. **Consider Network Effects**: Too many parallel connections may cause throttling
3. **Batch Small Work**: Combine small tasks to reduce overhead
4. **Load Balancing**: Ensure even distribution of work across branches

### Optimizing Fan-In

1. **Streaming Aggregation**: Process results as they arrive rather than waiting for all
2. **Minimize Data Transfer**: Only collect necessary data from parallel tasks
3. **Early Termination**: Implement short-circuit patterns when possible
4. **Timeout Management**: Handle slow branches appropriately

## Error Handling in Fan-Out/Fan-In

### Handling Branch Failures

```yaml
- processInParallel:
    try:
      do:
        - parallelProcessing:
            fork:
              branches:
                - branch1:
                    # First branch tasks...
                
                - branch2:
                    # Second branch tasks...
              
              continue: "on-any"  # Continue even if some branches fail
      
      catch:
        - error:
            do:
              - handlePartialFailure:
                  # Handle partial completion
```

### Retry Strategies

Different retry strategies for fan-out operations:

1. **Individual Branch Retry**: Retry only failed branches
2. **Full Fan-Out Retry**: Retry the entire fan-out operation
3. **Incremental Retry**: Retry with increasing parallelism

### Compensating Transactions

Handling partial failures with compensation:

```yaml
- distributeUpdates:
    fork:
      branches:
        - updateSystem1:
            try:
              do:
                - callSystem1:
                    # Update System 1
              
              catch:
                - error:
                    do:
                      - recordFailure:
                          set:
                            failures: "${ append(.failures, 'system1') }"
        
        # Similar branches for other systems
      
      output: "*"
    
    # After fork completes, check for partial failures
    if: "${ .failures | length > 0 }"
    do:
      - compensatePartialUpdates:
          # Roll back successful updates due to partial failure
```

## Real-World Examples

### Distributed Data Processing

Processing a large dataset in parallel:

```yaml
- processLargeDataset:
    set:
      chunks: "${ .dataset | _chunk(1000) }"
    
    for:
      iterator: "${ .chunks }"
      as: "chunk"
      parallel: true
      maxConcurrency: 20
      do:
        - processChunk:
            callHTTP:
              url: "https://data-processor/process"
              method: "POST"
              body: "${ .chunk }"
      
      output:
        collect: true
        as: "processedChunks"
    
    set:
      results: "${ .processedChunks | flatten }"
      summary:
        totalProcessed: "${ .results | length }"
        successCount: "${ .results | map(select(.success)) | length }"
        errorCount: "${ .results | map(select(.error)) | length }"
```

### Multi-Service Aggregation

Fetching data from multiple services and aggregating the results:

```yaml
- fetchCustomerProfile:
    fork:
      branches:
        - basicInfo:
            callHTTP:
              url: "https://customer-service/customers/${ .customerId }"
              method: "GET"
        
        - orderHistory:
            callHTTP:
              url: "https://order-service/customers/${ .customerId }/orders"
              method: "GET"
        
        - paymentMethods:
            callHTTP:
              url: "https://payment-service/customers/${ .customerId }/payment-methods"
              method: "GET"
        
        - recommendations:
            callHTTP:
              url: "https://recommendation-service/for-customer/${ .customerId }"
              method: "GET"
      
      output: "*"
    
    set:
      customerProfile:
        id: "${ .customerId }"
        name: "${ .basicInfo.name }"
        email: "${ .basicInfo.email }"
        orders: "${ .orderHistory.orders }"
        paymentMethods: "${ .paymentMethods.methods }"
        recommendations: "${ .recommendations.products }"
```

### Event Processing Pipeline

Processing high-volume events with fan-out and fan-in:

```yaml
- processEvents:
    listen:
      to: "any"
      events:
        - event: "NewEvent"
      consume:
        amount: 100
        collect: true
    
    for:
      iterator: "${ .consumed }"
      as: "event"
      parallel: true
      maxConcurrency: 20
      do:
        - processEvent:
            switch:
              - condition: "${ .event.type == 'order' }"
                do:
                  - processOrderEvent:
                      # Process order event
              
              - condition: "${ .event.type == 'customer' }"
                do:
                  - processCustomerEvent:
                      # Process customer event
              
              - otherwise:
                  do:
                    - processGenericEvent:
                        # Process other events
      
      output:
        collect: true
        as: "processedEvents"
    
    emit:
      event: "EventsProcessed"
      data:
        batchId: "${ .batchId }"
        count: "${ .processedEvents | length }"
        timestamp: "${ now() }"
```

## Related Resources

- [Flow Control in Lemline](dsl-flow-control.md)
- [Fork Task Reference](dsl-task-fork.md)
- [For Task Reference](dsl-task-for.md)
- [Listen Task Reference](dsl-task-listen.md)
- [Event-Driven Architecture](lemline-explain-event-driven.md)