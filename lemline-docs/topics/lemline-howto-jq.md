---
title: How to write jq runtime expressions
---

# How to write jq runtime expressions

This guide explains how to use jq expressions in Lemline workflows. You'll learn the jq syntax, how to transform data, access context variables, and implement complex logic.

## Understanding jq in Lemline

jq is a lightweight, flexible query language for JSON that Lemline uses for runtime expressions. In Lemline workflows, jq expressions are used to:

- Transform input and output data
- Access workflow context and task data
- Implement conditional logic
- Filter and manipulate arrays and objects
- Format and combine data

## Basic jq Syntax

### Identity Expression

The simplest jq expression is the identity expression, represented by a single dot `.`:

```yaml
  - IdentityExpression:
      set:
        result: ${ . }
      then: NextTask
```

This passes through the entire input without modification.

### Property Access

To access a property within JSON data, use dot notation:

```yaml
  - PropertyAccess:
      set:
        name: ${ .customer.name }
        id: ${ .orderId }
      then: NextTask
```

### Array Access

Access array elements using square brackets:

```yaml
  - ArrayAccess:
      set:
        firstItem: ${ .items[0] }
        lastItem: ${ .items[-1] }
        secondAndThird: ${ .items[1:3] }
        firstThree: ${ .items[0:3] }
        allExceptFirst: ${ .items[1:] }
        lastThree: ${ .items[-3:] }
      then: NextTask
```

### Object Construction

Create new objects using curly braces:

```yaml
  - ObjectConstruction:
      set:
        customer:
          "id": ${ .userId }
          "fullName": ${ .firstName + ' ' + .lastName }
          "isActive": ${ .status == 'ACTIVE' }
          "joinDate": ${ .metadata.joinDate }
      then: NextTask
```

### Array Construction

Create arrays using square brackets:

```yaml
  - ArrayConstruction:
      set:
        productIds: ${ [.items[].productId] }
        prices: ${ [.items[].price] }
        errors: ${ [.error1, .error2] }
      then: NextTask
```

## Data Transformation Techniques

### String Operations

```yaml
  - StringOperations:
      set:
        fullName: ${ .firstName + ' ' + .lastName }
        greeting: "\"Hello, \" + .name + \"!\""
        uppercase: ${ .name | ascii_upcase }
        lowercase: ${ .name | ascii_downcase }
        trimmed: ${ .description | strip }
        substring: ${ .text[0:10] + "..." }
        replaced: ${ .code | gsub("[^A-Za-z0-9]"; "-") }
      then: NextTask
```

### Numeric Operations

```yaml
  - NumericOperations:
      set:
        sum: ${ .a + .b }
        difference: ${ .a - .b }
        product: ${ .a * .b }
        quotient: ${ .a / .b }
        rounded: ${ .value | round }
        ceiling: ${ .value | ceil }
        floor: ${ .value | floor }
        absolute: ${ .value | abs }
        formatted: ${ .value | tostring }
      then: NextTask
```

### Array Operations

```yaml
  - ArrayOperations:
      set:
        count: ${ .items | length }
        sum: ${ .items | map(.value) | add }
        average: ${ .items | map(.value) | add / length }
        sorted: ${ .items | sort_by(.price) }
        reversed: ${ .items | reverse }
        minimum: ${ .items | min_by(.price) }
        maximum: ${ .items | max_by(.price) }
        flattened: ${ .nestedArrays | flatten }
        unique: ${ .tags | unique }
        filtered: ${ .items | map(select(.price > 100)) }
        mapped: '${ .items | map({id: .id, name: .name}) }'
        joined: ${ .tags | join(", ") }
      then: NextTask
```

### Object Operations

```yaml
  - ObjectOperations:
      set:
        merged: ${ .defaults + .overrides }
        keys: ${ .customer | keys }
        hasKey: ${ .customer | has("email") }
        pickFields: ${ .user | {name, email, id} }
        transform: ${ .transaction | with_entries(.key |= ascii_upcase) }
        deleteKey: ${ .config | del(.debug) }
      then: NextTask
```

## Conditional Logic

### If Expressions

```yaml
  - ConditionalLogic:
      set:
        status: ${ if .score > 90 then "Excellent" else if .score > 70 then "Good" else "Average" end end }
        message: ${ if .isActive then "Account is active" else "Account is inactive" end }
        shipping: ${ if .express then "Express" else "Standard" end }
      then: NextTask
```

### Ternary Operator

```yaml
  - TernaryOperator:
      set:
        status: '${ if .score > 90 then "Excellent" else (.score > 70 ? "Good" : "Average") end }'
        message: ${ if .isActive then "Account is active" else "Account is inactive" end }
        shipping: ${ if .express then "Express" else "Standard" end }
      then: NextTask
```

### Boolean Operations

```yaml
  - BooleanOperations:
      set:
        and: ${ .condition1 and .condition2 }
        or: ${ .condition1 or .condition2 }
        not: "not .condition"
        complex: "(.a > 5 and .a < 10) or .b == 0"
      then: NextTask
```

### Handling Nulls and Defaults

```yaml
  - NullHandling:
      set:
        nameOrDefault: ${ .name // "Anonymous" }
        countOrZero: ${ .count // 0 }
        valueOrFallback: ${ if .value then .value else .fallback end }
        fullAddress: ${ .address.city + ", " + (.address.state // "N/A") }
      then: NextTask
```

## Accessing Workflow Context

### Workflow Variables

```yaml
  - AccessWorkflowContext:
      set:
        workflowId: ${ $context.id }
        startTime: ${ $workflow.startedAt }
        input: ${ $workflow.input }
        customInput: ${ $workflow.input.customerId }
        currentTime: ${ $context.currentTime }
      then: NextTask
```

### Task Results

```yaml
  - AccessTaskResults:
      set:
        previousTaskResult: ${ . }
        specificTaskResult: ${ $context.ValidateCustomer }
        specificProperty: ${ $context.ProcessPayment.transactionId }
      then: NextTask
```

### Exported Data

```yaml
  - AccessExportedData:
      set:
        exportedCustomer: ${ $context.customer }
        combinedData:
          "orderId": ${ .orderId }
          "customer": ${ $context.customer }
          "paymentInfo": ${ $context.paymentInfo }
      then: NextTask
```

## Advanced jq Techniques

### Using Pipes

Pipes allow you to chain operations:

```yaml
  - PipedOperations:
      set:
        processedText: ${ .text | strip | ascii_downcase | gsub("\s+"; "-") }
        titleCased: ${ .name | split(" ") | map(.[0:1] | ascii_upcase + .[1:]) | join(" ") }
        transformedItems: ${ .items | map(.price) | map(. * 0.9) | map(round) }
      then: NextTask
```

### Working with Dates

```yaml
  - DateOperations:
      set:
        daysSinceOrder: "date.difference(.orderDate, $context.currentTime, 'days')"
        isFutureDate: "date.compare(.deliveryDate, $context.currentTime) > 0"
        isDateValid: "try date.parse(.dateString, \"yyyy-MM-dd\") catch false"
        formattedDate: "date.format($context.currentTime, \"MMM d, yyyy\")"
      then: NextTask
```

### Function-like Operations

jq offers many built-in functions:

```yaml
  - BuiltInFunctions:
      set:
        type: "type"
        length: ${ length }
        entries: "to_entries"
        fromEntries: ${ .keyValuePairs | from_entries }
        unique: ${ .tags | unique }
        contains: ${ .text | contains("keyword") }
        startsWith: ${ .code | startswith("ABC") }
        endsWith: ${ .code | endswith("XYZ") }
      then: NextTask
```

### Regular Expressions

```yaml
  - RegexOperations:
      set:
        matches: ${ .text | test("^[A-Z]{3}\d{4}$") }
        extracted: ${ .text | match("(\d+)").captures[0].string }
        replaced: ${ .text | sub("(\d+)"; "NUM") }
        allReplaced: ${ .text | gsub("(\d+)"; "NUM") }
        splitByPattern: ${ .text | split("[,;]") }
      then: NextTask
```

### Error Handling in Expressions

```yaml
  - ErrorHandlingInExpressions:
      set:
        safeValue: "try .deep.nested.property catch null"
        safeOperation: "try (.value / .divisor) catch \"Division error\""
        safePathAccess: "try .items[.index] catch \"Invalid index\""
      then: NextTask
```

## Real-World Examples

### Data Transformation for API Request

```yaml
  - PrepareApiRequest:
      set:
        requestBody:
          "customerId": ${ .user.id }
          "items": '${ .cart.items | map({ productId: .id, quantity: .quantity, unitPrice: .price }) }'
          "shippingAddress":
            "name": ${ .user.fullName }
            "street": ${ .user.address.street }
            "city": ${ .user.address.city }
            "state": ${ .user.address.state }
            "zipCode": ${ .user.address.zipCode }
            "country": ${ .user.address.country // "US" }
          "subtotal": ${ .cart.items | map(.price * .quantity) | add }
          "discount": ${ .cart.discount // 0 }
          "shippingCost": ${ if .user.membershipLevel == "PREMIUM" then 0 else 5.99 end }
          "orderDate": ${ $context.currentTime }
      then: CallOrderAPI
```

### Processing a Report

```yaml
  - GenerateReport:
      set:
        report:
          "generatedAt": ${ $context.currentTime }
          "period":
            "start": ${ .reportPeriod.start }
            "end": ${ .reportPeriod.end }
            "durationDays": "date.difference(.reportPeriod.start, .reportPeriod.end, 'days')"
          "summary":
            "totalOrders": ${ .orders | length }
            "totalRevenue": ${ .orders | map(.total) | add }
            "averageOrderValue": ${ .orders | map(.total) | add / length }
            "topProduct": ${ .orders | map(.items[]) | group_by(.productId) | max_by(length)[0].productId }
          "categorySummary": '${ .orders | map(.items[]) | group_by(.category) | map({ category: .[0].category, itemCount:
            length, revenue: map(.price * .quantity) | add }) | sort_by(-.revenue) }'
      then: FormatReport
```

### Notification Content Construction

```yaml
  - PrepareNotification:
      set:
        notification:
          "recipient": ${ .user.email }
          "subject": '${ if .notificationType == "ORDER_CONFIRMATION" then "Your order #" + .order.id + " has been confirmed"
            else if .notificationType == "SHIPPING_UPDATE" then "Your order #" + .order.id + " has shipped" else "Update on
            your order #" + .order.id end end }'
          "message": "\"Dear \" + (.user.firstName // \"Customer\") + \",\\n\\n\" + if .notificationType == \"ORDER_CONFIRMATION\"\
            \ then \"Thank you for your order. We've received your order #\" + .order.id + \" and are processing it now.\"\
            \ else if .notificationType == \"SHIPPING_UPDATE\" then \"Great news! Your order #\" + .order.id + \" has shipped
            and should arrive by \" + .shipment.estimatedDelivery + \".\" else \"We have an update about your recent order
            #\" + .order.id + \".\" end end"
          "details": '${ if .notificationType == "ORDER_CONFIRMATION" then { orderNumber: .order.id, orderDate: .order.date,
            items: .order.items | map({name: .name, quantity: .quantity, price: .price}), total: .order.total } else if .notificationType
            == "SHIPPING_UPDATE" then { trackingNumber: .shipment.trackingNumber, carrier: .shipment.carrier, estimatedDelivery:
            .shipment.estimatedDelivery } else { orderNumber: .order.id, status: .order.status } end end }'
      then: SendNotification
```

## jq Expression Style Guide

To ensure consistent and maintainable expressions in your workflows, follow these style guidelines:

1. **Readability First**: Format complex expressions across multiple lines when needed
2. **Use Pipes**: Chain operations with pipes for clarity
3. **Parentheses**: Use parentheses to clarify precedence
4. **Indentation**: Indent nested structures consistently
5. **Comments**: Add comments for complex logic
6. **Null Handling**: Always include null checks for optional fields
7. **Descriptive Variables**: Use descriptive names for intermediate values
8. **Prefer Simplicity**: Break complex expressions into multiple tasks when possible

### Poor Style:

```yaml
result: ".items|map(select(.price>100))|map(.price)|add*.customers|length|if.>1000 then\"High\"else\"Low\"end"
```

### Good Style:

```yaml
# Calculate total price of expensive items per customer
result: "
  # Filter items over $100
  .items 
  | map(select(.price > 100)) 
  # Sum prices of expensive items
  | map(.price) 
  | add 
  # Calculate per-customer average
  * .customers 
  | length 
  # Categorize the result
  | if . > 1000 then 
      \"High\" 
    else 
      \"Low\" 
    end
"
```

## Common jq Mistakes and How to Avoid Them

1. **Missing Quotes Around Strings**:
   ```
   # Wrong: Adds two values
   message: "Hello + .name"  
   # Right: Concatenates strings
   message: "\"Hello \" + .name"
   ```

2. **Incorrect Property Access**:
   ```
   # Wrong: Tries to access a property with a dot in its name
   value: ".user.first.name"
   # Right: If the property is actually "first.name"
   value: ".user[\"first.name\"]"
   ```

3. **Not Handling Nulls**:
   ```
   # Wrong: Will cause error if address is null
   city: ".address.city"
   # Right: Provides a default
   city: ".address.city // \"Unknown\""
   ```

4. **Forgetting Array Indexing**:
   ```
   # Wrong: Accesses the array itself, not its first element
   firstItem: ".items"
   # Right: Accesses the first element
   firstItem: ".items[0]"
   ```

5. **Incorrect Filters**:
   ```
   # Wrong: Returns boolean for each item
   filtered: ".items | .price > 100"
   # Right: Filters the array
   filtered: ".items | map(select(.price > 100))"
   ```

## Debugging jq Expressions

When debugging jq expressions in Lemline:

1. **Break Down Complex Expressions**: Split complex expressions into simpler parts
2. **Use Intermediate Variables**: Store intermediate results in variables
3. **Try/Catch for Debugging**: Wrap expressions in `try/catch` to identify errors
4. **Use Type Checking**: Add `type` to check data types
5. **Output Debugging Information**: Create a dedicated debugging task:

```yaml
  - DebugExpression:
      set:
        original: ${ . }
        type: "type"
        hasProperty: "has(\"keyName\")"
        debug:
          "input": ${ . }
          "transformStep1": ${ . | map(.value) }
          "transformStep2": ${ . | map(.value) | add }
      then: ActualTask
```

## Next Steps

- Learn about [input, output, and export](lemline-howto-io.md)
- Explore [validating inputs and outputs with schemas](lemline-howto-schemas.md)
- Understand [how to pass data between tasks](lemline-howto-data-passing.md)
- Dive deeper into [runtime expressions and jq concepts](lemline-explain-jq.md)