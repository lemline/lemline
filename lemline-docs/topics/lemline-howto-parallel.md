---
title: How to execute tasks in parallel (fork)
---

# How to execute tasks in parallel (fork)

This guide explains how to execute tasks in parallel using the `fork` task type in Lemline. Parallel execution can significantly improve workflow performance for independent operations.

## Understanding the Fork Task

The `fork` task allows you to execute multiple branches of your workflow simultaneously. Each branch runs independently and in parallel, with results gathered after all branches complete.

Key characteristics of the `fork` task:
- Executes multiple branches concurrently
- Waits for all branches to complete before continuing
- Combines results from all branches
- Cannot be bounded by a timeout yet: Lemline only enforces timeouts on `listen` tasks

## Basic Fork Structure

Here's the basic structure of a `fork` task:

```yaml
  - ParallelProcessing:
      fork:
        branches:
          - Branch1:
              do:
                - Task1:
                    set:
                      result1: "Branch 1 result"
          - Branch2:
              do:
                - Task2:
                    set:
                      result2: "Branch 2 result"
      then: CombineResults
```

Each branch contains its own series of tasks that execute independently. After all branches complete, execution continues to the task specified in the `next` property.

## When to Use Parallel Execution

Use the `fork` task when:

1. You have multiple independent operations that don't rely on each other
2. You want to reduce overall workflow execution time
3. You need to make several external API calls simultaneously
4. You're processing multiple items that can be handled independently
5. You want to implement fan-out/fan-in patterns

## Simple Example: Parallel API Calls

Here's a simple example that makes three API calls in parallel:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: parallel-api-calls
  version: 1.0.0
  title: Parallel API Calls Example
do:
  - PrepareData:
      set:
        location: "New York"
        category: "technology"
        symbols: "AAPL,GOOG,MSFT"
      then: FetchData
  - FetchData:
      fork:
        branches:
          - WeatherBranch:
              do:
                - GetWeather:
                    call: http
                    with:
                      method: get
                      endpoint: https://api.weather.com/current?location={location}
                      query:
                        location: ${ .location }
          - NewsBranch:
              do:
                - GetNews:
                    call: http
                    with:
                      method: get
                      endpoint: https://api.news.com/headlines?category={category}
                      query:
                        category: ${ .category }
          - StocksBranch:
              do:
                - GetStocks:
                    call: http
                    with:
                      method: get
                      endpoint: https://api.stocks.com/quotes?symbols={symbols}
                      query:
                        symbols: ${ .symbols }
      then: CombineResults
  - CombineResults:
      set:
        dashboard:
          weather: ${ $context.WeatherBranch.result }
          news: ${ $context.NewsBranch.result }
          stocks: ${ $context.StocksBranch.result }
        message: "Dashboard data collected successfully"
      then: end
```

In this example, three API calls run simultaneously instead of sequentially, significantly reducing the overall execution time.

## Accessing Branch Results

Results from each branch are available in the workflow context using the branch name. In the example above, we access the results using:

```
$WORKFLOW.WeatherBranch.result
$WORKFLOW.NewsBranch.result
$WORKFLOW.StocksBranch.result
```

You can then combine these results in subsequent tasks.

## Complex Branches

Each branch can contain multiple tasks that execute in sequence:

```yaml
  - ParallelProcessing:
      fork:
        branches:
          - OrderBranch:
              do:
                - FetchOrder:
                    call: getOrder
                    with:
                      orderId: ${ .orderId }
                - ValidateOrder:
                    set:
                      isValid: ${ .items | length > 0 }
                - CalculateTotal:
                    set:
                      total: ${ .items | map(.price * .quantity) | add }
          - CustomerBranch:
              do:
                - FetchCustomer:
                    call: getCustomer
                    with:
                      customerId: ${ .customerId }
                - CheckEligibility:
                    set:
                      isEligible: ${ .status == "active" and .flags | length == 0 }
      then: ProcessResults
```

Each branch executes its tasks in sequence, while the branches themselves run in parallel.

## Error Handling in Parallel Execution

### Using Try/Catch in Branches

You can use the `try` task within branches to handle errors locally:

```yaml
  - ParallelProcessing:
      fork:
        branches:
          - CustomerBranch:
              do:
                - GetCustomerData:
                    try:
                      - FetchCustomer:
                          call: customerService
                          with:
                            id: ${ .customerId }
                    catch:
                      as: failure
                      then: CustomerFallback
                      retry:
                        limit:
                          attempt:
                            count: 3
                        delay: PT2S
                - CustomerFallback:
                    set:
                      customer:
                        name: "Unknown"
                        status: "unknown"
                        error: "Failed to fetch customer data"
      then: ContinueProcessing
```

This allows each branch to handle its own errors independently.

### Using Try/Catch Around the Fork

You can also wrap the entire `fork` task in a `try` task:

```yaml
  - AttemptParallelProcessing:
      try:
        - ParallelProcessing:
            fork:
              branches: []
            then: ContinueProcessing
      catch:
        as: failure
        then: HandleParallelError
        retry:
          limit:
            attempt:
              count: 2
  - HandleParallelError:
      set:
        error: "Parallel processing failed"
        details: ${ $context.error }
      then: Fallback
```

This handles cases where the entire parallel execution fails.

## Timeouts for Parallel Execution

You can set a timeout for the entire parallel execution:

```yaml
  - ParallelProcessing:
      fork:
        branches: []
      then: ContinueProcessing
      timeout:
        after: PT30S
  - HandleTimeout:
      set:
        error: "Parallel processing timed out after 30 seconds"
      then: Fallback
```

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

If any branch takes longer than the specified timeout, the entire fork is cancelled and execution continues to the `timeoutNext` task.

## Dynamic Branching

You can dynamically create parallel branches based on runtime data:

```yaml
- processProducts:
    for:
      each: productId
      in: '${ .productIds }'
    do:
      - fetchProduct:
          call: getProduct
          with:
            productId: '${ $productId }'
```

This example dynamically creates a branch for each product ID in the list.

## Real-World Example: Order Processing

Here's a more complex example that processes an order using parallel execution:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: order-processor
  version: 1.0.0
  title: Order Processing Workflow
do:
  - ReceiveOrder:
      set:
        orderId: ${ $workflow.input.orderId }
        customerId: ${ $workflow.input.customerId }
        items: ${ $workflow.input.items }
        shippingAddress: ${ $workflow.input.shippingAddress }
        paymentMethod: ${ $workflow.input.paymentMethod }
      then: ParallelValidation
  - ParallelValidation:
      fork:
        branches:
          - CustomerBranch:
              do:
                - ValidateCustomer:
                    try:
                      - CheckCustomer:
                          call: http
                          with:
                            method: get
                            endpoint: https://api.example.com/customers/{customerId}/validate
                            query:
                              customerId: ${ .customerId }
                    catch:
                      as: failure
                      then: CustomerFallback
                      retry:
                        limit:
                          attempt:
                            count: 3
                        delay: PT2S
                - CustomerFallback:
                    set:
                      customerValid: false
                      customerError: "Failed to validate customer"
          - InventoryBranch:
              do:
                - CheckInventory:
                    try:
                      - VerifyInventory:
                          call: http
                          with:
                            method: post
                            endpoint: https://api.example.com/inventory/check
                            body:
                              items: ${ .items }
                    catch:
                      as: failure
                      then: InventoryFallback
                      retry:
                        limit:
                          attempt:
                            count: 2
                - InventoryFallback:
                    set:
                      inventoryValid: false
                      inventoryError: "Failed to check inventory"
          - TaxBranch:
              do:
                - CalculateTax:
                    try:
                      - ComputeTax:
                          call: http
                          with:
                            method: post
                            endpoint: https://api.example.com/tax/calculate
                            body:
                              items: ${ .items }
                              shippingAddress: ${ .shippingAddress }
                    catch:
                      as: failure
                      then: TaxFallback
                      retry:
                        limit:
                          attempt:
                            count: 2
                - TaxFallback:
                    set:
                      taxValid: false
                      taxError: "Failed to calculate tax"
      then: EvaluateResults
      timeout:
        after: PT30S
  - EvaluateResults:
      set:
        customerValid: ${ $context.CustomerBranch.customerValid // $context.CustomerBranch.status == "ACTIVE" }
        inventoryValid: ${ $context.InventoryBranch.inventoryValid // $context.InventoryBranch.allAvailable == true }
        taxValid: ${ $context.TaxBranch.taxValid // $context.TaxBranch.taxAmount >= 0 }
        allValid: ${ .customerValid and .inventoryValid and .taxValid }
        subtotal: ${ .items | map(.price * .quantity) | add }
        tax: ${ $context.TaxBranch.taxAmount // (.subtotal * 0.08) }
        total: ${ .subtotal + .tax }
      then: CheckValidations
  - CheckValidations:
      switch:
        - case1:
            then: ProcessPayment
            when: ${ .allValid == true }
        - case2:
            then: HandleCustomerError
            when: ${ .customerValid == false }
        - case3:
            then: HandleInventoryError
            when: ${ .inventoryValid == false }
        - case4:
            then: HandleTaxError
            when: ${ .taxValid == false }
        - default:
            then: HandleUnknownError
  - ProcessPayment:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/payments/process
        body:
          orderId: ${ .orderId }
          customerId: ${ .customerId }
          amount: ${ .total }
          method: ${ .paymentMethod }
      then: CompleteOrder
  - CompleteOrder:
      set:
        status: "COMPLETED"
        message: "Order processed successfully"
        finalAmount: ${ .total }
        paymentConfirmation: ${ .transactionId }
      then: end
  - HandleCustomerError:
      set:
        status: "FAILED"
        reason: "Customer validation failed"
        details: ${ $context.CustomerBranch.customerError // "Invalid customer" }
      then: end
  - HandleInventoryError:
      set:
        status: "FAILED"
        reason: "Inventory check failed"
        details: ${ $context.InventoryBranch.inventoryError // "Inventory not available" }
      then: end
  - HandleTaxError:
      set:
        status: "FAILED"
        reason: "Tax calculation failed"
        details: ${ $context.TaxBranch.taxError // "Unable to calculate tax" }
      then: end
  - HandleUnknownError:
      set:
        status: "FAILED"
        reason: "Order processing failed"
        details: "Unknown error occurred during validation"
      then: end
  - HandleTimeout:
      set:
        status: "FAILED"
        reason: "Order processing timed out"
        details: "The operation took too long to complete"
      then: end
```

This workflow validates a customer, checks inventory, and calculates tax in parallel, then processes the payment if all validations pass.

## Performance Considerations

### Benefits of Parallel Execution

1. **Reduced execution time**: Operations run concurrently instead of sequentially
2. **Improved throughput**: More work gets done in the same amount of time
3. **Better resource utilization**: CPU and network resources are used more efficiently

### Potential Pitfalls

1. **Resource contention**: Too many parallel branches may overwhelm system resources
2. **External rate limits**: APIs may have rate limits that get hit with parallel calls
3. **Increased complexity**: Parallel execution adds complexity to workflow design
4. **Error handling challenges**: Errors in one branch may affect the entire workflow

## Best Practices

1. **Keep branches independent**: Ensure branches don't depend on each other's results
2. **Set appropriate timeouts**: Always include a timeout for the entire fork operation (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)
3. **Include proper error handling**: Handle errors both within branches and for the entire fork
4. **Consider resource constraints**: Don't create too many parallel branches
5. **Test thoroughly**: Test with real-world data volumes and timing
6. **Use fallbacks**: Provide fallback logic for branches that might fail
7. **Monitor performance**: Track execution times to ensure performance goals are met
8. **Consider idempotency**: Ensure operations can be safely retried if needed

## Next Steps

- Learn about [running loops in workflows](lemline-howto-loops.md)
- Explore [how to handle errors in workflows](lemline-howto-try-catch.md)
- Understand [how to pass data between tasks](lemline-howto-data-passing.md)