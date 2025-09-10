# Task Types Reference

This reference documents all supported task types in Lemline, their syntax, properties, and usage.

## Overview of Task Types

Lemline supports the following categories of tasks:

1. **Flow Control**: Tasks that manage execution flow
2. **Activity**: Tasks that perform actions or call external systems
3. **Data Manipulation**: Tasks that transform or store data
4. **Event Processing**: Tasks that emit or listen for events
5. **Error Handling**: Tasks that manage exceptions

## Flow Control Tasks

### Do Task

Executes a sequence of subtasks in order.

```yaml
- sequentialSteps:
    do:
      - firstStep:
          # Task definition
      - secondStep:
          # Task definition
```

**Properties**:
- None specific to the task type

**Notes**:
- Subtasks execute in sequential order
- If any subtask fails, execution stops unless in a `try` block

### Switch Task

Conditionally executes a branch based on evaluated conditions.

```yaml
- routeRequest:
    switch:
      - condition: "${ .type == 'standard' }"
        do:
          - standardProcessing:
              # Task definition
      
      - condition: "${ .type == 'express' }"
        do:
          - expressProcessing:
              # Task definition
      
      - otherwise:
          do:
            - defaultProcessing:
                # Task definition
```

**Properties**:
- **switch**: Array of condition/task pairs
  - **condition**: JQ expression evaluated to boolean (omitted for `otherwise`)
  - **do**: Tasks to execute if condition is true

**Notes**:
- Conditions are evaluated in order
- First matching condition's branch executes
- `otherwise` branch executes if no conditions match

### For Task

Iterates over a collection and executes subtasks for each item.

```yaml
- processItems:
    for:
      iterator: "${ .items }"
      as: "item"
      do:
        - processItem:
            # Task using .item variable
      output:
        collect: true  # Optional, collect results from iterations
```

**Properties**:
- **iterator**: Expression returning an array to iterate over
- **as**: Variable name to store the current item (default: `item`)
- **output.collect**: Whether to collect results from all iterations (default: `false`)

**Notes**:
- The iterator variable is available in the scope of the `do` block
- When `output.collect` is `true`, results are collected in an array

### Fork Task

Executes multiple branches in parallel.

```yaml
- parallelProcessing:
    fork:
      branches:
        - processPayment:
            # Payment processing tasks
        
        - prepareShipment:
            # Shipment preparation tasks
      
      output: "*"  # Merge outputs from all branches
```

**Properties**:
- **branches**: Named branches to execute in parallel
- **output**: How to handle branch outputs:
  - `"*"`: Merge all branch outputs
  - `"first"`: Use the output of the first completed branch
  - `"last"`: Use the output of the last completed branch

**Notes**:
- Branches execute independently
- If any branch fails, the fork fails (unless in a `try` block)

### Try Task

Provides error handling and retry capabilities.

```yaml
- secureOperation:
    try:
      retry:
        policy:
          strategy: backoff
          backoff:
            delay: PT1S
            multiplier: 2
          limit:
            attempt:
              count: 3
      do:
        - riskyTask:
            # Task that might fail
      
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/timeout"
            as: "timeoutError"
          do:
            - handleTimeout:
                # Error handling tasks
```

**Properties**:
- **retry**: Retry configuration (optional)
  - **policy**: Retry policy configuration
    - **strategy**: `constant`, `linear`, or `backoff`
    - **constant/linear/backoff**: Strategy-specific configuration
    - **limit**: Retry limits (attempts, duration)
    - **when/exceptWhen**: Conditional retry expressions
- **do**: Tasks to execute and monitor for errors
- **catch**: Array of error handlers
  - **error.with**: Criteria for matching errors
  - **error.as**: Variable name for the caught error
  - **error.when/exceptWhen**: Conditional error handling
  - **do**: Tasks to execute if the error is caught

**Notes**:
- Retry attempts the entire `do` block again
- Catch blocks are evaluated in order
- First matching catch block handles the error

## Activity Tasks

### Call HTTP

Makes HTTP requests to external services.

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
      auth: "apiAuth"  # Reference to authentication
      body: "${ .requestBody }"
```

**Properties**:
- **url**: Target URL (supports expressions)
- **method**: HTTP method (`GET`, `POST`, `PUT`, `DELETE`, etc.)
- **query**: Query parameters (object)
- **headers**: HTTP headers (object)
- **auth**: Reference to authentication configuration
- **body**: Request body (supports expressions)
- **timeout**: Request timeout
- **followRedirects**: Whether to follow redirects (default: `true`)
- **output**:
  - **as**: Variable name for response (default: whole response object)
  - **from**: Path to extract from response

**Notes**:
- Response includes `status`, `headers`, and `body`
- Communication errors raise `communication` error type
- Status codes 4xx/5xx can be caught with the `catch` block

### Call OpenAPI

Makes calls to services defined with OpenAPI specifications.

```yaml
- getProduct:
    callOpenAPI:
      operation: "getProduct"
      api: "https://api.example.com/swagger.json"
      parameters:
        productId: "${ .id }"
      auth: "apiAuth"
```

**Properties**:
- **operation**: Operation ID from the OpenAPI specification
- **api**: URL or file path to OpenAPI specification
- **parameters**: Operation parameters (object)
- **auth**: Reference to authentication configuration
- **timeout**: Request timeout

**Notes**:
- Parameters are validated against the OpenAPI specification
- Response is parsed according to the specification

### Call gRPC

Makes gRPC calls to services.

```yaml
- getUserProfile:
    callGRPC:
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "${ .userId }"
```

**Properties**:
- **service**: Fully qualified service name
- **rpc**: RPC method name
- **message**: Request message (object)
- **auth**: Reference to authentication configuration
- **timeout**: Call timeout

**Notes**:
- Requires the service definition to be available
- Binary data is automatically encoded/decoded

### Call Function

Calls a function defined in a catalog.

```yaml
- processPayment:
    callFunction:
      function: "payments.processPayment"
      arguments:
        amount: "${ .order.total }"
        currency: "USD"
        customerId: "${ .customer.id }"
```

**Properties**:
- **function**: Function reference (`catalog.function`)
- **arguments**: Function arguments (object)
- **timeout**: Function call timeout

**Notes**:
- Functions must be defined in the catalog
- Functions can be implemented as Lambda, HTTP, or in-process

### Call AsyncAPI

Publishes or subscribes to messages using AsyncAPI specifications.

```yaml
- publishEvent:
    callAsyncAPI:
      publish:
        channel: "orders"
        message:
          key: "${ .order.id }"
          payload: "${ .order }"
```

**Properties**:
- **publish**: Publishing configuration
  - **channel**: Channel to publish to
  - **message**: Message to publish
    - **key**: Message key (for partitioning)
    - **payload**: Message payload
    - **headers**: Message headers
- **subscribe**: Subscription configuration
  - **channel**: Channel to subscribe to
  - **consume**: Consumption policy
    - **amount**: Number of messages to consume
    - **until**: Condition to stop consuming
    - **while**: Condition to continue consuming
  - **correlate**: Correlation criteria

**Notes**:
- AsyncAPI specification must be defined
- Message formats are validated against schemas

### Run Container

Executes a container.

```yaml
- processData:
    runContainer:
      image: "data-processor:latest"
      command: ["process", "--input", "${ .inputFile }"]
      volumes:
        - "/data:/data"
      environment:
        API_KEY: "${ .secrets.apiKey }"
      cleanup: "always"
```

**Properties**:
- **image**: Container image
- **command**: Command to execute (array)
- **volumes**: Volume mounts (array)
- **environment**: Environment variables (object)
- **cleanup**: Cleanup policy (`always`, `success`, `never`)
- **timeout**: Execution timeout

**Notes**:
- Requires container runtime (Docker, Podman)
- Container exit code determines success/failure

### Run Script

Executes a script.

```yaml
- calculateTotal:
    runScript:
      script:
        inline: |
          function calculate(items) {
            return items.reduce((sum, item) => sum + item.price * item.quantity, 0);
          }
          return calculate(input.items);
      arguments:
        items: "${ .cart.items }"
```

**Properties**:
- **script**: Script definition
  - **inline**: Inline script content
  - **external**: External script reference
- **arguments**: Script arguments (object)
- **language**: Script language (default: `javascript`)
- **timeout**: Execution timeout

**Notes**:
- Supported languages depend on runtime configuration
- Script output becomes the task output

### Run Workflow

Executes a subworkflow.

```yaml
- processOrder:
    runWorkflow:
      workflow: "orderProcessor"
      input: "${ .order }"
      waitForCompletion: true
```

**Properties**:
- **workflow**: Workflow name or URL
- **input**: Input data for the subworkflow
- **waitForCompletion**: Whether to wait for completion (default: `true`)
- **timeout**: Execution timeout

**Notes**:
- Input is validated against subworkflow's input schema
- Subworkflow output becomes the task output if waiting

## Data Manipulation Tasks

### Set Task

Sets or updates variables in the current context.

```yaml
- prepareData:
    set:
      orderStatus: "PROCESSING"
      timestamp: "${ now() }"
      customer:
        id: "${ .customerId }"
        type: "${ .premium ? 'premium' : 'standard' }"
```

**Properties**:
- Key-value pairs to set in the current context

**Notes**:
- Values can be literals or expressions
- Complex objects can be constructed
- Existing variables can be updated or new ones created

## Event Processing Tasks

### Emit Task

Emits an event to the event bus.

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

**Properties**:
- **event**: Event name
- **data**: Event payload
- **context**: Event metadata (object)
  - **source**: Event source identifier
  - **type**: Event type
  - **subject**: Event subject
  - **time**: Event timestamp (default: current time)
  - **correlationId**: For event correlation

**Notes**:
- Events are sent to the configured event broker
- CloudEvents format is used by default

### Wait Task

Pauses workflow execution for a specified duration.

```yaml
- delayProcessing:
    wait:
      duration: PT1H
```

**Properties**:
- **duration**: ISO 8601 duration (e.g., `PT10S`, `PT1H30M`)
- **until**: Wait until a specific ISO 8601 timestamp (alternative to duration)

**Notes**:
- Execution is suspended during the wait
- The workflow resumes automatically after the wait period

### Listen Task

Waits for events from the event bus.

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

**Properties**:
- **to**: Event consumption strategy
  - `"any"`: Any matching event
  - `"all"`: All specified events
  - `"one"`: Single specific event
- **events**: Array of events to listen for
  - **event**: Event name
  - **filter**: Expression to filter events
  - **as**: Variable name for the received event
- **timeout**: Maximum wait time
- **consume**: Consumption policy
  - **amount**: Number of events to consume
  - **until**: Condition to stop consuming
  - **while**: Condition to continue consuming

**Notes**:
- Execution is suspended until events are received or timeout
- Received events are available in the task output

## Error Handling Tasks

### Raise Task

Explicitly raises an error.

```yaml
- checkInventory:
    if: "${ .stock < .quantity }"
    raise:
      error: "outOfStock"  # Reference to a defined error
      with:
        details: "Requested ${ .quantity }, only ${ .stock } available"
```

**Properties**:
- **error**: Error name (from `use.errors`) or inline error definition
  - **type**: Error type URI
  - **status**: Error status code
  - **title**: Error title
  - **details**: Error details
- **with**: Override specific properties of the referenced error

**Notes**:
- Raised errors propagate according to normal error handling rules
- Errors can be caught by enclosing `try` blocks

## Common Task Properties

All tasks support these common properties:

```yaml
- taskName:
    taskType:
      # Task-specific configuration
    
    # Common properties
    if: "${ .condition == true }"
    timeout: PT30S
    metadata:
      description: "Task description"
      labels:
        category: "api"
```

- **if**: Conditional expression for task execution
- **timeout**: Task-specific timeout (ISO 8601 duration)
- **metadata**: Task metadata (description, labels, etc.)

## Related Resources

- [DSL Syntax Reference](lemline-ref-dsl-syntax.md)
- [Error Handling with Try-Catch](lemline-howto-try-catch.md)
- [HTTP Tasks](lemline-howto-http.md)
- [Workflow Examples](dsl-workflow-examples.md)