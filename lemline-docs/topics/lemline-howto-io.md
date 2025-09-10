---
title: How to use input, output, and export
---

# How to use input, output, and export

This guide explains how to work with data flow in Lemline workflows, focusing on input validation, output transformation, and exporting data to the global context. You'll learn how to control data as it flows through your workflow.

## Understanding Data Flow in Lemline

Lemline's data flow model consists of three main components:

1. **Input**: Data coming into a task, including validation and transformation
2. **Output**: Data produced by a task, including transformation and validation
3. **Export**: Data promoted to the global workflow context for use across the workflow

Understanding how to use these components effectively is key to building robust workflows.

## Working with Workflow Input

### Defining Workflow Input Schema

You can define an input schema at the workflow level to validate incoming data:

```yaml
id: order-processing
name: Order Processing Workflow
version: '1.0'
specVersion: '1.0'
start: ValidateOrder
input:
  schema:
    type: object
    required: ["orderId", "customerId", "items"]
    properties:
      orderId:
        type: string
        pattern: "^ORD-[0-9]{6}$"
      customerId:
        type: string
      items:
        type: array
        minItems: 1
        items:
          type: object
          required: ["productId", "quantity"]
          properties:
            productId:
              type: string
            quantity:
              type: integer
              minimum: 1
```

This schema ensures that workflow input contains the required fields with the correct data types and formats.

### Accessing Workflow Input

You can access workflow input in any task using the `$WORKFLOW.input` reference:

```yaml
- name: ProcessOrder
  type: set
  data:
    orderId: "$WORKFLOW.input.orderId"
    customerId: "$WORKFLOW.input.customerId"
    items: "$WORKFLOW.input.items"
    timestamp: "$WORKFLOW.startTime"
  next: NextTask
```

### Default Input Values

You can provide default values for workflow input fields:

```yaml
input:
  schema:
    # Schema definition as above
  defaults:
    priority: "NORMAL"
    currency: "USD"
    shipMethod: "STANDARD"
```

These defaults are applied when the input doesn't contain the specified fields.

## Task Input Processing

### Implicit Input

By default, each task receives the output of the previous task as its input:

```yaml
- name: TaskA
  type: set
  data:
    value: 42
    message: "Hello"
  next: TaskB

- name: TaskB
  type: set
  data:
    originalValue: ".value"  # Accesses the value (42) from TaskA
    newMessage: ".message + \", World!\""  # Uses the message from TaskA
  next: TaskC
```

### Input Transformation

You can transform the input data before task execution:

```yaml
- name: FormatData
  type: set
  input:
    transform:
      user: ".userProfile"
      contactInfo: {
        email: ".userProfile.email | ascii_downcase",
        phone: ".userProfile.phone | gsub(\"[^0-9]\"; \"\")"
      }
  data:
    formattedUser: {
      id: ".user.id",
      name: ".user.name",
      email: ".contactInfo.email",
      phone: ".contactInfo.phone"
    }
  next: NextTask
```

The `input.transform` field lets you reshape the incoming data before it's used in the task.

### Input Schema Validation

You can validate task input against a schema:

```yaml
- name: ValidatePaymentInfo
  type: set
  input:
    schema:
      type: object
      required: ["amount", "currency", "paymentMethod"]
      properties:
        amount:
          type: number
          minimum: 0.01
        currency:
          type: string
          enum: ["USD", "EUR", "GBP"]
        paymentMethod:
          type: object
          required: ["type"]
          properties:
            type:
              type: string
              enum: ["CREDIT", "DEBIT", "PAYPAL"]
  data:
    validPayment: true
    formattedAmount: "\"$\" + (.amount | tostring)"
  next: ProcessPayment
```

If the input doesn't match the schema, a validation error is raised.

## Task Output Processing

### Basic Output

The output of a task is the value defined in the `data` field:

```yaml
- name: GenerateOutput
  type: set
  data:
    status: "SUCCESS"
    result: 42
    timestamp: "$WORKFLOW.currentTime"
  next: NextTask
```

This task produces an output object with `status`, `result`, and `timestamp` fields.

### Output Transformation

You can transform the output data before passing it to the next task:

```yaml
- name: ProcessApiResponse
  type: call
  function: userService
  output:
    transform:
      user: ".data.user"
      permissions: ".data.permissions | map(.name)"
      isAdmin: ".data.permissions | map(.name) | contains([\"ADMIN\"])"
  next: NextTask
```

The `output.transform` field reshapes the task result before it's passed to the next task.

### Output Schema Validation

You can validate task output against a schema:

```yaml
- name: GenerateUserProfile
  type: set
  data:
    profile:
      id: ".userId"
      name: ".firstName + \" \" + .lastName"
      email: ".emailAddress"
      memberSince: ".joinDate"
  output:
    schema:
      type: object
      required: ["profile"]
      properties:
        profile:
          type: object
          required: ["id", "name", "email"]
          properties:
            id:
              type: string
            name:
              type: string
            email:
              type: string
              format: email
  next: NextTask
```

If the output doesn't match the schema, a validation error is raised.

## Exporting Data to Global Context

### Basic Export

The `export` field lets you promote data to the global workflow context:

```yaml
- name: FetchCustomerData
  type: call
  function: customerService
  data:
    customerId: ".customerId"
  export:
    as:
      customer: ".data"  # Export the customer data to global context
  next: NextTask
```

After this task executes, the customer data is available throughout the workflow as `$WORKFLOW.customer`.

### Exporting Selected Fields

You can export specific fields rather than the entire output:

```yaml
- name: ProcessOrder
  type: call
  function: orderService
  data:
    orderId: ".orderId"
  export:
    as:
      orderItems: ".items"
      orderTotal: ".total"
      shippingAddress: ".shippingAddress"
  next: NextTask
```

This exports only the selected fields to the global context.

### Export with Transformation

You can transform data during export:

```yaml
- name: FetchProductData
  type: call
  function: productService
  data:
    productIds: ".items | map(.productId)"
  export:
    as:
      products: ".products"
      productMap: ".products | map({key: .id, value: .}) | from_entries"
      categories: ".products | map(.category) | unique"
  next: NextTask
```

This exports the original products array plus derived data structures.

### Export Schema Validation

You can validate exported data against a schema:

```yaml
- name: ValidateExportedData
  type: call
  function: dataService
  data:
    id: ".entityId"
  export:
    as:
      validatedEntity: "."
    schema:
      type: object
      required: ["id", "name", "status"]
      properties:
        id:
          type: string
        name:
          type: string
        status:
          type: string
          enum: ["ACTIVE", "INACTIVE", "PENDING"]
  next: NextTask
```

This ensures the exported data meets the specified schema requirements.

## Comprehensive Example: Order Processing

Here's a complete example showing input, output, and export in action:

```yaml
id: order-processing
name: Order Processing
version: '1.0'
specVersion: '1.0'
start: ValidateOrder
input:
  schema:
    type: object
    required: ["orderId", "customerId", "items"]
    properties:
      orderId:
        type: string
      customerId:
        type: string
      items:
        type: array
        minItems: 1
        items:
          type: object
          required: ["productId", "quantity"]
tasks:
  - name: ValidateOrder
    type: set
    data:
      orderId: "$WORKFLOW.input.orderId"
      customerId: "$WORKFLOW.input.customerId"
      items: "$WORKFLOW.input.items"
      timestamp: "$WORKFLOW.startTime"
      isValid: "$WORKFLOW.input.items | length > 0"
    output:
      schema:
        type: object
        required: ["orderId", "customerId", "items", "isValid"]
        properties:
          isValid:
            type: boolean
    next: CheckOrderValidity
  
  - name: CheckOrderValidity
    type: switch
    conditions:
      - condition: ".isValid == false"
        next: RejectOrder
      - condition: true
        next: FetchCustomer
  
  - name: FetchCustomer
    type: call
    function: customerService
    data:
      customerId: ".customerId"
    output:
      transform:
        customer:
          id: ".id"
          name: ".firstName + ' ' + .lastName"
          email: ".email"
          tier: ".membershipTier"
          isVIP: ".membershipTier == 'PLATINUM' || .membershipTier == 'GOLD'"
    export:
      as:
        customer: ".customer"
    next: FetchProducts
  
  - name: FetchProducts
    type: call
    function: productService
    input:
      transform:
        productIds: ".items | map(.productId)"
    data:
      productIds: ".productIds"
    output:
      transform:
        availableProducts: ".products | map(select(.stock > 0))"
        unavailableProducts: ".products | map(select(.stock == 0))"
        allAvailable: ".products | all(.stock > 0)"
    export:
      as:
        products: ".products"
        productMap: ".products | map({key: .id, value: .}) | from_entries"
    next: EnrichOrderItems
  
  - name: EnrichOrderItems
    type: set
    data:
      enrichedItems: ".items | map({
        productId: .productId,
        quantity: .quantity,
        product: $WORKFLOW.productMap[.productId],
        price: $WORKFLOW.productMap[.productId].price,
        subtotal: $WORKFLOW.productMap[.productId].price * .quantity
      })"
      subtotal: ".enrichedItems | map(.subtotal) | add"
      hasUnavailableItems: ".unavailableProducts | length > 0"
    export:
      as:
        orderItems: ".enrichedItems"
        orderSubtotal: ".subtotal"
    next: CheckAvailability
  
  - name: CheckAvailability
    type: switch
    conditions:
      - condition: ".hasUnavailableItems == true"
        next: HandleUnavailableItems
      - condition: true
        next: CalculateTotal
  
  - name: CalculateTotal
    type: set
    input:
      transform:
        baseSubtotal: "$WORKFLOW.orderSubtotal"
        discountRate: "$WORKFLOW.customer.isVIP ? 0.1 : 0"
        taxRate: 0.08
    data:
      discount: ".baseSubtotal * .discountRate"
      tax: "(.baseSubtotal - .discount) * .taxRate"
      total: ".baseSubtotal - .discount + .tax"
      summary: {
        orderId: ".orderId",
        customer: "$WORKFLOW.customer.name",
        itemCount: "$WORKFLOW.orderItems | length",
        subtotal: ".baseSubtotal",
        discount: ".discount",
        tax: ".tax",
        total: ".total"
      }
    export:
      as:
        orderSummary: ".summary"
        orderTotal: ".total"
      schema:
        type: object
        required: ["orderSummary", "orderTotal"]
        properties:
          orderTotal:
            type: number
            minimum: 0
    next: ProcessPayment
  
  - name: ProcessPayment
    type: call
    function: paymentService
    data:
      customerId: "$WORKFLOW.customer.id"
      orderId: ".orderId"
      amount: "$WORKFLOW.orderTotal"
      currency: "USD"
    output:
      transform:
        paymentConfirmed: ".status == 'SUCCESS'"
        transactionId: ".transactionId"
        paymentTimestamp: ".timestamp"
    export:
      as:
        payment: {
          transactionId: ".transactionId",
          status: ".status",
          timestamp: ".paymentTimestamp"
        }
    next: CheckPaymentStatus
  
  - name: CheckPaymentStatus
    type: switch
    conditions:
      - condition: ".paymentConfirmed == true"
        next: CompleteOrder
      - condition: true
        next: HandlePaymentFailure
  
  - name: CompleteOrder
    type: set
    data:
      orderConfirmation: {
        orderId: ".orderId",
        customer: "$WORKFLOW.customer",
        items: "$WORKFLOW.orderItems",
        summary: "$WORKFLOW.orderSummary",
        payment: "$WORKFLOW.payment",
        status: "CONFIRMED",
        completedAt: "$WORKFLOW.currentTime"
      }
    next: SendConfirmation
  
  - name: SendConfirmation
    type: call
    function: notificationService
    data:
      to: "$WORKFLOW.customer.email"
      subject: "Order Confirmation: .orderId"
      template: "order_confirmation"
      data: ".orderConfirmation"
    end: true
  
  - name: HandleUnavailableItems
    type: set
    data:
      unavailableItems: ".unavailableProducts | map(.name)"
      rejectionReason: "Some items are unavailable: " + (.unavailableItems | join(", "))
      status: "REJECTED"
    next: NotifyRejection
  
  - name: HandlePaymentFailure
    type: set
    data:
      rejectionReason: "Payment failed: " + (.status || "Unknown error")
      status: "PAYMENT_FAILED"
    next: NotifyRejection
  
  - name: RejectOrder
    type: set
    data:
      status: "REJECTED"
      rejectionReason: "Invalid order"
    next: NotifyRejection
  
  - name: NotifyRejection
    type: call
    function: notificationService
    data:
      to: "$WORKFLOW.customer.email || .customerId"
      subject: "Order .orderId: " + .status
      template: "order_rejection"
      data: {
        orderId: ".orderId",
        status: ".status",
        reason: ".rejectionReason"
      }
    end: true
```

This workflow demonstrates:

1. **Workflow input schema** validation to ensure required fields
2. **Task input transformation** to prepare data for service calls
3. **Task output transformation** to reshape service responses
4. **Data export** to make critical data available globally
5. **Schema validation** at various points to ensure data integrity

## Advanced Patterns

### Chain of Responsibility

Chain multiple data transformations for complex processing:

```yaml
- name: Step1
  type: set
  data:
    intermediateResult: ".rawData | parse_json"
  next: Step2

- name: Step2
  type: set
  data:
    processedData: ".intermediateResult | map(.value)"
  next: Step3

- name: Step3
  type: set
  data:
    finalResult: ".processedData | add"
  export:
    as:
      result: ".finalResult"
  next: FinalStep
```

### Selective Processing with Condition-Based Export

Export different data based on conditions:

```yaml
- name: ConditionalExport
  type: set
  data:
    status: ".code == 200 ? 'SUCCESS' : 'ERROR'"
    successData: "if .code == 200 then .data else null end"
    errorData: "if .code != 200 then .error else null end"
  export:
    as:
      result: ".status == 'SUCCESS' ? .successData : .errorData"
      isError: ".status == 'ERROR'"
  next: NextStep
```

### Parameterized Tasks

Use exported data as task parameters:

```yaml
- name: SetParameters
  type: set
  data:
    parameters: {
      pageSize: 25,
      sortOrder: "desc",
      includeInactive: false
    }
  export:
    as:
      queryParams: ".parameters"
  next: RunQuery

- name: RunQuery
  type: call
  function: dataService
  data:
    entity: "customers"
    pageSize: "$WORKFLOW.queryParams.pageSize"
    sortOrder: "$WORKFLOW.queryParams.sortOrder"
    includeInactive: "$WORKFLOW.queryParams.includeInactive"
  next: ProcessResults
```

## Best Practices

1. **Validate Early**: Add schema validation as early as possible in the workflow
2. **Use Specific Schemas**: Create detailed, specific schemas for validation
3. **Transform Near Use**: Do data transformations close to where the data is used
4. **Export Judiciously**: Only export data needed by multiple tasks
5. **Document the Data Flow**: Add comments to explain complex transformations
6. **Use Meaningful Names**: Name exported data with clear, descriptive identifiers
7. **Prefer Task Chaining**: Pass data through task chains rather than global context when possible
8. **Validate Exported Data**: Add schemas to exported data for integrity
9. **Minimize Global State**: Avoid exporting too much to the global context
10. **Clean Structure**: Structure data consistently across the workflow

## Common Errors and Solutions

### Schema Validation Errors

**Problem**: Input data doesn't match the schema.
**Solution**: 
- Verify data structure matches schema requirements
- Check for missing required fields
- Ensure data types are correct
- Use default values for optional fields

### Missing Context Variables

**Problem**: Accessing `$WORKFLOW.variable` that doesn't exist.
**Solution**:
- Ensure the variable is exported before use
- Check for typos in variable names
- Add null checks: `$WORKFLOW.variable // defaultValue`

### Transformation Errors

**Problem**: jq transformation expressions fail.
**Solution**:
- Break complex transformations into simpler steps
- Use `try/catch` in expressions for error handling
- Add explicit type handling
- Debug with a separate diagnostic task

## Next Steps

- Learn about [writing jq runtime expressions](lemline-howto-jq.md)
- Explore [validating inputs and outputs with schemas](lemline-howto-schemas.md)
- Understand [passing data between tasks](lemline-howto-data-passing.md)
- Dive deeper into [runtime expressions and data flow](lemline-explain-jq.md)