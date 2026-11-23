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
document:
  dsl: 1.0.3
  namespace: examples
  name: order-processing
  version: 1.0.0
  title: Order Processing Workflow
input:
  schema:
    document:
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
use:
  functions: {}
do:
  - receiveOrder:
      set: ${ . }
```

This schema ensures that workflow input contains the required fields with the correct data types and formats.

### Accessing Workflow Input

You can access workflow input in any task using the `$WORKFLOW.input` reference:

```yaml
  - ProcessOrder:
      set:
        orderId: ${ $workflow.input.orderId }
        customerId: ${ $workflow.input.customerId }
        items: ${ $workflow.input.items }
        timestamp: ${ $workflow.startedAt }
      then: NextTask
```

### Default Input Values

You can provide default values for workflow input fields:

```yaml
input:
  from: '${ {"priority": "NORMAL", "currency": "USD", "shipMethod": "STANDARD"} + . }'
```

These defaults are applied when the input doesn't contain the specified fields.

## Task Input Processing

### Implicit Input

By default, each task receives the output of the previous task as its input:

```yaml
  - TaskA:
      set:
        value: 42
        message: "Hello"
      then: TaskB
  - TaskB:
      set:
        originalValue: ${ .value }
        newMessage: ${ .message + ", World!" }
      then: TaskC
```

### Input Transformation

You can transform the input data before task execution:

```yaml
  - FormatData:
      set:
        formattedUser:
          id: ${ .user.id }
          name: ${ .user.name }
          email: ${ .contactInfo.email }
          phone: ${ .contactInfo.phone }
      then: NextTask
      input:
        from:
          user: ${ .userProfile }
          contactInfo:
            email: ${ .userProfile.email | ascii_downcase }
            phone: ${ .userProfile.phone | gsub("[^0-9]"; "") }
```

The `input.transform` field lets you reshape the incoming data before it's used in the task.

### Input Schema Validation

You can validate task input against a schema:

```yaml
  - ValidatePaymentInfo:
      set:
        validPayment: true
        formattedAmount: ${ "$" + (.amount | tostring) }
      then: ProcessPayment
      input:
        schema:
          document:
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
```

If the input doesn't match the schema, a validation error is raised.

## Task Output Processing

### Basic Output

The output of a task is the value defined in the `data` field:

```yaml
  - GenerateOutput:
      set:
        status: "SUCCESS"
        result: 42
        timestamp: ${ $context.currentTime }
      then: NextTask
```

This task produces an output object with `status`, `result`, and `timestamp` fields.

### Output Transformation

You can transform the output data before passing it to the next task:

```yaml
  - ProcessApiResponse:
      call: userService
      with: {}
      then: NextTask
      output:
        as:
          user: ${ .data.user }
          permissions: ${ .data.permissions | map(.name) }
          isAdmin: ${ .data.permissions | map(.name) | contains(["ADMIN"]) }
```

The `output.transform` field reshapes the task result before it's passed to the next task.

### Output Schema Validation

You can validate task output against a schema:

```yaml
  - GenerateUserProfile:
      set:
        profile:
          id: ${ .userId }
          name: ${ .firstName + " " + .lastName }
          email: ${ .emailAddress }
          memberSince: ${ .joinDate }
      then: NextTask
      output:
        schema:
          document:
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
```

If the output doesn't match the schema, a validation error is raised.

## Exporting Data to Global Context

### Basic Export

The `export` field lets you promote data to the global workflow context:

```yaml
  - FetchCustomerData:
      call: customerService
      with:
        customerId: ${ .customerId }
      then: NextTask
      export:
        as:
          customer: ${ .data }
```

After this task executes, the customer data is available throughout the workflow as `$WORKFLOW.customer`.

### Exporting Selected Fields

You can export specific fields rather than the entire output:

```yaml
  - ProcessOrder:
      call: orderService
      with:
        orderId: ${ .orderId }
      then: NextTask
      export:
        as:
          orderItems: ${ .items }
          orderTotal: ${ .total }
          shippingAddress: ${ .shippingAddress }
```

This exports only the selected fields to the global context.

### Export with Transformation

You can transform data during export:

```yaml
  - FetchProductData:
      call: productService
      with:
        productIds: ${ .items | map(.productId) }
      then: NextTask
      export:
        as:
          products: ${ .products }
          productMap: '${ .products | map({key: .id, value: .}) | from_entries }'
          categories: ${ .products | map(.category) | unique }
```

This exports the original products array plus derived data structures.

### Export Schema Validation

You can validate exported data against a schema:

```yaml
  - ValidateExportedData:
      call: dataService
      with:
        id: ${ .entityId }
      then: NextTask
      export:
        as:
          validatedEntity: ${ . }
        schema:
          document:
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
```

This ensures the exported data meets the specified schema requirements.

## Comprehensive Example: Order Processing

Here's a complete example showing input, output, and export in action:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: order-processing
  version: 1.0.0
  title: Order Processing
input:
  schema:
    document:
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
do:
  - ValidateOrder:
      set:
        orderId: ${ $workflow.input.orderId }
        customerId: ${ $workflow.input.customerId }
        items: ${ $workflow.input.items }
        timestamp: ${ $workflow.startedAt }
        isValid: ${ $workflow.input.items | length > 0 }
      then: CheckOrderValidity
      output:
        schema:
          document:
            type: object
            required: ["orderId", "customerId", "items", "isValid"]
            properties:
              isValid:
                type: boolean
  - CheckOrderValidity:
      switch:
        - case1:
            then: RejectOrder
            when: ${ .isValid == false }
        - default:
            then: FetchCustomer
  - FetchCustomer:
      call: customerService
      with:
        customerId: ${ .customerId }
      then: FetchProducts
      output:
        as:
          customer:
            id: ${ .id }
            name: ${ .firstName + " " + .lastName }
            email: ${ .email }
            tier: ${ .membershipTier }
            isVIP: ${ .membershipTier == "PLATINUM" // .membershipTier == "GOLD" }
      export:
        as:
          customer: ${ .customer }
  - FetchProducts:
      call: productService
      with:
        productIds: ${ .productIds }
      then: EnrichOrderItems
      input:
        from:
          productIds: ${ .items | map(.productId) }
      output:
        as:
          availableProducts: ${ .products | map(select(.stock > 0)) }
          unavailableProducts: ${ .products | map(select(.stock == 0)) }
          allAvailable: ${ .products | all(.stock > 0) }
      export:
        as:
          products: ${ .products }
          productMap: '${ .products | map({key: .id, value: .}) | from_entries }'
  - EnrichOrderItems:
      set:
        enrichedItems: '${ .items | map({ productId: (.productId), quantity: (.quantity), product: ($context.productMap[.productId]),
          price: ($context.productMap[.productId].price), subtotal: ($context.productMap[.productId].price * .quantity)})
          }'
        subtotal: ${ .enrichedItems | map(.subtotal) | add }
        hasUnavailableItems: ${ .unavailableProducts | length > 0 }
      then: CheckAvailability
      export:
        as:
          orderItems: ${ .enrichedItems }
          orderSubtotal: ${ .subtotal }
  - CheckAvailability:
      switch:
        - case1:
            then: HandleUnavailableItems
            when: ${ .hasUnavailableItems == true }
        - default:
            then: CalculateTotal
  - CalculateTotal:
      set:
        discount: ${ .baseSubtotal * .discountRate }
        tax: ${ "(\(.baseSubtotal) - \(.discount)) * \(.taxRate)" }
        total: ${ .baseSubtotal - .discount + .tax }
        summary:
          orderId: ${ .orderId }
          customer: ${ $context.customer.name }
          itemCount: ${ $context.orderItems | length }
          subtotal: ${ .baseSubtotal }
          discount: ${ .discount }
          tax: ${ .tax }
          total: ${ .total }
      then: ProcessPayment
      input:
        from:
          baseSubtotal: ${ $context.orderSubtotal }
          discountRate: ${ if $context.customer.isVIP then 0.1 else 0 end }
          taxRate: 0.08
      export:
        as:
          orderSummary: ${ .summary }
          orderTotal: ${ .total }
        schema:
          document:
            type: object
            required: ["orderSummary", "orderTotal"]
            properties:
              orderTotal:
                type: number
                minimum: 0
  - ProcessPayment:
      call: paymentService
      with:
        customerId: ${ $context.customer.id }
        orderId: ${ .orderId }
        amount: ${ $context.orderTotal }
        currency: "USD"
      then: CheckPaymentStatus
      output:
        as:
          paymentConfirmed: ${ .status == "SUCCESS" }
          transactionId: ${ .transactionId }
          paymentTimestamp: ${ .timestamp }
      export:
        as:
          payment:
            transactionId: ${ .transactionId }
            status: ${ .status }
            timestamp: ${ .paymentTimestamp }
  - CheckPaymentStatus:
      switch:
        - case1:
            then: CompleteOrder
            when: ${ .paymentConfirmed == true }
        - default:
            then: HandlePaymentFailure
  - CompleteOrder:
      set:
        orderConfirmation:
          orderId: ${ .orderId }
          customer: ${ $context.customer }
          items: ${ $context.orderItems }
          summary: ${ $context.orderSummary }
          payment: ${ $context.payment }
          status: "CONFIRMED"
          completedAt: ${ $context.currentTime }
      then: SendConfirmation
  - SendConfirmation:
      call: notificationService
      with:
        to: ${ $context.customer.email }
        subject: '${ "Order Confirmation: \(.orderId)" }'
        template: "order_confirmation"
        data: ${ .orderConfirmation }
      then: end
  - HandleUnavailableItems:
      set:
        unavailableItems: ${ .unavailableProducts | map(.name) }
        rejectionReason: '${ "Some items are unavailable: " + (.unavailableItems | join(", ")) }'
        status: "REJECTED"
      then: NotifyRejection
  - HandlePaymentFailure:
      set:
        rejectionReason: '${ "Payment failed: " + (.status // "Unknown error") }'
        status: "PAYMENT_FAILED"
      then: NotifyRejection
  - RejectOrder:
      set:
        status: "REJECTED"
        rejectionReason: "Invalid order"
      then: NotifyRejection
  - NotifyRejection:
      call: notificationService
      with:
        to: ${ $context.customer.email // .customerId }
        subject: '${ "Order .orderId: " + .status }'
        template: "order_rejection"
        data:
          orderId: ${ .orderId }
          status: ${ .status }
          reason: ${ .rejectionReason }
      then: end
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
  - Step1:
      set:
        intermediateResult: ${ .rawData | parse_json }
      then: Step2
  - Step2:
      set:
        processedData: ${ .intermediateResult | map(.value) }
      then: Step3
  - Step3:
      set:
        finalResult: ${ .processedData | add }
      then: FinalStep
      export:
        as:
          result: ${ .finalResult }
```

### Selective Processing with Condition-Based Export

Export different data based on conditions:

```yaml
  - ConditionalExport:
      set:
        status: ${ if .code == 200 then "SUCCESS" else "ERROR" end }
        successData: ${ if .code == 200 then .data else null end }
        errorData: ${ if .code != 200 then .error else null end }
      then: NextStep
      export:
        as:
          result: ${ if .status == "SUCCESS" then .successData else .errorData end }
          isError: ${ .status == "ERROR" }
```

### Parameterized Tasks

Use exported data as task parameters:

```yaml
  - SetParameters:
      set:
        parameters:
          pageSize: 25
          sortOrder: "desc"
          includeInactive: false
      then: RunQuery
      export:
        as:
          queryParams: ${ .parameters }
  - RunQuery:
      call: dataService
      with:
        entity: "customers"
        pageSize: ${ $context.queryParams.pageSize }
        sortOrder: ${ $context.queryParams.sortOrder }
        includeInactive: ${ $context.queryParams.includeInactive }
      then: ProcessResults
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