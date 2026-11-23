# DSL Syntax Reference

> **Runtime support:** this page describes the DSL syntax. The Lemline runtime does not execute `call: openapi`, `call: asyncapi`, `call: grpc` or OAuth 2.0 / OpenID Connect authentication yet. See [Supported Features](lemline-ref-supported-features.md).


This page provides a comprehensive reference for the Open Workflow DSL syntax as implemented in Lemline.

## Format and Structure

A workflow has a `document` header and a `do` list. This complete definition accepts an order and returns its status.

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: order-processing
  version: '1.0.0'
  summary: Process customer orders
  metadata:
    tags: [order, processing]
do:
  - acceptOrder:
      set:
        status: accepted
```

## Core Components

### Workflow Identification

`document.dsl` identifies the DSL version. The required fields `document.namespace`, `document.name` and `document.version` identify a particular version of your workflow. Use `document.metadata` for optional application-specific annotations.

### Input/Output Schema

```yaml
input:
  schema:
    document:
      type: "object"
      properties:
        orderId:
          type: "string"
      required: ["orderId"]
  from: "${ .data }"  # Optional path to extract input from

output:
  schema:
    document:
      type: "object"
      properties:
        result:
          type: "string"
  as: "orderResult"  # Optional variable name for the output
```

## Resource Definitions

### Authentication

```yaml
use:
  authentications:
    "apiAuth":
      bearer:
        token: "your-token"
    "serviceAuth":
      oauth2:
        authority: "https://auth.example.com/token"
        grant: "client_credentials"
        client:
          id: "client-id"
          secret: "client-secret"
```

### Error Definitions

```yaml
use:
  errors:
    "outOfStock":
      type: "https://example.com/errors/out-of-stock"
      status: 409
      title: "Item Out of Stock"
```

### Retry Policies

```yaml
use:
  retries:
    "standardRetry":
      limit:
        attempt:
          count: 3
      delay: PT1S
      backoff:
        exponential: {}
      jitter:
        from: PT0S
        to: PT0.1S
```

### Timeout Definitions

```yaml
use:
  timeouts:
    "shortTimeout":
      after: PT10S
    "longTimeout":
      after: PT2M
```

### Function Catalogs

```yaml
use:
  catalogs:
    "payments":
      endpoint: https://functions.example.com/payments
    "shipping":
      endpoint: https://functions.example.com/shipping
```

## Task Syntax

### Basic Task Structure

```yaml
- taskName:  # Unique name for the task
    taskType:  # The type of task (callHTTP, set, etc.)
      # Task-specific configuration...
    
    # Optional task properties
    if: "${ .condition == true }"  # Conditional execution
    timeout: PT30S  # Task-specific timeout
    metadata:  # Task metadata
      description: "Description of this task"
      labels:
        category: "api"
```

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

### Flow Control Tasks

#### Do Task

```yaml
  - processingStep:
      do:
        - task1:
            set: ${ . }
        - task2:
            set: ${ . }
```

#### Switch Task

```yaml
  - routeRequest:
      do:
        - choose:
            switch:
              - branch1:
                  when: ${ .type == "standard" }
                  then: branch1
              - branch2:
                  when: ${ .type == "express" }
                  then: branch2
              - branch3:
                  then: branch3
        - branch1:
            do:
              - standardProcessing:
                  set: ${ . }
            then: exit
        - branch2:
            do:
              - expressProcessing:
                  set: ${ . }
            then: exit
        - branch3:
            do:
              - defaultProcessing:
                  set: ${ . }
            then: exit
```

#### For Task

```yaml
  - processItems:
      for:
        in: "${ .items }"
        each: "item"
      do:
        - processItem:
            set: ${ $item }
```

#### Fork Task

```yaml
  - parallelProcessing:
      fork:
        branches:
          - processPayment:
              set: ${ . }
          - prepareShipment:
              set: ${ . }
```

#### Try-Catch Task

```yaml
  - secureOperation:
      try:
        - riskyTask:
            set: ${ . }
      catch:
        as: "timeoutError"
        do:
          - handleTimeout:
              set: ${ . }
        errors:
          with:
            type: "https://open-workflow-specification.org/spec/1.0.0/errors/timeout"
        retry: "standardRetry"
```

### Activity Tasks

#### Call HTTP

```yaml
  - fetchData:
      call: http
      with:
        method: "GET"
        query:
          param1: "value1"
          param2: "${ .dynamicValue }"
        headers:
          Content-Type: "application/json"
          Authorization: "Bearer ${ .token }"
        body: "${ .requestBody }"
        endpoint:
          uri: "https://api.example.com/data"
          authentication:
            use: "apiAuth"
```

#### Call OpenAPI

```yaml
- getProduct:
    callOpenAPI:
      operation: "getProduct"
      api: "https://api.example.com/swagger.json"
      parameters:
        productId: "${ .id }"
      auth: "apiAuth"
```

#### Call gRPC

```yaml
- getUserProfile:
    callGRPC:
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "${ .userId }"
```

#### Call Function

```yaml
- processPayment:
    callFunction:
      function: "payments.processPayment"
      arguments:
        amount: "${ .order.total }"
        currency: "USD"
        customerId: "${ .customer.id }"
```

#### Set Task

```yaml
- prepareData:
    set:
      orderStatus: "PROCESSING"
      timestamp: "${ now() }"
      customer:
        id: "${ .customerId }"
        type: "${ .premium ? 'premium' : 'standard' }"
```

#### Emit Task

```yaml
  - notifyShipping:
      emit:
        event:
          with:
            type: "com.example.order.processed"
            source: "order-service"
            subject: "${ .order.id }"
            data: "${ .order }"
```

#### Wait Task

```yaml
  - delayProcessing:
      wait: PT1H
```

#### Listen Task

```yaml
  - waitForApproval:
      listen:
        to:
          "any":
            - with:
                type: "OrderApproved"
              correlate:
                orderId:
                  from: ${ .data.orderId }
                  expect: ${ .currentOrder.id }
            - with:
                type: "OrderRejected"
              correlate:
                orderId:
                  from: ${ .data.orderId }
                  expect: ${ .currentOrder.id }
      timeout:
        after: PT24H
```

#### Raise Task

```yaml
  - reportError:
      if: "${ .inventory.quantity < .order.quantity }"
      raise:
        error:
          type: https://example.com/errors/out-of-stock
          status: 409
          detail: ${ "Requested \(.order.quantity), available \(.inventory.quantity)" }
```

## Expression Syntax

Lemline uses JQ expressions for data manipulation and conditions:

```yaml
# String literal
"${ 'Hello ' + .name }"

# Numeric operations
"${ .price * .quantity * (1 - .discount) }"

# Boolean conditions
"${ .age >= 18 && .verified == true }"

# Array operations
"${ .items | length }"
"${ .items[] | select(.price > 100) }"

# Object transformations
"${ .user | { id: .id, name: .name } }"

# Functions
"${ now() }"
"${ uuid() }"
"${ contains(.email, '@') }"
```

## Reserved Variables

Lemline provides these reserved variables:

- `$input` - The input data to the workflow
- `$data` - Current data context (starting with input data)
- `$error` - Information about the current error (in catch blocks)
- `$parent` - Access to the parent scope's data
- `$self` - Access to task metadata

## Extension Mechanism

Custom extensions are defined using the `extension` property:

```yaml
- customTask:
    extension:
      monitoring:
        metricName: "task.execution.time"
        labels:
          component: "payment"
```

## Full Example

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: order-processing
  version: '1.0.0'
  summary: Verify an order, charge the customer and prepare the shipment
use:
  secrets: [paymentToken]
  authentications:
    paymentAuth:
      bearer:
        token: ${ $secrets.paymentToken }
input:
  schema:
    document:
      type: object
      required: [orderId, items, customer, totalAmount]
      properties:
        orderId: { type: string }
        items: { type: array, minItems: 1 }
        totalAmount: { type: number }
        customer:
          type: object
          required: [id, address]
do:
  - checkInventory:
      call: http
      with:
        method: post
        endpoint: https://inventory.example.com/check
        body: ${ .items }
      output:
        as: ${ $input }
  - verifyCustomer:
      call: http
      with:
        method: get
        endpoint: '${ "https://customers.example.com/verify/\(.customer.id | @uri)" }'
      output:
        as: ${ $input }
  - processPayment:
      try:
        - chargeCustomer:
            try:
              - charge:
                  call: http
                  with:
                    method: post
                    endpoint:
                      uri: https://payments.example.com/charge
                      authentication: { use: paymentAuth }
                    headers:
                      Idempotency-Key: ${ .orderId }
                    body:
                      customerId: ${ .customer.id }
                      amount: ${ .totalAmount }
                      currency: USD
            catch:
              errors:
                with:
                  status: 503
              retry:
                delay: PT1S
                backoff: { exponential: {} }
                limit: { attempt: { count: 3 } }
      catch:
        as: paymentError
        do:
          - notifyFailure:
              emit:
                event:
                  with:
                    source: https://orders.example.com
                    type: OrderFailed
                    data:
                      orderId: ${ $workflow.input.orderId }
                      reason: ${ $paymentError.title }
              then: end
  - prepareShipment:
      input:
        from: ${ $workflow.input }
      fork:
        branches:
          - allocateInventory:
              call: http
              with:
                method: post
                endpoint: https://inventory.example.com/allocate
                body: ${ .items }
          - generateLabels:
              call: http
              with:
                method: post
                endpoint: https://shipping.example.com/labels
                body:
                  orderId: ${ .orderId }
                  address: ${ .customer.address }
  - completeOrder:
      call: http
      with:
        method: put
        endpoint: '${ "https://orders.example.com/complete/\($workflow.input.orderId | @uri)" }'
        body:
          status: COMPLETED
  - notifySuccess:
      emit:
        event:
          with:
            source: https://orders.example.com
            type: OrderCompleted
            data:
              orderId: ${ $workflow.input.orderId }
```

## Related Resources

- [Task Types Reference](lemline-ref-task-types.md)
- [JQ Expressions Guide](lemline-howto-jq.md)
- [Workflow Examples](dsl-workflow-examples.md)