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
- Can have a timeout for the entire parallel execution

## Basic Fork Structure

Here's the basic structure of a `fork` task:

```yaml
- name: ParallelProcessing
  type: fork
  branches:
    - name: Branch1
      tasks:
        - name: Task1
          type: set
          data:
            result1: "Branch 1 result"
    - name: Branch2
      tasks:
        - name: Task2
          type: set
          data:
            result2: "Branch 2 result"
  next: CombineResults
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
id: parallel-api-calls
name: Parallel API Calls Example
version: '1.0'
specVersion: '1.0'
start: PrepareData
functions:
  - name: getWeather
    type: http
    operation: GET
    url: https://api.weather.com/current?location={location}
  - name: getNews
    type: http
    operation: GET
    url: https://api.news.com/headlines?category={category}
  - name: getStocks
    type: http
    operation: GET
    url: https://api.stocks.com/quotes?symbols={symbols}
tasks:
  - name: PrepareData
    type: set
    data:
      location: "New York"
      category: "technology"
      symbols: "AAPL,GOOG,MSFT"
    next: FetchData
  
  - name: FetchData
    type: fork
    branches:
      - name: WeatherBranch
        tasks:
          - name: GetWeather
            type: call
            function: getWeather
            data:
              location: ".location"
      - name: NewsBranch
        tasks:
          - name: GetNews
            type: call
            function: getNews
            data:
              category: ".category"
      - name: StocksBranch
        tasks:
          - name: GetStocks
            type: call
            function: getStocks
            data:
              symbols: ".symbols"
    next: CombineResults
  
  - name: CombineResults
    type: set
    data:
      dashboard:
        weather: "$WORKFLOW.WeatherBranch.result"
        news: "$WORKFLOW.NewsBranch.result"
        stocks: "$WORKFLOW.StocksBranch.result"
      message: "Dashboard data collected successfully"
    end: true
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
- name: ParallelProcessing
  type: fork
  branches:
    - name: OrderBranch
      tasks:
        - name: FetchOrder
          type: call
          function: getOrder
          data:
            orderId: ".orderId"
        - name: ValidateOrder
          type: set
          data:
            isValid: ".items | length > 0"
        - name: CalculateTotal
          type: set
          data:
            total: ".items | map(.price * .quantity) | add"
    - name: CustomerBranch
      tasks:
        - name: FetchCustomer
          type: call
          function: getCustomer
          data:
            customerId: ".customerId"
        - name: CheckEligibility
          type: set
          data:
            isEligible: ".status == 'active' && .flags | length == 0"
  next: ProcessResults
```

Each branch executes its tasks in sequence, while the branches themselves run in parallel.

## Error Handling in Parallel Execution

### Using Try/Catch in Branches

You can use the `try` task within branches to handle errors locally:

```yaml
- name: ParallelProcessing
  type: fork
  branches:
    - name: CustomerBranch
      tasks:
        - name: GetCustomerData
          type: try
          retry:
            maxAttempts: 3
            interval: PT2S
          catch:
            - error: "*"
              next: CustomerFallback
          do:
            - name: FetchCustomer
              type: call
              function: customerService
              data:
                id: ".customerId"
        - name: CustomerFallback
          type: set
          data:
            customer:
              name: "Unknown"
              status: "unknown"
              error: "Failed to fetch customer data"
    # other branches...
  next: ContinueProcessing
```

This allows each branch to handle its own errors independently.

### Using Try/Catch Around the Fork

You can also wrap the entire `fork` task in a `try` task:

```yaml
- name: AttemptParallelProcessing
  type: try
  retry:
    maxAttempts: 2
  catch:
    - error: "*"
      next: HandleParallelError
  do:
    - name: ParallelProcessing
      type: fork
      branches:
        # branches defined here...
      next: ContinueProcessing

- name: HandleParallelError
  type: set
  data:
    error: "Parallel processing failed"
    details: "$WORKFLOW.error"
  next: Fallback
```

This handles cases where the entire parallel execution fails.

## Timeouts for Parallel Execution

You can set a timeout for the entire parallel execution:

```yaml
- name: ParallelProcessing
  type: fork
  timeout: PT30S
  timeoutNext: HandleTimeout
  branches:
    # branches defined here...
  next: ContinueProcessing

- name: HandleTimeout
  type: set
  data:
    error: "Parallel processing timed out after 30 seconds"
  next: Fallback
```

If any branch takes longer than the specified timeout, the entire fork is cancelled and execution continues to the `timeoutNext` task.

## Dynamic Branching

You can dynamically create parallel branches based on runtime data:

```yaml
- name: PrepareParallel
  type: set
  data:
    productIds: [123, 456, 789]
    branches: ".productIds | map({
      name: \"Product\" + (. | tostring),
      tasks: [{
        name: \"FetchProduct\",
        type: \"call\",
        function: \"getProduct\",
        data: {
          productId: .
        }
      }]
    })"
  next: ProcessProducts

- name: ProcessProducts
  type: fork
  branches: ".branches"
  next: CombineResults
```

This example dynamically creates a branch for each product ID in the list.

## Real-World Example: Order Processing

Here's a more complex example that processes an order using parallel execution:

```yaml
id: order-processor
name: Order Processing Workflow
version: '1.0'
specVersion: '1.0'
start: ReceiveOrder
functions:
  - name: validateCustomer
    type: http
    operation: GET
    url: https://api.example.com/customers/{customerId}/validate
  - name: checkInventory
    type: http
    operation: POST
    url: https://api.example.com/inventory/check
  - name: calculateTax
    type: http
    operation: POST
    url: https://api.example.com/tax/calculate
  - name: processPayment
    type: http
    operation: POST
    url: https://api.example.com/payments/process
tasks:
  - name: ReceiveOrder
    type: set
    data:
      orderId: "$WORKFLOW.input.orderId"
      customerId: "$WORKFLOW.input.customerId"
      items: "$WORKFLOW.input.items"
      shippingAddress: "$WORKFLOW.input.shippingAddress"
      paymentMethod: "$WORKFLOW.input.paymentMethod"
    next: ParallelValidation
  
  - name: ParallelValidation
    type: fork
    timeout: PT30S
    timeoutNext: HandleTimeout
    branches:
      - name: CustomerBranch
        tasks:
          - name: ValidateCustomer
            type: try
            retry:
              maxAttempts: 3
              interval: PT2S
            catch:
              - error: "*"
                next: CustomerFallback
            do:
              - name: CheckCustomer
                type: call
                function: validateCustomer
                data:
                  customerId: ".customerId"
          - name: CustomerFallback
            type: set
            data:
              customerValid: false
              customerError: "Failed to validate customer"
      
      - name: InventoryBranch
        tasks:
          - name: CheckInventory
            type: try
            retry:
              maxAttempts: 2
            catch:
              - error: "*"
                next: InventoryFallback
            do:
              - name: VerifyInventory
                type: call
                function: checkInventory
                data:
                  items: ".items"
          - name: InventoryFallback
            type: set
            data:
              inventoryValid: false
              inventoryError: "Failed to check inventory"
      
      - name: TaxBranch
        tasks:
          - name: CalculateTax
            type: try
            retry:
              maxAttempts: 2
            catch:
              - error: "*"
                next: TaxFallback
            do:
              - name: ComputeTax
                type: call
                function: calculateTax
                data:
                  items: ".items"
                  shippingAddress: ".shippingAddress"
          - name: TaxFallback
            type: set
            data:
              taxValid: false
              taxError: "Failed to calculate tax"
    next: EvaluateResults
  
  - name: EvaluateResults
    type: set
    data:
      customerValid: "$WORKFLOW.CustomerBranch.customerValid || $WORKFLOW.CustomerBranch.status == 'ACTIVE'"
      inventoryValid: "$WORKFLOW.InventoryBranch.inventoryValid || $WORKFLOW.InventoryBranch.allAvailable == true"
      taxValid: "$WORKFLOW.TaxBranch.taxValid || $WORKFLOW.TaxBranch.taxAmount >= 0"
      allValid: ".customerValid && .inventoryValid && .taxValid"
      subtotal: ".items | map(.price * .quantity) | add"
      tax: "$WORKFLOW.TaxBranch.taxAmount || (.subtotal * 0.08)"
      total: ".subtotal + .tax"
    next: CheckValidations
  
  - name: CheckValidations
    type: switch
    conditions:
      - condition: ".allValid == true"
        next: ProcessPayment
      - condition: ".customerValid == false"
        next: HandleCustomerError
      - condition: ".inventoryValid == false"
        next: HandleInventoryError
      - condition: ".taxValid == false"
        next: HandleTaxError
      - condition: true
        next: HandleUnknownError
  
  - name: ProcessPayment
    type: call
    function: processPayment
    data:
      orderId: ".orderId"
      customerId: ".customerId"
      amount: ".total"
      method: ".paymentMethod"
    next: CompleteOrder
  
  - name: CompleteOrder
    type: set
    data:
      status: "COMPLETED"
      message: "Order processed successfully"
      finalAmount: ".total"
      paymentConfirmation: ".transactionId"
    end: true
  
  - name: HandleCustomerError
    type: set
    data:
      status: "FAILED"
      reason: "Customer validation failed"
      details: "$WORKFLOW.CustomerBranch.customerError || 'Invalid customer'"
    end: true
  
  - name: HandleInventoryError
    type: set
    data:
      status: "FAILED"
      reason: "Inventory check failed"
      details: "$WORKFLOW.InventoryBranch.inventoryError || 'Inventory not available'"
    end: true
  
  - name: HandleTaxError
    type: set
    data:
      status: "FAILED"
      reason: "Tax calculation failed"
      details: "$WORKFLOW.TaxBranch.taxError || 'Unable to calculate tax'"
    end: true
  
  - name: HandleUnknownError
    type: set
    data:
      status: "FAILED"
      reason: "Order processing failed"
      details: "Unknown error occurred during validation"
    end: true
  
  - name: HandleTimeout
    type: set
    data:
      status: "FAILED"
      reason: "Order processing timed out"
      details: "The operation took too long to complete"
    end: true
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
2. **Set appropriate timeouts**: Always include a timeout for the entire fork operation
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