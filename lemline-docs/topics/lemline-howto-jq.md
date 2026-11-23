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
- name: IdentityExpression
  type: set
  data:
    result: "."  # Returns the entire input
  next: NextTask
```

This passes through the entire input without modification.

### Property Access

To access a property within JSON data, use dot notation:

```yaml
- name: PropertyAccess
  type: set
  data:
    name: ".customer.name"  # Access the name property inside the customer object
    id: ".orderId"          # Access the orderId at the root level
  next: NextTask
```

### Array Access

Access array elements using square brackets:

```yaml
- name: ArrayAccess
  type: set
  data:
    firstItem: ".items[0]"              # First element
    lastItem: ".items[-1]"              # Last element
    secondAndThird: ".items[1:3]"       # Elements 1 and 2 (not including 3)
    firstThree: ".items[0:3]"           # First three elements
    allExceptFirst: ".items[1:]"        # All elements except the first
    lastThree: ".items[-3:]"            # Last three elements
  next: NextTask
```

### Object Construction

Create new objects using curly braces:

```yaml
- name: ObjectConstruction
  type: set
  data:
    customer: {
      "id": ".userId",
      "fullName": ".firstName + ' ' + .lastName",
      "isActive": ".status == 'ACTIVE'",
      "joinDate": ".metadata.joinDate"
    }
  next: NextTask
```

### Array Construction

Create arrays using square brackets:

```yaml
- name: ArrayConstruction
  type: set
  data:
    productIds: "[.items[].productId]"  # Extract all product IDs into an array
    prices: "[.items[].price]"          # Extract all prices into an array
    errors: "[.error1, .error2]"        # Create array from multiple values
  next: NextTask
```

## Data Transformation Techniques

### String Operations

```yaml
- name: StringOperations
  type: set
  data:
    fullName: ".firstName + ' ' + .lastName"  # Concatenation
    greeting: "\"Hello, \" + .name + \"!\""   # String with quotes
    uppercase: ".name | ascii_upcase"         # Uppercase conversion
    lowercase: ".name | ascii_downcase"       # Lowercase conversion
    trimmed: ".description | strip"           # Remove leading/trailing whitespace
    substring: ".text[0:10] + \"...\""        # Substring with ellipsis
    replaced: ".code | gsub(\"[^A-Za-z0-9]\"; \"-\")"  # Replace non-alphanumeric with dash
  next: NextTask
```

### Numeric Operations

```yaml
- name: NumericOperations
  type: set
  data:
    sum: ".a + .b"                     # Addition
    difference: ".a - .b"              # Subtraction
    product: ".a * .b"                 # Multiplication
    quotient: ".a / .b"                # Division
    rounded: ".value | round"          # Round to nearest integer
    ceiling: ".value | ceil"           # Round up
    floor: ".value | floor"            # Round down
    absolute: ".value | abs"           # Absolute value
    formatted: ".value | tostring"     # Convert number to string
  next: NextTask
```

### Array Operations

```yaml
- name: ArrayOperations
  type: set
  data:
    count: ".items | length"                              # Count items
    sum: ".items | map(.value) | add"                     # Sum values
    average: ".items | map(.value) | add / length"        # Calculate average
    sorted: ".items | sort_by(.price)"                    # Sort by property
    reversed: ".items | reverse"                          # Reverse array
    minimum: ".items | min_by(.price)"                    # Item with minimum price
    maximum: ".items | max_by(.price)"                    # Item with maximum price
    flattened: ".nestedArrays | flatten"                  # Flatten nested arrays
    unique: ".tags | unique"                              # Get unique values
    filtered: ".items | map(select(.price > 100))"        # Filter by condition
    mapped: ".items | map({id: .id, name: .name})"        # Transform array items
    joined: ".tags | join(\", \")"                        # Join array elements
  next: NextTask
```

### Object Operations

```yaml
- name: ObjectOperations
  type: set
  data:
    merged: ".defaults + .overrides"                      # Merge objects (overrides wins)
    keys: ".customer | keys"                              # Get object keys
    hasKey: ".customer | has(\"email\")"                  # Check if key exists
    pickFields: ".user | {name, email, id}"               # Pick specific fields
    transform: ".transaction | with_entries(.key |= ascii_upcase)"  # Transform keys
    deleteKey: ".config | del(.debug)"                    # Delete a key
  next: NextTask
```

## Conditional Logic

### If Expressions

```yaml
- name: ConditionalLogic
  type: set
  data:
    status: "if .score > 90 then \"Excellent\" else if .score > 70 then \"Good\" else \"Average\" end end"
    message: "if .isActive then \"Account is active\" else \"Account is inactive\" end"
    shipping: "if .express then \"Express\" else \"Standard\" end"
  next: NextTask
```

### Ternary Operator

```yaml
- name: TernaryOperator
  type: set
  data:
    status: ".score > 90 ? \"Excellent\" : (.score > 70 ? \"Good\" : \"Average\")"
    message: ".isActive ? \"Account is active\" : \"Account is inactive\""
    shipping: ".express ? \"Express\" : \"Standard\""
  next: NextTask
```

### Boolean Operations

```yaml
- name: BooleanOperations
  type: set
  data:
    and: ".condition1 and .condition2"         # Logical AND
    or: ".condition1 or .condition2"           # Logical OR
    not: "not .condition"                      # Logical NOT
    complex: "(.a > 5 and .a < 10) or .b == 0" # Complex condition
  next: NextTask
```

### Handling Nulls and Defaults

```yaml
- name: NullHandling
  type: set
  data:
    nameOrDefault: ".name // \"Anonymous\""        # Use default if null or missing
    countOrZero: ".count // 0"                     # Default to zero
    valueOrFallback: ".value ? .value : .fallback" # Use fallback if null, false, or empty
    fullAddress: ".address.city + \", \" + (.address.state // \"N/A\")"  # Default within expression
  next: NextTask
```

## Accessing Workflow Context

### Workflow Variables

```yaml
- name: AccessWorkflowContext
  type: set
  data:
    workflowId: "$WORKFLOW.id"                  # Workflow instance ID
    startTime: "$WORKFLOW.startTime"            # Workflow start time
    input: "$WORKFLOW.input"                    # Workflow input data
    customInput: "$WORKFLOW.input.customerId"   # Specific input field
    currentTime: "$WORKFLOW.currentTime"        # Current time
  next: NextTask
```

### Task Results

```yaml
- name: AccessTaskResults
  type: set
  data:
    previousTaskResult: "."                              # Current task input (previous task result)
    specificTaskResult: "$WORKFLOW.ValidateCustomer"     # Result from a specific task
    specificProperty: "$WORKFLOW.ProcessPayment.transactionId"  # Property from a task result
  next: NextTask
```

### Exported Data

```yaml
- name: AccessExportedData
  type: set
  data:
    exportedCustomer: "$WORKFLOW.customer"       # Access exported customer data
    combinedData: {
      "orderId": ".orderId",
      "customer": "$WORKFLOW.customer",
      "paymentInfo": "$WORKFLOW.paymentInfo"
    }
  next: NextTask
```

## Advanced jq Techniques

### Using Pipes

Pipes allow you to chain operations:

```yaml
- name: PipedOperations
  type: set
  data:
    processedText: ".text | strip | ascii_downcase | gsub(\"\\s+\"; \"-\")"
    titleCased: ".name | split(\" \") | map(.[0:1] | ascii_upcase + .[1:]) | join(\" \")"
    transformedItems: ".items | map(.price) | map(. * 0.9) | map(round)"
  next: NextTask
```

### Working with Dates

```yaml
- name: DateOperations
  type: set
  data:
    # Calculate days between dates using the date helper functions
    daysSinceOrder: "date.difference(.orderDate, $WORKFLOW.currentTime, 'days')"
    isFutureDate: "date.compare(.deliveryDate, $WORKFLOW.currentTime) > 0"
    isDateValid: "try date.parse(.dateString, \"yyyy-MM-dd\") catch false"
    formattedDate: "date.format($WORKFLOW.currentTime, \"MMM d, yyyy\")"
  next: NextTask
```

### Function-like Operations

jq offers many built-in functions:

```yaml
- name: BuiltInFunctions
  type: set
  data:
    type: "type"                                # Get data type (string, number, object, etc.)
    length: "length"                            # Length of string, array, or object
    entries: "to_entries"                       # Convert object to array of key-value pairs
    fromEntries: ".keyValuePairs | from_entries"  # Convert array of key-value pairs to object
    unique: ".tags | unique"                    # Remove duplicates from array
    contains: ".text | contains(\"keyword\")"   # Check if string contains substring
    startsWith: ".code | startswith(\"ABC\")"   # Check if string starts with prefix
    endsWith: ".code | endswith(\"XYZ\")"       # Check if string ends with suffix
  next: NextTask
```

### Regular Expressions

```yaml
- name: RegexOperations
  type: set
  data:
    matches: ".text | test(\"^[A-Z]{3}\\d{4}$\")"    # Test if text matches pattern
    extracted: ".text | match(\"(\\d+)\").captures[0].string"  # Extract matched pattern
    replaced: ".text | sub(\"(\\d+)\"; \"NUM\")"     # Replace first match
    allReplaced: ".text | gsub(\"(\\d+)\"; \"NUM\")" # Replace all matches
    splitByPattern: ".text | split(\"[,;]\")"        # Split by regex pattern
  next: NextTask
```

### Error Handling in Expressions

```yaml
- name: ErrorHandlingInExpressions
  type: set
  data:
    # Use try/catch to handle potential errors
    safeValue: "try .deep.nested.property catch null"
    safeOperation: "try (.value / .divisor) catch \"Division error\""
    safePathAccess: "try .items[.index] catch \"Invalid index\""
  next: NextTask
```

## Real-World Examples

### Data Transformation for API Request

```yaml
- name: PrepareApiRequest
  type: set
  data:
    requestBody: {
      "customerId": ".user.id",
      "items": ".cart.items | map({
        productId: .id,
        quantity: .quantity,
        unitPrice: .price
      })",
      "shippingAddress": {
        "name": ".user.fullName",
        "street": ".user.address.street",
        "city": ".user.address.city",
        "state": ".user.address.state",
        "zipCode": ".user.address.zipCode",
        "country": ".user.address.country || \"US\""
      },
      "subtotal": ".cart.items | map(.price * .quantity) | add",
      "discount": ".cart.discount // 0",
      "shippingCost": "if .user.membershipLevel == \"PREMIUM\" then 0 else 5.99 end",
      "orderDate": "$WORKFLOW.currentTime"
    }
  next: CallOrderAPI
```

### Processing a Report

```yaml
- name: GenerateReport
  type: set
  data:
    report: {
      "generatedAt": "$WORKFLOW.currentTime",
      "period": {
        "start": ".reportPeriod.start",
        "end": ".reportPeriod.end",
        "durationDays": "date.difference(.reportPeriod.start, .reportPeriod.end, 'days')"
      },
      "summary": {
        "totalOrders": ".orders | length",
        "totalRevenue": ".orders | map(.total) | add",
        "averageOrderValue": ".orders | map(.total) | add / length",
        "topProduct": ".orders | map(.items[]) | group_by(.productId) | max_by(length)[0].productId"
      },
      "categorySummary": ".orders | map(.items[]) | group_by(.category) | map({
        category: .[0].category,
        itemCount: length,
        revenue: map(.price * .quantity) | add
      }) | sort_by(-.revenue)"
    }
  next: FormatReport
```

### Notification Content Construction

```yaml
- name: PrepareNotification
  type: set
  data:
    notification: {
      "recipient": ".user.email",
      "subject": "if .notificationType == \"ORDER_CONFIRMATION\" then 
                   \"Your order #\" + .order.id + \" has been confirmed\" 
                 else if .notificationType == \"SHIPPING_UPDATE\" then 
                   \"Your order #\" + .order.id + \" has shipped\" 
                 else 
                   \"Update on your order #\" + .order.id 
                 end end",
      "message": "\"Dear \" + (.user.firstName // \"Customer\") + \",\\n\\n\" +
                 if .notificationType == \"ORDER_CONFIRMATION\" then
                   \"Thank you for your order. We've received your order #\" + .order.id + \" and are processing it now.\"
                 else if .notificationType == \"SHIPPING_UPDATE\" then
                   \"Great news! Your order #\" + .order.id + \" has shipped and should arrive by \" + .shipment.estimatedDelivery + \".\"
                 else
                   \"We have an update about your recent order #\" + .order.id + \".\"
                 end end",
      "details": "if .notificationType == \"ORDER_CONFIRMATION\" then {
                    orderNumber: .order.id,
                    orderDate: .order.date,
                    items: .order.items | map({name: .name, quantity: .quantity, price: .price}),
                    total: .order.total
                  } else if .notificationType == \"SHIPPING_UPDATE\" then {
                    trackingNumber: .shipment.trackingNumber,
                    carrier: .shipment.carrier,
                    estimatedDelivery: .shipment.estimatedDelivery
                  } else {
                    orderNumber: .order.id,
                    status: .order.status
                  } end end"
    }
  next: SendNotification
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
- name: DebugExpression
  type: set
  data:
    original: "."  # Original input
    type: "type"   # Data type
    hasProperty: "has(\"keyName\")"  # Check if property exists
    debug: {
      "input": ".",
      "transformStep1": ". | map(.value)",
      "transformStep2": ". | map(.value) | add"
    }
  next: ActualTask
```

## Next Steps

- Learn about [input, output, and export](lemline-howto-io.md)
- Explore [validating inputs and outputs with schemas](lemline-howto-schemas.md)
- Understand [how to pass data between tasks](lemline-howto-data-passing.md)
- Dive deeper into [runtime expressions and jq concepts](lemline-explain-jq.md)