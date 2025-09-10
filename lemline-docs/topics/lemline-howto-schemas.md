---
title: How to validate inputs and outputs with schemas
---

# How to validate inputs and outputs with schemas

This guide explains how to implement data validation in Lemline workflows using JSON Schema. You'll learn how to validate workflow inputs, task inputs and outputs, and exported data to ensure data quality and prevent errors.

## Understanding Schema Validation in Lemline

Schema validation allows you to define the expected structure, types, and constraints for your data. Lemline uses JSON Schema (draft-07) for validation at various points in the workflow:

- **Workflow Input**: Validating data passed to the workflow
- **Task Input**: Validating data before task execution
- **Task Output**: Validating data produced by a task
- **Export Data**: Validating data promoted to the global context

By defining schemas, you can catch data issues early, before they cause problems downstream, making your workflows more robust and reliable.

## Basic Schema Structure

A JSON Schema defines the structure and constraints for JSON data:

```json
{
  "type": "object",
  "required": ["name", "email"],
  "properties": {
    "name": {
      "type": "string",
      "minLength": 1
    },
    "email": {
      "type": "string",
      "format": "email"
    },
    "age": {
      "type": "integer",
      "minimum": 18
    }
  }
}
```

This schema validates an object with:
- Required `name` and `email` properties
- `name` must be a non-empty string
- `email` must be a valid email address
- Optional `age` must be an integer of at least 18 if present

## Validating Workflow Input

### Adding an Input Schema

To validate data passed to your workflow, define an input schema:

```yaml
id: customer-onboarding
name: Customer Onboarding Workflow
version: '1.0'
specVersion: '1.0'
start: ValidateCustomer
input:
  schema:
    type: object
    required: ["name", "email", "address"]
    properties:
      name:
        type: string
        minLength: 1
      email:
        type: string
        format: email
      address:
        type: object
        required: ["street", "city", "zipCode"]
        properties:
          street:
            type: string
          city:
            type: string
          state:
            type: string
          zipCode:
            type: string
            pattern: "^\\d{5}(-\\d{4})?$"
      phoneNumber:
        type: string
        pattern: "^\\+?[1-9]\\d{1,14}$"
```

### Providing Default Values

You can specify default values for optional fields:

```yaml
input:
  schema:
    # Schema definition as above
  defaults:
    preferredContact: "email"
    marketingOptIn: false
    accountType: "STANDARD"
```

These defaults are applied when the input doesn't contain the specified fields.

### Accessing Validated Input

After validation, you can access the input data in your workflow:

```yaml
- name: ProcessCustomer
  type: set
  data:
    customer: {
      name: "$WORKFLOW.input.name",
      email: "$WORKFLOW.input.email",
      address: "$WORKFLOW.input.address",
      phone: "$WORKFLOW.input.phoneNumber || 'Not provided'",
      preferredContact: "$WORKFLOW.input.preferredContact"
    }
  next: CreateAccount
```

## Task Input Validation

### Validating Task Input

To validate data before a task executes:

```yaml
- name: ProcessPayment
  type: call
  function: paymentService
  input:
    schema:
      type: object
      required: ["amount", "currency", "paymentMethod"]
      properties:
        amount:
          type: number
          exclusiveMinimum: 0
        currency:
          type: string
          enum: ["USD", "EUR", "GBP"]
        paymentMethod:
          type: object
          required: ["type", "token"]
          properties:
            type:
              type: string
              enum: ["CREDIT_CARD", "PAYPAL", "BANK_TRANSFER"]
            token:
              type: string
  data:
    amount: ".cart.total"
    currency: ".cart.currency"
    paymentMethod: ".payment"
  next: HandlePaymentResult
```

If the input doesn't match the schema, a validation error is raised before the task executes.

### Input Transformation and Validation

You can combine transformation with validation:

```yaml
- name: FormatUserData
  type: set
  input:
    transform:
      formattedUser: {
        id: ".userId",
        fullName: ".firstName + ' ' + .lastName",
        email: ".email | ascii_downcase",
        age: ".age | tonumber"
      }
    schema:
      type: object
      required: ["formattedUser"]
      properties:
        formattedUser:
          type: object
          required: ["id", "fullName", "email"]
          properties:
            id:
              type: string
            fullName:
              type: string
              minLength: 3
            email:
              type: string
              format: email
            age:
              type: integer
              minimum: 13
  data:
    userProfile: ".formattedUser"
    isAdult: ".formattedUser.age >= 18"
  next: NextTask
```

This example transforms the input data and then validates the transformed structure.

## Task Output Validation

### Validating Task Output

To validate data produced by a task:

```yaml
- name: GenerateOrderSummary
  type: set
  data:
    orderSummary: {
      id: ".orderId",
      customer: {
        id: ".customerId",
        name: ".customerName"
      },
      items: ".items | map({
        id: .productId,
        name: .productName,
        quantity: .quantity,
        price: .price
      })",
      subtotal: ".items | map(.price * .quantity) | add",
      tax: ".items | map(.price * .quantity) | add * 0.08",
      total: ".items | map(.price * .quantity) | add * 1.08"
    }
  output:
    schema:
      type: object
      required: ["orderSummary"]
      properties:
        orderSummary:
          type: object
          required: ["id", "customer", "items", "subtotal", "tax", "total"]
          properties:
            id:
              type: string
            customer:
              type: object
              required: ["id", "name"]
            items:
              type: array
              minItems: 1
              items:
                type: object
                required: ["id", "name", "quantity", "price"]
            subtotal:
              type: number
              minimum: 0
            tax:
              type: number
              minimum: 0
            total:
              type: number
              minimum: 0
  next: NextTask
```

If the output doesn't match the schema, a validation error is raised after the task executes.

### Output Transformation and Validation

Combine transformation with validation:

```yaml
- name: ProcessAPIResponse
  type: call
  function: externalAPI
  data:
    endpoint: "/data"
    parameters: ".queryParams"
  output:
    transform:
      status: ".code == 200 ? 'SUCCESS' : 'ERROR'"
      data: ".code == 200 ? .body.data : null"
      error: ".code != 200 ? .body.error : null"
    schema:
      type: object
      required: ["status"]
      properties:
        status:
          type: string
          enum: ["SUCCESS", "ERROR"]
        data:
          type: object
          # Only require data structure if status is SUCCESS
          if:
            properties:
              status:
                const: "SUCCESS"
            required: ["status"]
          then:
            required: ["data"]
        error:
          type: object
          # Only require error structure if status is ERROR
          if:
            properties:
              status:
                const: "ERROR"
            required: ["status"]
          then:
            required: ["error"]
  next: HandleResult
```

This transforms the API response into a standardized format and validates the structure.

## Validating Exported Data

### Export Schema Validation

To validate data being exported to the global context:

```yaml
- name: FetchCustomerData
  type: call
  function: customerService
  data:
    customerId: ".customerId"
  export:
    as:
      customer: ".data"
    schema:
      type: object
      required: ["customer"]
      properties:
        customer:
          type: object
          required: ["id", "name", "email", "status"]
          properties:
            id:
              type: string
            name:
              type: string
            email:
              type: string
              format: email
            status:
              type: string
              enum: ["ACTIVE", "INACTIVE", "PENDING"]
  next: NextTask
```

This ensures that only valid customer data is exported to the global context.

## Advanced Schema Features

### Complex Property Dependencies

You can define dependencies between properties:

```yaml
schema:
  type: object
  properties:
    shippingMethod:
      type: string
      enum: ["STANDARD", "EXPRESS", "SAME_DAY"]
    deliveryDate:
      type: string
      format: date
  dependencies:
    shippingMethod:
      oneOf:
        - properties:
            shippingMethod:
              enum: ["SAME_DAY"]
            deliveryDate:
              type: string
          required: ["deliveryDate"]
        - properties:
            shippingMethod:
              enum: ["EXPRESS", "STANDARD"]
```

This schema requires `deliveryDate` only when `shippingMethod` is "SAME_DAY".

### Conditional Schemas with if/then/else

You can apply different validations based on conditions:

```yaml
schema:
  type: object
  properties:
    paymentMethod:
      type: string
      enum: ["CREDIT_CARD", "BANK_TRANSFER", "PAYPAL"]
    cardNumber:
      type: string
    expiryDate:
      type: string
    accountNumber:
      type: string
    routingNumber:
      type: string
    paypalEmail:
      type: string
  required: ["paymentMethod"]
  if:
    properties:
      paymentMethod:
        enum: ["CREDIT_CARD"]
    required: ["paymentMethod"]
  then:
    required: ["cardNumber", "expiryDate"]
  else:
    if:
      properties:
        paymentMethod:
          enum: ["BANK_TRANSFER"]
      required: ["paymentMethod"]
    then:
      required: ["accountNumber", "routingNumber"]
    else:
      required: ["paypalEmail"]
```

This schema requires different fields based on the payment method selected.

### Pattern Properties

You can validate properties that match a pattern:

```yaml
schema:
  type: object
  properties:
    name:
      type: string
  patternProperties:
    "^custom_":
      type: string
  additionalProperties: false
```

This schema allows any property that starts with "custom_" (and requires it to be a string), plus the "name" property, but no others.

### Advanced String Formats

JSON Schema supports several built-in formats for string validation:

```yaml
schema:
  type: object
  properties:
    email:
      type: string
      format: email
    website:
      type: string
      format: uri
    date:
      type: string
      format: date
    time:
      type: string
      format: time
    dateTime:
      type: string
      format: date-time
    ipAddress:
      type: string
      format: ipv4
    uuid:
      type: string
      format: uuid
```

### Array Validation

For validating arrays and their contents:

```yaml
schema:
  type: object
  properties:
    tags:
      type: array
      minItems: 1
      maxItems: 5
      uniqueItems: true
      items:
        type: string
        minLength: 2
    matrix:
      type: array
      items:
        type: array
        items:
          type: number
    coordinates:
      type: array
      items:
        type: number
      minItems: 2
      maxItems: 3
```

### Content Media Type

You can specify the expected media type for string content:

```yaml
schema:
  type: object
  properties:
    htmlContent:
      type: string
      contentMediaType: "text/html"
    csvData:
      type: string
      contentMediaType: "text/csv"
    jsonData:
      type: string
      contentMediaType: "application/json"
```

## Real-World Example: Order Processing

Here's a comprehensive example showing schema validation throughout an order processing workflow:

```yaml
id: order-processing
name: Order Processing Workflow
version: '1.0'
specVersion: '1.0'
start: ValidateOrder
input:
  schema:
    type: object
    required: ["orderId", "customerId", "items", "shippingAddress", "paymentMethod"]
    properties:
      orderId:
        type: string
        pattern: "^ORD-[0-9]{8}$"
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
      shippingAddress:
        type: object
        required: ["street", "city", "state", "zipCode", "country"]
        properties:
          street:
            type: string
          city:
            type: string
          state:
            type: string
          zipCode:
            type: string
          country:
            type: string
            default: "US"
      paymentMethod:
        type: object
        required: ["type"]
        properties:
          type:
            type: string
            enum: ["CREDIT_CARD", "PAYPAL", "BANK_TRANSFER"]
          cardToken:
            type: string
          paypalEmail:
            type: string
          accountInfo:
            type: object
  defaults:
    currency: "USD"
    notes: ""
tasks:
  - name: ValidateOrder
    type: set
    data:
      orderId: "$WORKFLOW.input.orderId"
      customerId: "$WORKFLOW.input.customerId"
      items: "$WORKFLOW.input.items"
      shippingAddress: "$WORKFLOW.input.shippingAddress"
      paymentMethod: "$WORKFLOW.input.paymentMethod"
      currency: "$WORKFLOW.input.currency"
      notes: "$WORKFLOW.input.notes"
    next: FetchCustomer
  
  - name: FetchCustomer
    type: call
    function: customerService
    data:
      customerId: ".customerId"
    output:
      schema:
        type: object
        required: ["id", "name", "email", "status"]
        properties:
          id:
            type: string
          name:
            type: string
          email:
            type: string
            format: email
          status:
            type: string
            enum: ["ACTIVE", "INACTIVE", "SUSPENDED"]
          membershipTier:
            type: string
    export:
      as:
        customer: "."
      schema:
        type: object
        required: ["customer"]
        properties:
          customer:
            type: object
            required: ["id", "name", "email", "status"]
    next: CheckCustomerStatus
  
  - name: CheckCustomerStatus
    type: switch
    conditions:
      - condition: "$WORKFLOW.customer.status != 'ACTIVE'"
        next: RejectInactiveCustomer
      - condition: true
        next: CheckInventory
  
  - name: CheckInventory
    type: call
    function: inventoryService
    input:
      transform:
        productRequests: ".items | map({
          productId: .productId,
          quantity: .quantity
        })"
      schema:
        type: object
        required: ["productRequests"]
        properties:
          productRequests:
            type: array
            minItems: 1
            items:
              type: object
              required: ["productId", "quantity"]
    data:
      items: ".productRequests"
    output:
      schema:
        type: object
        required: ["available", "unavailableItems", "items"]
        properties:
          available:
            type: boolean
          unavailableItems:
            type: array
            items:
              type: object
              required: ["productId", "requestedQuantity", "availableQuantity"]
          items:
            type: array
            items:
              type: object
              required: ["productId", "name", "price", "availableQuantity"]
    next: CheckInventoryResult
  
  - name: CheckInventoryResult
    type: switch
    conditions:
      - condition: ".available == false"
        next: HandleUnavailableItems
      - condition: true
        next: CalculateOrderTotal
  
  - name: CalculateOrderTotal
    type: set
    input:
      transform:
        products: ".items"
        orderItems: ".items | map({
          productId: .productId,
          name: .name,
          unitPrice: .price,
          quantity: (.productId as $pid | $WORKFLOW.input.items[] | select(.productId == $pid) | .quantity)
        })"
      schema:
        type: object
        required: ["orderItems"]
        properties:
          orderItems:
            type: array
            minItems: 1
            items:
              type: object
              required: ["productId", "name", "unitPrice", "quantity"]
    data:
      items: ".orderItems"
      subtotal: ".orderItems | map(.unitPrice * .quantity) | add"
      discountRate: "$WORKFLOW.customer.membershipTier == 'GOLD' ? 0.05 : ($WORKFLOW.customer.membershipTier == 'PLATINUM' ? 0.1 : 0)"
      discount: ".subtotal * .discountRate"
      taxRate: 0.08
      tax: "(.subtotal - .discount) * .taxRate"
      total: ".subtotal - .discount + .tax"
      orderSummary: {
        orderId: ".orderId",
        customerId: ".customerId",
        customerName: "$WORKFLOW.customer.name",
        items: ".items",
        pricing: {
          subtotal: ".subtotal",
          discount: ".discount",
          tax: ".tax",
          total: ".total"
        },
        currency: ".currency"
      }
    output:
      schema:
        type: object
        required: ["orderSummary", "total"]
        properties:
          orderSummary:
            type: object
            required: ["orderId", "customerId", "customerName", "items", "pricing"]
          total:
            type: number
            minimum: 0
    export:
      as:
        orderDetails: ".orderSummary"
        orderTotal: ".total"
      schema:
        type: object
        required: ["orderDetails", "orderTotal"]
    next: ProcessPayment
  
  - name: ProcessPayment
    type: call
    function: paymentService
    input:
      transform:
        paymentRequest: {
          orderId: ".orderId",
          customerId: ".customerId",
          amount: ".total",
          currency: ".currency",
          method: ".paymentMethod.type",
          token: ".paymentMethod.cardToken",
          email: ".paymentMethod.paypalEmail",
          accountInfo: ".paymentMethod.accountInfo"
        }
      schema:
        type: object
        required: ["paymentRequest"]
        properties:
          paymentRequest:
            type: object
            required: ["orderId", "customerId", "amount", "currency", "method"]
            if:
              properties:
                method:
                  enum: ["CREDIT_CARD"]
              required: ["method"]
            then:
              required: ["token"]
            else:
              if:
                properties:
                  method:
                    enum: ["PAYPAL"]
                required: ["method"]
              then:
                required: ["email"]
              else:
                required: ["accountInfo"]
    data:
      payment: ".paymentRequest"
    output:
      schema:
        type: object
        required: ["success", "transactionId", "status"]
        properties:
          success:
            type: boolean
          transactionId:
            type: string
          status:
            type: string
          errorCode:
            type: string
          errorMessage:
            type: string
        if:
          properties:
            success:
              enum: [false]
          required: ["success"]
        then:
          required: ["errorCode", "errorMessage"]
    next: CheckPaymentResult
  
  - name: CheckPaymentResult
    type: switch
    conditions:
      - condition: ".success == true"
        next: CreateShipment
      - condition: true
        next: HandlePaymentFailure
  
  - name: CreateShipment
    type: call
    function: shippingService
    input:
      schema:
        type: object
        required: ["orderId", "items", "shippingAddress", "customerEmail"]
        properties:
          orderId:
            type: string
          items:
            type: array
            minItems: 1
          shippingAddress:
            type: object
            required: ["street", "city", "state", "zipCode", "country"]
          customerEmail:
            type: string
            format: email
    data:
      orderId: ".orderId"
      items: ".items"
      shippingAddress: ".shippingAddress"
      customerEmail: "$WORKFLOW.customer.email"
    output:
      schema:
        type: object
        required: ["shipmentId", "trackingNumber", "estimatedDelivery", "carrier"]
        properties:
          shipmentId:
            type: string
          trackingNumber:
            type: string
          estimatedDelivery:
            type: string
            format: date
          carrier:
            type: string
    export:
      as:
        shipment: "."
    next: CompleteOrder
  
  - name: CompleteOrder
    type: set
    data:
      orderConfirmation: {
        orderId: ".orderId",
        customer: {
          id: ".customerId",
          name: "$WORKFLOW.customer.name",
          email: "$WORKFLOW.customer.email"
        },
        items: ".items",
        summary: {
          subtotal: "$WORKFLOW.orderDetails.pricing.subtotal",
          discount: "$WORKFLOW.orderDetails.pricing.discount",
          tax: "$WORKFLOW.orderDetails.pricing.tax",
          total: "$WORKFLOW.orderDetails.pricing.total",
          currency: ".currency"
        },
        payment: {
          transactionId: ".transactionId",
          method: ".paymentMethod.type"
        },
        shipping: {
          shipmentId: "$WORKFLOW.shipment.shipmentId",
          trackingNumber: "$WORKFLOW.shipment.trackingNumber",
          estimatedDelivery: "$WORKFLOW.shipment.estimatedDelivery",
          carrier: "$WORKFLOW.shipment.carrier",
          address: ".shippingAddress"
        },
        status: "CONFIRMED",
        createdAt: "$WORKFLOW.startTime",
        completedAt: "$WORKFLOW.currentTime"
      }
    output:
      schema:
        type: object
        required: ["orderConfirmation"]
        properties:
          orderConfirmation:
            type: object
            required: ["orderId", "customer", "items", "summary", "payment", "shipping", "status"]
    next: SendConfirmation
  
  - name: SendConfirmation
    type: call
    function: notificationService
    data:
      to: "$WORKFLOW.customer.email"
      subject: "Order Confirmed: .orderId"
      template: "order_confirmation"
      data: ".orderConfirmation"
    end: true
  
  # Error handling tasks with similar schema validations...
```

This workflow demonstrates schema validation at multiple points:
1. Workflow input validation to ensure required order fields
2. Output schema validation after service calls
3. Validation of data being exported to the global context
4. Input transformations with schema validation
5. Complex conditional schemas for payment method validation

## Schema Reuse and References

For complex schemas that are used in multiple places, you can use references:

```yaml
# Define schema components at the workflow level
schemas:
  addressSchema:
    type: object
    required: ["street", "city", "zipCode"]
    properties:
      street:
        type: string
      city:
        type: string
      state:
        type: string
      zipCode:
        type: string
      country:
        type: string
        default: "US"
  
  customerSchema:
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
      address:
        $ref: "#/schemas/addressSchema"

# Reference them in tasks
tasks:
  - name: ValidateCustomer
    type: set
    data:
      customer: {
        id: ".customerId",
        name: ".fullName",
        email: ".emailAddress",
        address: ".shippingAddress"
      }
    output:
      schema:
        $ref: "#/schemas/customerSchema"
    next: NextTask
```

## Best Practices for Schema Validation

1. **Validate Early**: Add schema validation as early as possible in the workflow
2. **Be Specific**: Create detailed, specific schemas that precisely define your data requirements
3. **Default Values**: Provide defaults for optional fields when appropriate
4. **Schema Reuse**: Use schema references to avoid duplication
5. **Document Schemas**: Add comments to explain complex validation requirements
6. **Incremental Validation**: Validate at multiple points to catch issues early
7. **Performance Consideration**: Balance validation thoroughness with performance needs
8. **Version Schemas**: Include version information for evolving schemas
9. **Error Messages**: Configure clear, helpful validation error messages
10. **Test Edge Cases**: Test your schemas with both valid and invalid data

## Troubleshooting Schema Validation

### Common Validation Errors

1. **Missing Required Properties**:
   ```
   Validation error: Required property 'customerId' is missing
   ```
   Solution: Ensure all required properties are present in your data.

2. **Type Mismatches**:
   ```
   Validation error: Property 'quantity' must be of type integer
   ```
   Solution: Check data types and use transformation to convert if needed.

3. **Pattern Matching Failures**:
   ```
   Validation error: Property 'zipCode' does not match pattern '^\\d{5}(-\\d{4})?$'
   ```
   Solution: Ensure string values match the required patterns.

4. **Numeric Constraints**:
   ```
   Validation error: Property 'price' must be >= 0
   ```
   Solution: Verify numeric values meet the defined constraints.

5. **Enum Validation**:
   ```
   Validation error: Property 'status' must be one of: ACTIVE, INACTIVE, PENDING
   ```
   Solution: Check that enum values match one of the allowed values.

### Debugging Validation Issues

When troubleshooting validation problems:

1. **Add Diagnostic Tasks**: Insert temporary tasks to inspect data before validation
2. **Simplify Schemas**: Temporarily simplify complex schemas to isolate issues
3. **Check Data Types**: Verify that data types match schema requirements
4. **Inspect Transformations**: Ensure transformations produce data matching the schema
5. **Validate Externally**: Use online JSON Schema validators to test schemas independently

## Next Steps

- Learn about [writing jq runtime expressions](lemline-howto-jq.md)
- Explore [input, output, and export](lemline-howto-io.md)
- Understand [how to pass data between tasks](lemline-howto-data-passing.md)
- Dive deeper into [data flow concepts](lemline-explain-jq.md)