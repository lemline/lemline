---
title: How to use OpenAPI-defined services
---

# How to use OpenAPI-defined services

This guide explains how to integrate with OpenAPI-defined services in your Lemline workflows. You'll learn how to reference OpenAPI definitions, make API calls, handle responses, and implement error handling.

## Understanding OpenAPI Integration

OpenAPI (formerly known as Swagger) is a specification for describing RESTful APIs. Lemline provides native support for OpenAPI-defined services, allowing you to:

- Reference external OpenAPI definitions
- Make calls to operations defined in the specification
- Use typed request/response models
- Leverage built-in validation
- Utilize the authentication mechanisms defined in the spec

## Benefits of Using OpenAPI Integration

Using OpenAPI integration in Lemline offers several advantages:

1. **Type Safety**: Request and response structures are validated against the schema
2. **Discoverability**: Available endpoints and operations are clearly defined
3. **Consistency**: All calls to the service follow the same patterns
4. **Reduced Boilerplate**: Authentication and common headers are defined once
5. **Better Documentation**: The OpenAPI specification serves as documentation
6. **Automatic Validation**: Input is validated before requests are sent

## Basic OpenAPI Integration Structure

To use an OpenAPI-defined service in Lemline, you need to:

1. Define a function that references the OpenAPI specification
2. Specify the operation to call
3. Create a call task that uses the function

Here's a basic example:

```yaml
functions:
  - name: petStoreService
    type: openapi
    specification: https://petstore3.swagger.io/api/v3/openapi.json
    operation: getPetById

tasks:
  - name: GetPet
    type: call
    function: petStoreService
    data:
      petId: 123
    next: ProcessPetData
  
  - name: ProcessPetData
    type: set
    data:
      petName: ".name"
      petStatus: ".status"
      message: "Pet .name is currently .status"
    next: NextTask
```

## Referencing OpenAPI Specifications

### External Specification URLs

You can reference an external OpenAPI specification using a URL:

```yaml
functions:
  - name: weatherService
    type: openapi
    specification: https://api.weather.com/openapi.json
    operation: getCurrentWeather
```

### Local Specification Files

You can also reference a local file containing the OpenAPI specification:

```yaml
functions:
  - name: internalService
    type: openapi
    specification: file:///path/to/api-spec.yaml
    operation: getStatus
```

### Embedded Specifications

For smaller APIs or testing, you can embed the specification directly:

```yaml
functions:
  - name: simpleService
    type: openapi
    specification:
      openapi: "3.0.0"
      info:
        title: "Simple API"
        version: "1.0.0"
      paths:
        /status:
          get:
            operationId: getStatus
            responses:
              '200':
                description: "Status response"
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        status:
                          type: string
    operation: getStatus
```

## Selecting Operations

### By Operation ID

The preferred way to select an operation is using the `operationId` from the specification:

```yaml
functions:
  - name: userService
    type: openapi
    specification: https://api.example.com/openapi.json
    operation: getUserById
```

### By Path and Method

If `operationId` is not available, you can specify the path and method:

```yaml
functions:
  - name: productService
    type: openapi
    specification: https://api.example.com/openapi.json
    operation:
      path: /products/{productId}
      method: GET
```

## Making API Calls with Parameters

### Path Parameters

Path parameters are specified directly in the `data` section:

```yaml
- name: GetUserDetails
  type: call
  function: userService
  data:
    userId: ".userId"  # Matches {userId} in the path
  next: ProcessUserData
```

### Query Parameters

Query parameters are specified in the `query` field:

```yaml
- name: SearchProducts
  type: call
  function: productSearch
  data:
    query:
      category: ".productCategory"
      minPrice: ".priceRange.min"
      maxPrice: ".priceRange.max"
      sort: "price"
  next: ProcessSearchResults
```

### Request Body

For operations that require a request body:

```yaml
- name: CreateOrder
  type: call
  function: orderService
  data:
    body:
      customerId: ".customerId"
      items:
        - productId: ".items[0].id"
          quantity: ".items[0].quantity"
        - productId: ".items[1].id"
          quantity: ".items[1].quantity"
      shippingAddress:
        street: ".shippingAddress.street"
        city: ".shippingAddress.city"
        zipCode: ".shippingAddress.zipCode"
  next: ProcessOrderResponse
```

### Headers

Custom headers can be set in the `headers` field:

```yaml
- name: GetResource
  type: call
  function: resourceService
  data:
    resourceId: ".resourceId"
    headers:
      X-Request-ID: ".requestId"
      Accept-Language: "en-US"
  next: ProcessResource
```

## Handling Authentication

Lemline supports all authentication methods defined in the OpenAPI specification:

### API Key Authentication

```yaml
functions:
  - name: secureService
    type: openapi
    specification: https://api.example.com/openapi.json
    operation: getSecureResource
    auth:
      type: apiKey
      name: "X-API-Key"
      value: "${api_key}"  # Reference to a secret
```

### OAuth2 Authentication

```yaml
functions:
  - name: oauthService
    type: openapi
    specification: https://api.example.com/openapi.json
    operation: getProtectedResource
    auth:
      type: oauth2
      flow: clientCredentials
      tokenUrl: "https://auth.example.com/token"
      clientId: "${client_id}"
      clientSecret: "${client_secret}"
      scopes:
        - "read:resources"
```

### Basic Authentication

```yaml
functions:
  - name: basicAuthService
    type: openapi
    specification: https://api.example.com/openapi.json
    operation: getAuthenticatedResource
    auth:
      type: basic
      username: "${username}"
      password: "${password}"
```

## Request and Response Validation

One of the key benefits of using OpenAPI integration is automatic validation:

### Request Validation

Lemline validates your request against the schema before sending:

```yaml
- name: CreateUser
  type: call
  function: userService
  data:
    body:
      username: ".username"
      email: ".email"
      # If the schema requires age to be a positive integer
      # and you provide a negative value, validation will fail
      age: -5  # This would fail validation if age must be positive
  next: ProcessResponse
```

If the request doesn't match the schema, a `VALIDATION_ERROR` will be raised.

### Response Validation

Responses are also validated against the schema:

```yaml
- name: GetWeather
  type: call
  function: weatherService
  data:
    location: ".location"
  next: ProcessWeather
```

If the service returns a response that doesn't match the schema, a `VALIDATION_ERROR` will be raised, indicating a potential problem with the service.

## Error Handling

### Using Try/Catch for OpenAPI Calls

Wrap OpenAPI calls in a `try` task to handle errors:

```yaml
- name: AttemptApiCall
  type: try
  retry:
    maxAttempts: 3
    interval: PT2S
  catch:
    - error: "VALIDATION_ERROR"
      next: HandleValidationError
    - error: "HTTP_ERROR"
      next: HandleHttpError
    - error: "*"
      next: HandleGenericError
  do:
    - name: GetProduct
      type: call
      function: productService
      data:
        productId: ".productId"
  next: ProcessProduct
```

### Handling Specific Status Codes

Handle specific status codes defined in the OpenAPI spec:

```yaml
- name: AttemptUserLookup
  type: try
  catch:
    - error: "HTTP_ERROR"
      status: 404
      next: HandleUserNotFound
    - error: "HTTP_ERROR"
      status: 403
      next: HandleForbidden
    - error: "*"
      next: HandleOtherError
  do:
    - name: GetUser
      type: call
      function: userService
      data:
        userId: ".userId"
  next: ProcessUser
```

## Advanced OpenAPI Features

### Using Server Variables

If the OpenAPI specification defines server variables, you can set them:

```yaml
functions:
  - name: multiRegionService
    type: openapi
    specification: https://api.example.com/openapi.json
    operation: getResource
    server:
      variables:
        region: "eu-west-1"
```

### Selecting Specific Servers

If the specification defines multiple servers, you can select one:

```yaml
functions:
  - name: environmentSpecificService
    type: openapi
    specification: https://api.example.com/openapi.json
    operation: getResource
    server:
      url: "https://staging-api.example.com/v1"
```

### Content Type Selection

You can specify which content type to use when multiple are available:

```yaml
- name: GetResourceWithFormat
  type: call
  function: resourceService
  data:
    resourceId: ".resourceId"
    headers:
      Accept: "application/xml"  # Request XML instead of JSON
  next: ProcessResourceXml
```

## Real-World Example: E-Commerce API Integration

Here's a complete example that integrates with an e-commerce API defined via OpenAPI:

```yaml
id: order-processing
name: E-Commerce Order Processing
version: '1.0'
specVersion: '1.0'
start: ReceiveOrder
functions:
  - name: ecommerceAPI
    type: openapi
    specification: https://api.ecommerce-example.com/openapi.json
    auth:
      type: oauth2
      flow: clientCredentials
      tokenUrl: "https://auth.ecommerce-example.com/token"
      clientId: "${ecommerce_client_id}"
      clientSecret: "${ecommerce_client_secret}"
      scopes:
        - "orders:read"
        - "orders:write"
        - "inventory:read"
        - "payments:write"
tasks:
  - name: ReceiveOrder
    type: set
    data:
      orderId: "$WORKFLOW.input.orderId"
      customerId: "$WORKFLOW.input.customerId"
      items: "$WORKFLOW.input.items"
    next: ValidateCustomer
  
  - name: ValidateCustomer
    type: try
    retry:
      maxAttempts: 3
      interval: PT2S
    catch:
      - error: "HTTP_ERROR"
        status: 404
        next: HandleInvalidCustomer
      - error: "*"
        next: HandleValidationError
    do:
      - name: GetCustomer
        type: call
        function: ecommerceAPI
        data:
          operation: getCustomerById
          customerId: ".customerId"
    next: CheckInventory
  
  - name: CheckInventory
    type: try
    retry:
      maxAttempts: 2
    catch:
      - error: "*"
        next: HandleInventoryError
    do:
      - name: VerifyInventory
        type: call
        function: ecommerceAPI
        data:
          operation: checkInventoryAvailability
          body:
            items: ".items | map({productId: .productId, quantity: .quantity})"
    next: EvaluateInventory
  
  - name: EvaluateInventory
    type: switch
    conditions:
      - condition: ".available == true"
        next: CalculateTotal
      - condition: true
        next: HandleUnavailableInventory
  
  - name: CalculateTotal
    type: try
    catch:
      - error: "*"
        next: HandlePricingError
    do:
      - name: GetPricing
        type: call
        function: ecommerceAPI
        data:
          operation: calculateOrderTotal
          body:
            customerId: ".customerId"
            items: ".items | map({productId: .productId, quantity: .quantity})"
            applyCoupons: true
    next: ProcessPayment
  
  - name: ProcessPayment
    type: try
    retry:
      maxAttempts: 2
    catch:
      - error: "HTTP_ERROR"
        status: 402
        next: HandlePaymentRejected
      - error: "*"
        next: HandlePaymentError
    do:
      - name: ChargePayment
        type: call
        function: ecommerceAPI
        data:
          operation: processPayment
          body:
            customerId: ".customerId"
            orderId: ".orderId"
            amount: ".total"
            paymentMethod: ".paymentMethod"
    next: CreateOrder
  
  - name: CreateOrder
    type: try
    retry:
      maxAttempts: 3
    catch:
      - error: "*"
        next: HandleOrderCreationError
    do:
      - name: SubmitOrder
        type: call
        function: ecommerceAPI
        data:
          operation: createOrder
          body:
            customerId: ".customerId"
            orderItems: ".items"
            shippingAddress: ".GetCustomer.defaultShippingAddress"
            billingAddress: ".GetCustomer.defaultBillingAddress"
            total: ".total"
            paymentId: ".ChargePayment.paymentId"
    next: CompleteOrder
  
  - name: CompleteOrder
    type: set
    data:
      status: "COMPLETED"
      message: "Order processed successfully"
      orderId: ".SubmitOrder.orderId"
      trackingNumber: ".SubmitOrder.trackingNumber"
      estimatedDelivery: ".SubmitOrder.estimatedDelivery"
    end: true
  
  # Error handling tasks...
  - name: HandleInvalidCustomer
    type: set
    data:
      status: "REJECTED"
      reason: "Invalid customer account"
    end: true
  
  - name: HandleValidationError
    type: set
    data:
      status: "ERROR"
      reason: "Customer validation failed"
      error: "$WORKFLOW.error"
    end: true
  
  - name: HandleInventoryError
    type: set
    data:
      status: "ERROR"
      reason: "Inventory check failed"
      error: "$WORKFLOW.error"
    end: true
  
  - name: HandleUnavailableInventory
    type: set
    data:
      status: "REJECTED"
      reason: "Some items are unavailable"
      unavailableItems: ".unavailableItems"
    end: true
  
  - name: HandlePricingError
    type: set
    data:
      status: "ERROR"
      reason: "Failed to calculate pricing"
      error: "$WORKFLOW.error"
    end: true
  
  - name: HandlePaymentRejected
    type: set
    data:
      status: "REJECTED"
      reason: "Payment was rejected"
      details: ".declineReason"
    end: true
  
  - name: HandlePaymentError
    type: set
    data:
      status: "ERROR"
      reason: "Payment processing failed"
      error: "$WORKFLOW.error"
    end: true
  
  - name: HandleOrderCreationError
    type: set
    data:
      status: "ERROR"
      reason: "Order creation failed"
      error: "$WORKFLOW.error"
    end: true
```

This workflow uses a single OpenAPI-defined e-commerce service for all operations, with appropriate error handling for each step.

## Best Practices for OpenAPI Integration

1. **Use OpenAPI 3.0+ Specifications**: Ensure you're using modern OpenAPI specifications (3.0 or later)
2. **Prefer operationId**: Use operation IDs instead of path/method combinations when available
3. **Validate Specifications**: Ensure your OpenAPI spec is valid before integrating
4. **Handle Schema Validation Errors**: Implement proper error handling for validation failures
5. **Prefer Spec-Defined Auth**: Use authentication methods defined in the spec
6. **Version Your APIs**: Use versioned API specs to ensure stability
7. **Cache Specifications**: For frequently used APIs, consider caching the specification
8. **Monitor Schema Changes**: Be alert to changes in the API schema that might break your workflows
9. **Test with Mock Servers**: Use OpenAPI mock servers for testing before connecting to production APIs
10. **Document Function References**: Clearly document which operations from the spec are being used

## Troubleshooting Common Issues

### Schema Validation Errors

If you encounter validation errors:
- Check that your data structure matches the schema
- Verify data types (strings vs. numbers, etc.)
- Ensure required fields are provided
- Check array formats and nested objects

### API Operation Not Found

If an operation cannot be found:
- Verify the operation ID is correct
- Check the specification URL is accessible
- Ensure you're using the correct version of the API
- Verify the path and method if using that approach

### Authentication Failures

For authentication issues:
- Verify your credentials are correct
- Check that you're using the right authentication type
- Ensure you have the necessary scopes
- Verify the token URL is correct (for OAuth2)

### Handling JSON Schema References

If the schema uses `$ref` references:
- Ensure the referenced schemas are accessible
- Check for circular references
- Verify that all referenced components are available

## Next Steps

- Learn about [running scripts or containers](lemline-howto-run.md)
- Explore [how to call gRPC functions](lemline-howto-grpc.md)
- Understand [how to emit and listen for events](lemline-howto-events.md)