---
title: How to use OpenAPI-defined services
---

# How to use OpenAPI-defined services

> **Not supported by the Lemline runtime yet.** Workflows that use `call: openapi` are accepted, but the task fails when it runs. Call the operation directly with `call: http` instead (see [HTTP Reference](lemline-ref-http.md)).


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
use:
  functions:
    petStoreService:
      call: openapi
      with:
        document:
          endpoint: https://petstore3.swagger.io/api/v3/openapi.json
        operationId: getPetById
        parameters: {}
do:
  - GetPet:
      call: petStoreService
      with:
        petId: 123
      then: ProcessPetData
  - ProcessPetData:
      set:
        petName: ${ .name }
        petStatus: ${ .status }
        message: ${ "Pet \(.name) is currently \(.status)" }
      then: NextTask
```

## Referencing OpenAPI Specifications

### External Specification URLs

You can reference an external OpenAPI specification using a URL:

```yaml
use:
  functions:
    weatherService:
      call: openapi
      with:
        document:
          endpoint: https://api.weather.com/openapi.json
        operationId: getCurrentWeather
        parameters: {}
```

### Local Specification Files

You can also reference a local file containing the OpenAPI specification:

```yaml
use:
  functions:
    internalService:
      call: openapi
      with:
        document:
          endpoint: file:///path/to/api-spec.yaml
        operationId: getStatus
        parameters: {}
```

### OpenAPI Document

Save the OpenAPI document as a separate resource and reference it through `with.document.endpoint`. For example, the following OpenAPI document defines the `getStatus` operation.

```yaml
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
```

## Selecting Operations

### By Operation ID

The preferred way to select an operation is using the `operationId` from the specification:

```yaml
use:
  functions:
    userService:
      call: openapi
      with:
        document:
          endpoint: https://api.example.com/openapi.json
        operationId: getUserById
        parameters: {}
```

### Identifying a Product Operation

Give the operation an ID in the OpenAPI document and pass the path parameter by name.

```yaml
use:
  functions:
    productService:
      call: openapi
      with:
        document:
          endpoint: https://api.example.com/openapi.json
        operationId: getProduct
        parameters:
          productId: ${ .productId }
```

## Making API Calls with Parameters

### Path Parameters

Path parameters are specified directly in the `data` section:

```yaml
  - GetUserDetails:
      call: userService
      with:
        userId: ${ .userId }
      then: ProcessUserData
```

### Query Parameters

Query parameters are specified in the `query` field:

```yaml
  - SearchProducts:
      call: productSearch
      with:
        query:
          category: ${ .productCategory }
          minPrice: ${ .priceRange.min }
          maxPrice: ${ .priceRange.max }
          sort: "price"
      then: ProcessSearchResults
```

### Request Body

For operations that require a request body:

```yaml
  - CreateOrder:
      call: orderService
      with:
        body:
          customerId: ${ .customerId }
          items:
            - productId: ${ .items[0].id }
              quantity: ${ .items[0].quantity }
            - productId: ${ .items[1].id }
              quantity: ${ .items[1].quantity }
          shippingAddress:
            street: ${ .shippingAddress.street }
            city: ${ .shippingAddress.city }
            zipCode: ${ .shippingAddress.zipCode }
      then: ProcessOrderResponse
```

### Headers

Custom headers can be set in the `headers` field:

```yaml
  - GetResource:
      call: resourceService
      with:
        resourceId: ${ .resourceId }
        headers:
          X-Request-ID: ${ .requestId }
          Accept-Language: "en-US"
      then: ProcessResource
```

## Handling Authentication

Lemline supports all authentication methods defined in the OpenAPI specification:

### API Key Authentication

```yaml
use:
  functions:
    secureService:
      call: openapi
      with:
        document:
          endpoint: https://api.example.com/openapi.json
        operationId: getSecureResource
        parameters:
          "X-API-Key": ${ $secrets.api_key }
```

### OAuth2 Authentication

```yaml
use:
  functions:
    oauthService:
      call: openapi
      with:
        document:
          endpoint: https://api.example.com/openapi.json
        operationId: getProtectedResource
        parameters: {}
        authentication:
          oauth2:
            authority: "https://auth.example.com/token"
            grant: client_credentials
            client:
              id: ${ $secrets.client_id }
              secret: ${ $secrets.client_secret }
            scopes:
              - "read:resources"
```

### Basic Authentication

```yaml
use:
  functions:
    basicAuthService:
      call: openapi
      with:
        document:
          endpoint: https://api.example.com/openapi.json
        operationId: getAuthenticatedResource
        parameters: {}
        authentication:
          basic:
            username: ${ $secrets.username }
            password: ${ $secrets.password }
```

## Request and Response Validation

One of the key benefits of using OpenAPI integration is automatic validation:

### Request Validation

Lemline validates your request against the schema before sending:

```yaml
  - CreateUser:
      call: userService
      with:
        body:
          username: ${ .username }
          email: ${ .email }
          age: -5
      then: ProcessResponse
```

If the request doesn't match the schema, a `VALIDATION_ERROR` will be raised.

### Response Validation

Responses are also validated against the schema:

```yaml
  - GetWeather:
      call: weatherService
      with:
        location: ${ .location }
      then: ProcessWeather
```

If the service returns a response that doesn't match the schema, a `VALIDATION_ERROR` will be raised, indicating a potential problem with the service.

## Error Handling

### Using Try/Catch for OpenAPI Calls

Wrap OpenAPI calls in a `try` task to handle errors:

```yaml
  - AttemptApiCall:
      try:
        - GetProduct:
            call: productService
            with:
              productId: ${ .productId }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandleValidationError
                    when: ${ $failure.type == "VALIDATION_ERROR" }
                - case2:
                    then: HandleHttpError
                    when: ${ $failure.type == "HTTP_ERROR" }
                - case3:
                    then: HandleGenericError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT2S
      then: ProcessProduct
```

### Handling Specific Status Codes

Handle specific status codes defined in the OpenAPI spec:

```yaml
  - AttemptUserLookup:
      try:
        - GetUser:
            call: userService
            with:
              userId: ${ .userId }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandleUserNotFound
                    when: ${ $failure.status == 404 }
                - case2:
                    then: HandleForbidden
                    when: ${ $failure.status == 403 }
                - case3:
                    then: HandleOtherError
      then: ProcessUser
```

## Advanced OpenAPI Features

### Using Server Variables

If the OpenAPI specification defines server variables, you can set them:

```yaml
use:
  functions:
    multiRegionService:
      call: openapi
      with:
        document:
          endpoint: https://api.example.com/openapi.json
        operationId: getResource
        parameters: {}
```

### Selecting Specific Servers

If the specification defines multiple servers, you can select one:

```yaml
use:
  functions:
    environmentSpecificService:
      call: openapi
      with:
        document:
          endpoint: https://api.example.com/openapi.json
        operationId: getResource
        parameters: {}
```

### Content Type Selection

You can specify which content type to use when multiple are available:

```yaml
  - GetResourceWithFormat:
      call: resourceService
      with:
        resourceId: ${ .resourceId }
        headers:
          Accept: "application/xml"
      then: ProcessResourceXml
```

## Real-World Example: E-Commerce API Integration

Here's a complete example that integrates with an e-commerce API defined via OpenAPI:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: order-processing
  version: 1.0.0
  title: E-Commerce Order Processing
do:
  - ReceiveOrder:
      set:
        orderId: ${ $workflow.input.orderId }
        customerId: ${ $workflow.input.customerId }
        items: ${ $workflow.input.items }
      then: ValidateCustomer
  - ValidateCustomer:
      try:
        - GetCustomer:
            call: openapi
            with:
              document:
                endpoint: https://api.ecommerce-example.com/openapi.json
              operationId: ecommerceAPI
              parameters:
                operation: getCustomerById
                customerId: ${ .customerId }
              authentication:
                oauth2:
                  authority: "https://auth.ecommerce-example.com/token"
                  grant: client_credentials
                  client:
                    id: ${ $secrets.ecommerce_client_id }
                    secret: ${ $secrets.ecommerce_client_secret }
                  scopes:
                    - "orders:read"
                    - "orders:write"
                    - "inventory:read"
                    - "payments:write"
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandleInvalidCustomer
                    when: ${ $failure.status == 404 }
                - case2:
                    then: HandleValidationError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT2S
      then: CheckInventory
  - CheckInventory:
      try:
        - VerifyInventory:
            call: openapi
            with:
              document:
                endpoint: https://api.ecommerce-example.com/openapi.json
              operationId: ecommerceAPI
              parameters:
                operation: checkInventoryAvailability
                body:
                  items: '${ .items | map({productId: .productId, quantity: .quantity}) }'
              authentication:
                oauth2:
                  authority: "https://auth.ecommerce-example.com/token"
                  grant: client_credentials
                  client:
                    id: ${ $secrets.ecommerce_client_id }
                    secret: ${ $secrets.ecommerce_client_secret }
                  scopes:
                    - "orders:read"
                    - "orders:write"
                    - "inventory:read"
                    - "payments:write"
      catch:
        as: failure
        then: HandleInventoryError
        retry:
          limit:
            attempt:
              count: 2
      then: EvaluateInventory
  - EvaluateInventory:
      switch:
        - case1:
            then: CalculateTotal
            when: ${ .available == true }
        - default:
            then: HandleUnavailableInventory
  - CalculateTotal:
      try:
        - GetPricing:
            call: openapi
            with:
              document:
                endpoint: https://api.ecommerce-example.com/openapi.json
              operationId: ecommerceAPI
              parameters:
                operation: calculateOrderTotal
                body:
                  customerId: ${ .customerId }
                  items: '${ .items | map({productId: .productId, quantity: .quantity}) }'
                  applyCoupons: true
              authentication:
                oauth2:
                  authority: "https://auth.ecommerce-example.com/token"
                  grant: client_credentials
                  client:
                    id: ${ $secrets.ecommerce_client_id }
                    secret: ${ $secrets.ecommerce_client_secret }
                  scopes:
                    - "orders:read"
                    - "orders:write"
                    - "inventory:read"
                    - "payments:write"
      catch:
        as: failure
        then: HandlePricingError
      then: ProcessPayment
  - ProcessPayment:
      try:
        - ChargePayment:
            call: openapi
            with:
              document:
                endpoint: https://api.ecommerce-example.com/openapi.json
              operationId: ecommerceAPI
              parameters:
                operation: processPayment
                body:
                  customerId: ${ .customerId }
                  orderId: ${ .orderId }
                  amount: ${ .total }
                  paymentMethod: ${ .paymentMethod }
              authentication:
                oauth2:
                  authority: "https://auth.ecommerce-example.com/token"
                  grant: client_credentials
                  client:
                    id: ${ $secrets.ecommerce_client_id }
                    secret: ${ $secrets.ecommerce_client_secret }
                  scopes:
                    - "orders:read"
                    - "orders:write"
                    - "inventory:read"
                    - "payments:write"
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandlePaymentRejected
                    when: ${ $failure.status == 402 }
                - case2:
                    then: HandlePaymentError
        retry:
          limit:
            attempt:
              count: 2
      then: CreateOrder
  - CreateOrder:
      try:
        - SubmitOrder:
            call: openapi
            with:
              document:
                endpoint: https://api.ecommerce-example.com/openapi.json
              operationId: ecommerceAPI
              parameters:
                operation: createOrder
                body:
                  customerId: ${ .customerId }
                  orderItems: ${ .items }
                  shippingAddress: ${ .GetCustomer.defaultShippingAddress }
                  billingAddress: ${ .GetCustomer.defaultBillingAddress }
                  total: ${ .total }
                  paymentId: ${ .ChargePayment.paymentId }
              authentication:
                oauth2:
                  authority: "https://auth.ecommerce-example.com/token"
                  grant: client_credentials
                  client:
                    id: ${ $secrets.ecommerce_client_id }
                    secret: ${ $secrets.ecommerce_client_secret }
                  scopes:
                    - "orders:read"
                    - "orders:write"
                    - "inventory:read"
                    - "payments:write"
      catch:
        as: failure
        then: HandleOrderCreationError
        retry:
          limit:
            attempt:
              count: 3
      then: CompleteOrder
  - CompleteOrder:
      set:
        status: "COMPLETED"
        message: "Order processed successfully"
        orderId: ${ .SubmitOrder.orderId }
        trackingNumber: ${ .SubmitOrder.trackingNumber }
        estimatedDelivery: ${ .SubmitOrder.estimatedDelivery }
      then: end
  - HandleInvalidCustomer:
      set:
        status: "REJECTED"
        reason: "Invalid customer account"
      then: end
  - HandleValidationError:
      set:
        status: "ERROR"
        reason: "Customer validation failed"
        error: ${ $context.error }
      then: end
  - HandleInventoryError:
      set:
        status: "ERROR"
        reason: "Inventory check failed"
        error: ${ $context.error }
      then: end
  - HandleUnavailableInventory:
      set:
        status: "REJECTED"
        reason: "Some items are unavailable"
        unavailableItems: ${ .unavailableItems }
      then: end
  - HandlePricingError:
      set:
        status: "ERROR"
        reason: "Failed to calculate pricing"
        error: ${ $context.error }
      then: end
  - HandlePaymentRejected:
      set:
        status: "REJECTED"
        reason: "Payment was rejected"
        details: ${ .declineReason }
      then: end
  - HandlePaymentError:
      set:
        status: "ERROR"
        reason: "Payment processing failed"
        error: ${ $context.error }
      then: end
  - HandleOrderCreationError:
      set:
        status: "ERROR"
        reason: "Order creation failed"
        error: ${ $context.error }
      then: end
```

This workflow uses a single OpenAPI-defined e-commerce service for all operations, with appropriate error handling for each step.

## Best Practices for OpenAPI Integration

1. **Use OpenAPI 3.0+ Specifications**: Ensure you're using modern OpenAPI specifications (3.0 or later)
2. **Prefer operationId**: Use the operation ID declared by the OpenAPI document
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