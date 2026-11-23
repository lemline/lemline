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
document:
  dsl: 1.0.3
  namespace: examples
  name: customer-onboarding
  version: 1.0.0
  title: Customer Onboarding Workflow
input:
  schema:
    document:
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
use:
  functions: {}
do:
  - receiveOrder:
      set: ${ . }
```

### Providing Default Values

You can specify default values for optional fields:

```yaml
input:
  from: '${ {"preferredContact": "email", "marketingOptIn": false, "accountType": "STANDARD"} + . }'
```

These defaults are applied when the input doesn't contain the specified fields.

### Accessing Validated Input

After validation, you can access the input data in your workflow:

```yaml
  - ProcessCustomer:
      set:
        customer:
          name: ${ $workflow.input.name }
          email: ${ $workflow.input.email }
          address: ${ $workflow.input.address }
          phone: ${ $workflow.input.phoneNumber // "Not provided" }
          preferredContact: ${ $workflow.input.preferredContact }
      then: CreateAccount
```

## Task Input Validation

### Validating Task Input

To validate data before a task executes:

```yaml
  - ProcessPayment:
      call: paymentService
      with:
        amount: ${ .cart.total }
        currency: ${ .cart.currency }
        paymentMethod: ${ .payment }
      then: HandlePaymentResult
      input:
        schema:
          document:
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
```

If the input doesn't match the schema, a validation error is raised before the task executes.

### Input Transformation and Validation

You can combine transformation with validation:

```yaml
  - FormatUserData:
      set:
        userProfile: ${ .formattedUser }
        isAdult: ${ .formattedUser.age >= 18 }
      then: NextTask
      input:
        schema:
          document:
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
        from:
          formattedUser:
            id: ${ .userId }
            fullName: ${ .firstName + " " + .lastName }
            email: ${ .email | ascii_downcase }
            age: ${ .age | tonumber }
```

This example transforms the input data and then validates the transformed structure.

## Task Output Validation

### Validating Task Output

To validate data produced by a task:

```yaml
  - GenerateOrderSummary:
      set:
        orderSummary:
          id: ${ .orderId }
          customer:
            id: ${ .customerId }
            name: ${ .customerName }
          items: '${ .items | map({ id: .productId, name: .productName, quantity: .quantity, price: .price }) }'
          subtotal: ${ .items | map(.price * .quantity) | add }
          tax: ${ .items | map(.price * .quantity) | add * 0.08 }
          total: ${ .items | map(.price * .quantity) | add * 1.08 }
      then: NextTask
      output:
        schema:
          document:
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
```

If the output doesn't match the schema, a validation error is raised after the task executes.

### Output Transformation and Validation

Combine transformation with validation:

```yaml
  - ProcessAPIResponse:
      call: externalAPI
      with:
        endpoint: "/data"
        parameters: ${ .queryParams }
      then: HandleResult
      output:
        schema:
          document:
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
        as:
          status: ${ if .code == 200 then "SUCCESS" else "ERROR" end }
          data: ${ if .code == 200 then .body.data else null end }
          error: ${ if .code != 200 then .body.error else null end }
```

This transforms the API response into a standardized format and validates the structure.

## Validating Exported Data

### Export Schema Validation

To validate data being exported to the global context:

```yaml
  - FetchCustomerData:
      call: customerService
      with:
        customerId: ${ .customerId }
      then: NextTask
      export:
        as:
          customer: ${ .data }
        schema:
          document:
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
  from: '${ {"currency": "USD", "notes": ""} + . }'
do:
  - ValidateOrder:
      set:
        orderId: ${ $workflow.input.orderId }
        customerId: ${ $workflow.input.customerId }
        items: ${ $workflow.input.items }
        shippingAddress: ${ $workflow.input.shippingAddress }
        paymentMethod: ${ $workflow.input.paymentMethod }
        currency: ${ $workflow.input.currency }
        notes: ${ $workflow.input.notes }
      then: FetchCustomer
  - FetchCustomer:
      call: customerService
      with:
        customerId: ${ .customerId }
      then: CheckCustomerStatus
      output:
        schema:
          document:
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
          customer: ${ . }
        schema:
          document:
            type: object
            required: ["customer"]
            properties:
              customer:
                type: object
                required: ["id", "name", "email", "status"]
  - CheckCustomerStatus:
      switch:
        - case1:
            then: RejectInactiveCustomer
            when: ${ $context.customer.status != "ACTIVE" }
        - default:
            then: CheckInventory
  - CheckInventory:
      call: inventoryService
      with:
        items: ${ .productRequests }
      then: CheckInventoryResult
      input:
        schema:
          document:
            type: object
            required: ["productRequests"]
            properties:
              productRequests:
                type: array
                minItems: 1
                items:
                  type: object
                  required: ["productId", "quantity"]
        from:
          productRequests: '${ .items | map({ productId: .productId, quantity: .quantity }) }'
      output:
        schema:
          document:
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
  - CheckInventoryResult:
      switch:
        - case1:
            then: HandleUnavailableItems
            when: ${ .available == false }
        - default:
            then: CalculateOrderTotal
  - CalculateOrderTotal:
      set:
        items: ${ .orderItems }
        subtotal: ${ .orderItems | map(.unitPrice * .quantity) | add }
        discountRate: '${ if $context.customer.membershipTier == "GOLD" then 0.05 elif $context.customer.membershipTier ==
          "PLATINUM" then 0.1 else 0 end }'
        discount: ${ .subtotal * .discountRate }
        taxRate: 0.08
        tax: ${ "(\(.subtotal) - \(.discount)) * \(.taxRate)" }
        total: ${ .subtotal - .discount + .tax }
        orderSummary:
          orderId: ${ .orderId }
          customerId: ${ .customerId }
          customerName: ${ $context.customer.name }
          items: ${ .items }
          pricing:
            subtotal: ${ .subtotal }
            discount: ${ .discount }
            tax: ${ .tax }
            total: ${ .total }
          currency: ${ .currency }
      then: ProcessPayment
      input:
        schema:
          document:
            type: object
            required: ["orderItems"]
            properties:
              orderItems:
                type: array
                minItems: 1
                items:
                  type: object
                  required: ["productId", "name", "unitPrice", "quantity"]
        from:
          products: ${ .items }
          orderItems: '${ .items | map({ productId: .productId, name: .name, unitPrice: .price, quantity: (.productId as $pid
            | $workflow.input.items[] | select(.productId == $pid) | .quantity) }) }'
      output:
        schema:
          document:
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
          orderDetails: ${ .orderSummary }
          orderTotal: ${ .total }
        schema:
          document:
            type: object
            required: ["orderDetails", "orderTotal"]
  - ProcessPayment:
      call: paymentService
      with:
        payment: ${ .paymentRequest }
      then: CheckPaymentResult
      input:
        schema:
          document:
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
        from:
          paymentRequest:
            orderId: ${ .orderId }
            customerId: ${ .customerId }
            amount: ${ .total }
            currency: ${ .currency }
            method: ${ .paymentMethod.type }
            token: ${ .paymentMethod.cardToken }
            email: ${ .paymentMethod.paypalEmail }
            accountInfo: ${ .paymentMethod.accountInfo }
      output:
        schema:
          document:
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
  - CheckPaymentResult:
      switch:
        - case1:
            then: CreateShipment
            when: ${ .success == true }
        - default:
            then: HandlePaymentFailure
  - CreateShipment:
      call: shippingService
      with:
        orderId: ${ .orderId }
        items: ${ .items }
        shippingAddress: ${ .shippingAddress }
        customerEmail: ${ $context.customer.email }
      then: CompleteOrder
      input:
        schema:
          document:
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
      output:
        schema:
          document:
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
          shipment: ${ . }
  - CompleteOrder:
      set:
        orderConfirmation:
          orderId: ${ .orderId }
          customer:
            id: ${ .customerId }
            name: ${ $context.customer.name }
            email: ${ $context.customer.email }
          items: ${ .items }
          summary:
            subtotal: ${ $context.orderDetails.pricing.subtotal }
            discount: ${ $context.orderDetails.pricing.discount }
            tax: ${ $context.orderDetails.pricing.tax }
            total: ${ $context.orderDetails.pricing.total }
            currency: ${ .currency }
          payment:
            transactionId: ${ .transactionId }
            method: ${ .paymentMethod.type }
          shipping:
            shipmentId: ${ $context.shipment.shipmentId }
            trackingNumber: ${ $context.shipment.trackingNumber }
            estimatedDelivery: ${ $context.shipment.estimatedDelivery }
            carrier: ${ $context.shipment.carrier }
            address: ${ .shippingAddress }
          status: "CONFIRMED"
          createdAt: ${ $workflow.startedAt }
          completedAt: ${ $context.currentTime }
      then: SendConfirmation
      output:
        schema:
          document:
            type: object
            required: ["orderConfirmation"]
            properties:
              orderConfirmation:
                type: object
                required: ["orderId", "customer", "items", "summary", "payment", "shipping", "status"]
  - SendConfirmation:
      call: notificationService
      with:
        to: ${ $context.customer.email }
        subject: '${ "Order Confirmed: \(.orderId)" }'
        template: "order_confirmation"
        data: ${ .orderConfirmation }
      then: end
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
do:
  - ValidateCustomer:
      set:
        customer:
          id: ${ .customerId }
          name: ${ .fullName }
          email: ${ .emailAddress }
          address: ${ .shippingAddress }
      then: NextTask
      output:
        schema:
          document:
            type: object
            required:
              - "id"
              - "name"
              - "email"
            properties:
              id:
                type: string
              name:
                type: string
              email:
                type: string
                format: email
              address:
                type: object
                required:
                  - "street"
                  - "city"
                  - "zipCode"
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