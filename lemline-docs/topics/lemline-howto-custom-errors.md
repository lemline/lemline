# Creating and Managing Custom Errors

This guide shows how to define and use custom error types in your Lemline workflows for more precise error handling.

## Introduction to Custom Error Types

Custom error types allow you to define specific error categories for your business domain. While Lemline provides [standard error types](dsl-errors-overview.md) for common scenarios, you may need to define domain-specific errors to handle particular situations in your workflows.

## Defining Custom Errors

Custom errors are defined in the `use.errors` section of your workflow:

```yaml
use:
  errors:
    - name: "paymentDeclined"
      type: "https://example.com/errors/payment-declined"
      status: 400
      title: "Payment Method Declined"
      details: "The payment processor rejected the transaction"
    
    - name: "inventoryShortage"
      type: "https://example.com/errors/inventory-shortage"
      status: 409
      title: "Insufficient Inventory"
```

Each error definition includes:

- `name`: A unique identifier for referencing the error
- `type`: A URI that categorizes the error (conventionally a URL)
- `status`: A numeric code representing the error condition (often matching HTTP status codes)
- `title`: A human-readable error title (optional)
- `details`: A default error message (optional)

## Using Custom Errors

### Raising Custom Errors

You can raise custom errors using the `raise` task:

```yaml
- checkInventory:
    if: "${ .availableStock < .requestedQuantity }"
    raise:
      error: "inventoryShortage"
      with:
        details: "Requested ${ .requestedQuantity } items, but only ${ .availableStock } available"
```

The `with` property allows you to override default properties or provide dynamic values.

### Catching Custom Errors

Catch your custom errors in `try` blocks:

```yaml
- processOrder:
    try:
      do:
        - verifyInventory:
            # This task might raise inventoryShortage
        - processPayment:
            # This task might raise paymentDeclined
      catch:
        - error:
            with:
              type: "https://example.com/errors/inventory-shortage"
          do:
            - handleInventoryError:
                # Logic for handling inventory shortages
        
        - error:
            with:
              type: "https://example.com/errors/payment-declined"
          do:
            - handlePaymentError:
                # Logic for handling payment issues
```

## Error Hierarchies

You can create hierarchical error types by using consistent URI patterns:

```yaml
use:
  errors:
    - name: "validationError"
      type: "https://example.com/errors/validation"
      status: 400
    
    - name: "requiredFieldMissing"
      type: "https://example.com/errors/validation/required-field"
      status: 400
    
    - name: "invalidFormat"
      type: "https://example.com/errors/validation/format"
      status: 400
```

This approach allows you to catch either specific error types or entire categories:

```yaml
catch:
  - error:
      with:
        # This will catch all validation errors
        type: "https://example.com/errors/validation"
    do:
      # Generic validation error handling
```

## Dynamic Error Creation

You can create errors dynamically within your workflow:

```yaml
- validateUserInput:
    switch:
      - condition: "${ .username == null }"
        raise:
          error:
            type: "https://example.com/errors/validation/required-field"
            status: 400
            title: "Required Field Missing"
            details: "Username is required"
      
      - condition: "${ .email && !contains(.email, '@') }"
        raise:
          error:
            type: "https://example.com/errors/validation/format"
            status: 400
            title: "Invalid Format"
            details: "Email address is invalid"
```

This allows you to generate specific errors without pre-defining them.

## Error Mapping

Map external system errors to your custom errors:

```yaml
- callExternalService:
    try:
      do:
        - makeApiCall:
            callHTTP:
              url: "https://api.example.com/process"
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
            as: "apiError"
          do:
            - mapError:
                switch:
                  - condition: "${ .apiError.status == 429 }"
                    raise:
                      error: "rateLimitExceeded"
                  
                  - condition: "${ .apiError.status == 401 }"
                    raise:
                      error: "authenticationFailed"
                  
                  - condition: "${ .apiError.status >= 500 }"
                    raise:
                      error: "serviceUnavailable"
```

This pattern translates generic communication errors into more specific business domain errors.

## Best Practices

1. **Use consistent naming**: Follow a consistent naming convention for your error types.
2. **Use clear URI patterns**: Structure error type URIs in a logical hierarchy.
3. **Be specific**: Create specific error types rather than generic ones.
4. **Include meaningful details**: Error messages should provide actionable information.
5. **Map external errors**: Translate third-party error codes to your domain-specific error types.
6. **Document your errors**: Keep a catalog of custom error types for reference.

## Related Resources

- [Error Handling with Try-Catch](lemline-howto-try-catch.md)
- [Implementing Retry Mechanisms](lemline-howto-retry.md)
- [Debugging Workflows](lemline-howto-debug.md)
- [Standard Error Types](dsl-errors-overview.md)