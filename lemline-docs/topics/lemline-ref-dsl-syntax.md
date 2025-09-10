# DSL Syntax Reference

This page provides a comprehensive reference for the Serverless Workflow DSL syntax as implemented in Lemline.

## Format and Structure

Lemline implements the Serverless Workflow specification, which supports both YAML and JSON formats. YAML is recommended for human readability. The basic structure of a workflow definition includes:

```yaml
# Basic workflow structure
version: "1.0.0"
namespace: "com.example"
name: "orderProcessing"
description: "Process customer orders"

# Metadata (optional)
metadata:
  version: "1.0.0"
  tags:
    - "order"
    - "processing"

# Shared resources (optional)
use:
  authentications: [...]
  errors: [...]
  retries: [...]
  timeouts: [...]
  catalogs: [...]
  functions: [...]

# Data specification (optional)
input:
  schema:
    type: "object"
    properties:
      orderId:
        type: "string"
      items:
        type: "array"
      customer:
        type: "object"
    required: ["orderId", "items"]

output:
  schema:
    type: "object"
    properties:
      orderStatus:
        type: "string"
      trackingId:
        type: "string"

# Workflow tasks (required)
do:
  - validateOrder:
      # Task definition...

  - processPayment:
      # Task definition...

  - shipOrder:
      # Task definition...
```

## Core Components

### Workflow Identification

```yaml
version: "1.0.0"     # Workflow specification version (required)
namespace: "com.example"   # Logical namespace (optional)
name: "orderProcessing"    # Workflow name (required)
description: "Process customer orders"  # Human-readable description (optional)
```

### Metadata

```yaml
metadata:
  version: "1.0.0"       # Workflow implementation version
  annotations:
    monitoring.level: "detailed"  # Custom annotations
  tags:                 # Categorization tags
    - "order"
    - "processing"
```

### Input/Output Schema

```yaml
input:
  schema:
    # JSON Schema definition for input data
    type: "object"
    properties:
      orderId:
        type: "string"
    required: ["orderId"]
  from: "${ .data }"  # Optional path to extract input from

output:
  schema:
    # JSON Schema definition for output data
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
    - name: "apiAuth"
      bearer:
        token: "your-token"
        
    - name: "serviceAuth"
      oauth2:
        grantType: "client_credentials"
        tokenUrl: "https://auth.example.com/token"
        clientId: "client-id"
        clientSecret: "client-secret"
```

### Error Definitions

```yaml
use:
  errors:
    - name: "outOfStock"
      type: "https://example.com/errors/out-of-stock"
      status: 409
      title: "Item Out of Stock"
```

### Retry Policies

```yaml
use:
  retries:
    - name: "standardRetry"
      policy:
        strategy: backoff
        backoff:
          delay: PT1S
          multiplier: 2
          jitter: 0.1
        limit:
          attempt:
            count: 3
```

### Timeout Definitions

```yaml
use:
  timeouts:
    - name: "shortTimeout"
      duration: PT10S
    
    - name: "longTimeout"
      duration: PT2M
```

### Function Catalogs

```yaml
use:
  catalogs:
    - name: "payments"
      functions:
        - name: "processPayment"
          operation: "https://functions.example.com/payments#Process"
    
    - name: "shipping"
      functions:
        - name: "calculateShipping"
          operation: "https://functions.example.com/shipping#Calculate"
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

### Flow Control Tasks

#### Do Task

```yaml
- processingStep:
    do:
      - task1:
          # Subtask definition...
      
      - task2:
          # Subtask definition...
```

#### Switch Task

```yaml
- routeRequest:
    switch:
      - condition: "${ .type == 'standard' }"
        do:
          - standardProcessing:
              # Task definition...
      
      - condition: "${ .type == 'express' }"
        do:
          - expressProcessing:
              # Task definition...
      
      - otherwise:
          do:
            - defaultProcessing:
                # Task definition...
```

#### For Task

```yaml
- processItems:
    for:
      iterator: "${ .items }"
      as: "item"
      do:
        - processItem:
            # Task definition using .item
      output:
        collect: true  # Collect all iteration results
```

#### Fork Task

```yaml
- parallelProcessing:
    fork:
      branches:
        - processPayment:
            # Payment processing tasks...
        
        - prepareShipment:
            # Shipment preparation tasks...
      
      output: "*"  # Merge outputs from all branches
```

#### Try-Catch Task

```yaml
- secureOperation:
    try:
      retry: "standardRetry"  # Reference to a retry policy
      do:
        - riskyTask:
            # Task that might fail...
      
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/timeout"
            as: "timeoutError"
          do:
            - handleTimeout:
                # Error handling tasks...
```

### Activity Tasks

#### Call HTTP

```yaml
- fetchData:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      query:
        param1: "value1"
        param2: "${ .dynamicValue }"
      headers:
        Content-Type: "application/json"
        Authorization: "Bearer ${ .token }"
      auth: "apiAuth"  # Reference to an authentication
      body: "${ .requestBody }"
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
      event: "OrderProcessed"
      data: "${ .order }"
      context:
        source: "order-service"
        type: "com.example.order.processed"
        subject: "${ .order.id }"
```

#### Wait Task

```yaml
- delayProcessing:
    wait:
      duration: PT1H
```

#### Listen Task

```yaml
- waitForApproval:
    listen:
      to: "any"
      events:
        - event: "OrderApproved"
          filter: "${ .orderId == .currentOrder.id }"
        - event: "OrderRejected"
          filter: "${ .orderId == .currentOrder.id }"
      timeout: PT24H
```

#### Raise Task

```yaml
- reportError:
    if: "${ .inventory.quantity < .order.quantity }"
    raise:
      error: "outOfStock"
      with:
        details: "Requested ${ .order.quantity }, available ${ .inventory.quantity }"
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
version: "1.0.0"
namespace: "com.example"
name: "orderProcessing"
description: "Process customer orders with payment and shipping"

metadata:
  version: "1.0.0"
  tags:
    - "order"
    - "e-commerce"

use:
  authentications:
    - name: "paymentAuth"
      oauth2:
        grantType: "client_credentials"
        tokenUrl: "https://auth.payment.com/token"
        clientId: "client-id"
        clientSecret:
          secret: "payment_secret"
  
  errors:
    - name: "paymentFailed"
      type: "https://example.com/errors/payment-failed"
      status: 400
    
    - name: "outOfStock"
      type: "https://example.com/errors/out-of-stock"
      status: 409
  
  retries:
    - name: "paymentRetry"
      policy:
        strategy: backoff
        backoff:
          delay: PT1S
          multiplier: 2
        limit:
          attempt:
            count: 3

input:
  schema:
    type: "object"
    properties:
      orderId:
        type: "string"
      items:
        type: "array"
      customer:
        type: "object"
    required: ["orderId", "items", "customer"]

output:
  schema:
    type: "object"
    properties:
      orderStatus:
        type: "string"
      trackingId:
        type: "string"

do:
  - validateOrder:
      do:
        - checkInventory:
            callHTTP:
              url: "https://inventory.example.com/check"
              method: "POST"
              body: "${ .items }"
            if: "${ .items | length > 0 }"
        
        - verifyCustomer:
            callHTTP:
              url: "https://customers.example.com/verify/${ .customer.id }"
              method: "GET"
  
  - processPayment:
      try:
        retry: "paymentRetry"
        do:
          - chargeCustomer:
              callHTTP:
                url: "https://payments.example.com/charge"
                method: "POST"
                auth: "paymentAuth"
                body: 
                  customerId: "${ .customer.id }"
                  amount: "${ .totalAmount }"
                  currency: "USD"
        catch:
          - error:
              with:
                type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
              as: "comError"
            do:
              - logError:
                  set:
                    paymentError: "${ .comError }"
                    orderStatus: "PAYMENT_FAILED"
  
  - prepareShipment:
      if: "${ .orderStatus != 'PAYMENT_FAILED' }"
      fork:
        branches:
          - allocateInventory:
              callHTTP:
                url: "https://inventory.example.com/allocate"
                method: "POST"
                body: "${ .items }"
          
          - generateLabels:
              callHTTP:
                url: "https://shipping.example.com/labels"
                method: "POST"
                body:
                  orderId: "${ .orderId }"
                  address: "${ .customer.address }"
        
        output: "*"
  
  - finalizeOrder:
      switch:
        - condition: "${ .orderStatus == 'PAYMENT_FAILED' }"
          do:
            - notifyFailure:
                emit:
                  event: "OrderFailed"
                  data: "${ . }"
        
        - otherwise:
            do:
              - completeOrder:
                  callHTTP:
                    url: "https://orders.example.com/complete/${ .orderId }"
                    method: "PUT"
                    body:
                      status: "COMPLETED"
                      trackingId: "${ .shipment.trackingId }"
              
              - notifySuccess:
                  emit:
                    event: "OrderCompleted"
                    data: "${ . }"
```

## Related Resources

- [Task Types Reference](lemline-ref-task-types.md)
- [JQ Expressions Guide](lemline-howto-jq.md)
- [Workflow Examples](dsl-workflow-examples.md)