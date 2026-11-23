# Understanding JQ Expressions in Lemline

This document explains how JQ expressions work in Lemline, their capabilities, and best practices for using them effectively.

## What is JQ?

JQ is a lightweight, flexible command-line JSON processor that enables powerful data manipulation, transformation, and querying. In Lemline, JQ expressions are used within workflows to:

1. **Extract Data**: Access specific fields or values
2. **Transform Data**: Convert between formats and structures
3. **Filter Data**: Select elements meeting specific criteria
4. **Calculate Values**: Perform arithmetic, string operations, and more
5. **Combine Data**: Merge data from multiple sources

## JQ Expression Syntax in Lemline

In Lemline workflows, JQ expressions are enclosed in `${ }` delimiters:

```yaml
set:
  orderTotal: "${ .items | map(.price * .quantity) | add }"
  customerName: "${ .customer.firstName + ' ' + .customer.lastName }"
  discountedItems: "${ .items | map(select(.discount > 0)) }"
```

### The Input Context

JQ expressions operate on the current data context, which includes:

- **Workflow input data**: Available from the beginning
- **Task output data**: Added as tasks execute
- **Variables set during execution**: Created with `set` tasks

The dot (`.`) represents the current context:

```yaml
# Access the entire current context
fullContext: "${ . }"

# Access a specific field
customerName: "${ .customer.name }"
```

## Basic JQ Operations

### Field Access

Access object properties with dot notation:

```yaml
# Simple property access
name: "${ .customer.name }"

# Nested property access
city: "${ .customer.address.city }"

# Array element access
firstItem: "${ .order.items[0] }"
```

### Array Operations

Work with arrays:

```yaml
# Array length
itemCount: "${ .order.items | length }"

# Map transformation (apply function to each element)
prices: "${ .order.items | map(.price) }"

# Filter arrays
expensiveItems: "${ .order.items | map(select(.price > 100)) }"

# Array aggregation
totalAmount: "${ .order.items | map(.price * .quantity) | add }"
```

### Object Operations

Manipulate objects:

```yaml
# Create new object
customerSummary: "${ { id: .customer.id, name: .customer.name } }"

# Merge objects
mergedData: "${ .defaultValues * .overrides }"

# Get object keys
propertyNames: "${ .customer | keys }"
```

### String Operations

Work with strings:

```yaml
# String concatenation
fullName: "${ .firstName + ' ' + .lastName }"

# String interpolation
greeting: "${ \"Hello, \\(.name)!\" }"

# String operations
upperName: "${ .name | ascii_upcase }"
nameLength: "${ .name | length }"
```

### Boolean Operations

Work with boolean logic:

```yaml
# Logical operations
isEligible: "${ .age >= 18 && .status == \"active\" }"
hasDiscount: "${ .isVip || .isFirstOrder }"
isNotExpired: "${ !.isExpired }"
```

### Number Operations

Perform calculations:

```yaml
# Arithmetic
totalWithTax: "${ .subtotal * (1 + .taxRate) }"
discountAmount: "${ .price * .discountRate }"

# Rounding
roundedAmount: "${ .amount | round }"
floorAmount: "${ .amount | floor }"
ceilAmount: "${ .amount | ceil }"
```

## Advanced JQ Features

### Filters and Piping

Chain operations with pipes:

```yaml
# Multiple transformations
processedData: "${ .rawData | fromjson | map(.value) | add }"

# Complex filtering
eligibleCustomers: "${ .customers | map(select(.age >= 18 and .status == \"active\")) }"
```

### Conditionals

Use conditional expressions:

```yaml
# Ternary operator
status: "${ .active ? \"Active\" : \"Inactive\" }"

# if-then-else
discount: "${ if .isVip then 0.2 elif .isFirstOrder then 0.1 else 0 end }"
```

### Iteration

Iterate through data:

```yaml
# Iteration using map
transformedItems: "${ .items | map({ id: .id, name: .name, total: (.price * .quantity) }) }"

# Explicit iteration
processedData: "${ .data | map(. * 2) }"
```

### Reduction

Reduce arrays to single values:

```yaml
# Sum array values
total: "${ .values | add }"

# Reduce with custom function
stats: "${ reduce .values[] as $v ({count:0, sum:0, min:null, max:null}; 
            .count += 1 | 
            .sum += $v | 
            .min = if .min == null or $v < .min then $v else .min end |
            .max = if .max == null or $v > .max then $v else .max end) }"
```

### Object Construction

Create complex objects:

```yaml
# Build new object
orderSummary: "${ {
  id: .order.id,
  customer: {
    id: .order.customer.id,
    name: .order.customer.name
  },
  items: .order.items | map({
    productId: .id,
    name: .name,
    quantity: .quantity,
    price: .price,
    total: (.price * .quantity)
  }),
  totals: {
    subtotal: .order.items | map(.price * .quantity) | add,
    tax: (.order.items | map(.price * .quantity) | add) * .taxRate,
    total: (.order.items | map(.price * .quantity) | add) * (1 + .taxRate)
  }
} }"
```

### Path Expressions

Use path expressions to navigate:

```yaml
# Get paths to all values with specific property
errorPaths: "${ paths(.error == true) }"

# Find paths to specific values
valuePaths: "${ paths(. == \"special_value\") }"
```

## Lemline-Specific Extensions

Lemline extends JQ with additional functionality:

### Time Functions

Work with dates and times:

```yaml
# Current time
now: "${ now() }"

# Format time
formattedDate: "${ now() | format(\"yyyy-MM-dd\") }"

# Time arithmetic
futureTime: "${ now() | plus(\"PT1H\") }"  # 1 hour from now
pastTime: "${ now() | minus(\"P1D\") }"   # 1 day ago

# Time comparisons
isAfter: "${ now() > .deadline }"
```

### UUID Generation

Generate unique identifiers:

```yaml
# Generate UUID
id: "${ uuid() }"

# Generate UUID with specific version
idV4: "${ uuid4() }"
```

### HTTP Utilities

Utilities for HTTP operations:

```yaml
# URL encoding
encodedParam: "${ urlEncode(.value) }"

# Base64 encoding/decoding
base64Value: "${ base64Encode(.data) }"
decodedValue: "${ base64Decode(.encodedData) }"
```

### Cryptographic Functions

Secure hash generation:

```yaml
# Generate hash
sha256Hash: "${ sha256(.data) }"
hmacValue: "${ hmacSha256(.data, .key) }"
```

### Environment Access

Access environment information:

```yaml
# Environment variables
apiUrl: "${ env(\"API_URL\") }"

# System properties
tempDir: "${ sys(\"java.io.tmpdir\") }"
```

## JQ Expression Contexts

JQ expressions appear in different contexts within Lemline workflows:

### In Set Tasks

Assign values to variables:

```yaml
- setValues:
    set:
      customerName: "${ .customer.firstName + \" \" + .customer.lastName }"
      orderTotal: "${ .items | map(.price * .quantity) | add }"
      shippingLabel: "${ { name: .customer.name, address: .customer.address } }"
```

### In Conditional Expressions

Control workflow execution:

```yaml
- checkOrder:
    if: "${ .order.total > 1000 }"
    do:
      - applyDiscount:
          # Apply discount logic
```

### In Switch Statements

Make multi-way decisions:

```yaml
- routeOrder:
    switch:
      - condition: "${ .order.total > 1000 }"
        do:
          - handlePremium:
              # Premium order handling
      
      - condition: "${ .order.items | length > 10 }"
        do:
          - handleBulk:
              # Bulk order handling
      
      - otherwise:
          do:
            - handleStandard:
                # Standard order handling
```

### In HTTP Requests

Construct dynamic HTTP requests:

```yaml
- callApi:
    callHTTP:
      url: "https://api.example.com/customers/${ .customerId }"
      method: "GET"
      headers:
        Authorization: "Bearer ${ .token }"
      query:
        include: "${ .includeDetails ? \"details\" : null }"
```

### In Event Definitions

Construct event data:

```yaml
- notifyShipping:
    emit:
      event: "OrderShipped"
      data:
        orderId: "${ .order.id }"
        customer: "${ .order.customer }"
        shippingInfo: "${ .shipping }"
```

## Best Practices

### Readability

Write clear, maintainable expressions:

1. **Use Meaningful Variable Names**: Choose descriptive names
2. **Break Down Complex Expressions**: Split into multiple smaller expressions
3. **Use Whitespace**: Format expressions for readability
4. **Add Comments**: Explain complex transformations

```yaml
# Instead of this:
result: "${ .d | map(select(.t > 100) | {i: .i, n: .n, v: (.p * .q)}) | add }"

# Use this:
set:
  # Filter for expensive items (price > 100)
  expensiveItems: "${ .data | map(select(.price > 100)) }"
  
  # Transform to summary format
  itemSummaries: "${ .expensiveItems | map({
    id: .id,
    name: .name,
    value: (.price * .quantity)
  }) }"
  
  # Calculate total value
  totalValue: "${ .itemSummaries | map(.value) | add }"
```

### Performance

Optimize for efficiency:

1. **Minimize Repeated Calculations**: Store intermediate results
2. **Use Appropriate Operations**: Choose efficient operations
3. **Be Careful with Recursion**: Avoid deep recursion
4. **Limit Data Size**: Process only necessary data

```yaml
# Instead of repeatedly calculating the same value:
item1Total: "${ .items[0].price * .items[0].quantity * (1 + .taxRate) }"
item2Total: "${ .items[1].price * .items[1].quantity * (1 + .taxRate) }"

# Calculate once and reuse:
set:
  # Calculate tax multiplier once
  taxMultiplier: "${ 1 + .taxRate }"
  
  # Calculate item totals using the multiplier
  itemTotals: "${ .items | map(.price * .quantity * .taxMultiplier) }"
```

### Error Handling

Handle potential errors:

1. **Check for Null Values**: Use conditionals to handle missing data
2. **Provide Defaults**: Use the `//` operator for default values
3. **Validate Input**: Check data structure before complex operations
4. **Handle Edge Cases**: Account for empty arrays, null values, etc.

```yaml
# Using defaults and null checks
customerName: "${ .customer.name // \"Guest\" }"
shippingAddress: "${ .order.shippingAddress or .customer.defaultAddress }"

# Handling potentially empty arrays
itemCount: "${ .items | length // 0 }"
hasItems: "${ (.items | length // 0) > 0 }"
```

### Testing JQ Expressions

Test expressions before using in workflows:

1. **Use the JQ Playground**: Test in tools like https://jqplay.org/
2. **Start Simple**: Build complexity incrementally
3. **Test with Sample Data**: Use realistic data examples
4. **Test Edge Cases**: Check behavior with null, empty, and extreme values

## Common Patterns

### Data Transformation

Transform between formats:

```yaml
# Flatten nested structure
flatCustomers: "${ .departments | map(.employees[]) }"

# Group by property
customersByCountry: "${ .customers | group_by(.country) }"

# Reshape data
apiResponse: "${ .customers | map({
  id: .id,
  name: .fullName,
  contact: {
    email: .emailAddress,
    phone: .phoneNumber
  }
}) }"
```

### Filtering and Selection

Filter data based on criteria:

```yaml
# Simple filter
activeUsers: "${ .users | map(select(.status == \"active\")) }"

# Complex condition
eligibleOrders: "${ .orders | map(select(
  .status == \"confirmed\" and
  .total > 100 and
  (.items | length) > 0
)) }"

# Top N selection
topProducts: "${ .products | sort_by(-.salesCount) | .[0:5] }"
```

### Aggregation

Aggregate and summarize data:

```yaml
# Simple aggregation
totalSales: "${ .orders | map(.total) | add }"

# Multiple aggregations
salesStats: "${ {
  count: .orders | length,
  total: .orders | map(.total) | add,
  average: (.orders | map(.total) | add) / (.orders | length),
  min: .orders | map(.total) | min,
  max: .orders | map(.total) | max
} }"

# Group and aggregate
salesByCategory: "${ .orders | group_by(.category) | map({
  category: .[0].category,
  count: length,
  total: map(.total) | add
}) }"
```

### Dynamic Path Access

Access properties with dynamic keys:

```yaml
# Dynamic property access
regionSales: "${ .sales[.selectedRegion] }"

# Dynamic nested access
configValue: "${ .config[.environment][.feature] }"

# Array of paths
selectedFields: "${ .data | map(.[$field] // null) }"
```

## Debugging JQ Expressions

When an expression doesn't work as expected:

1. **Start Simple**: Test with simpler expressions first
2. **Debug with Output**: Output intermediate values for debugging
3. **Check Data Structure**: Verify you're working with the expected structure
4. **Add Context**: Output context alongside expressions for comparison

```yaml
- debugExpression:
    set:
      # Output current context
      context: "${ . }"
      
      # Test simple field access
      customer: "${ .customer }"
      
      # Step-by-step debugging
      step1: "${ .orders }"
      step2: "${ .orders | map(.total) }"
      step3: "${ .orders | map(.total) | add }"
```

## Related Resources

- [JQ Manual](https://stedolan.github.io/jq/manual/)
- [JQ Playground](https://jqplay.org/)
- [Data Flow in Lemline](dsl-data-flow.md)
- [Workflow Definition Guide](lemline-howto-define-workflow.md)
- [Using JQ in Workflows](lemline-howto-jq.md)